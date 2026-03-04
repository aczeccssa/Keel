package com.keel.kernel.isolation

import com.keel.kernel.config.KeelConstants
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.observability.ObservabilityHub
import com.keel.kernel.observability.ObservabilityTracing
import com.keel.kernel.observability.TraceSpanEvent
import com.keel.kernel.plugin.PluginChannelHealth
import com.keel.kernel.plugin.PluginConfig
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointDefinition
import com.keel.kernel.plugin.PluginFailureRecord
import com.keel.kernel.plugin.PluginGeneration
import com.keel.kernel.plugin.PluginHealthState
import com.keel.kernel.plugin.PluginProcessState
import com.keel.kernel.plugin.PluginRuntimeDiagnostics
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.uds.runtime.HandshakeRequest
import com.keel.uds.runtime.HandshakeResponse
import com.keel.uds.runtime.HealthRequest
import com.keel.uds.runtime.HealthResponse
import com.keel.uds.runtime.InvokeRequest
import com.keel.uds.runtime.InvokeResponse
import com.keel.uds.runtime.PLUGIN_UDS_PROTOCOL_VERSION
import com.keel.uds.runtime.PluginBackpressureEvent
import com.keel.uds.runtime.PluginDrainCompleteEvent
import com.keel.uds.runtime.PluginEventQueueOverflowEvent
import com.keel.uds.runtime.PluginFailureEvent
import com.keel.uds.runtime.PluginLogEvent
import com.keel.uds.runtime.PluginTraceEvent
import com.keel.uds.runtime.PluginDisposedEvent
import com.keel.uds.runtime.PluginReadyEvent
import com.keel.uds.runtime.PluginRuntimeEvent
import com.keel.uds.runtime.PluginStoppingEvent
import com.keel.uds.runtime.PluginUdsFrameCodec
import com.keel.uds.runtime.PluginUdsJson
import com.keel.uds.runtime.ReloadPrepareRequest
import com.keel.uds.runtime.ReloadPrepareResponse
import com.keel.uds.runtime.ShutdownRequest
import com.keel.uds.runtime.ShutdownResponse
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.opentelemetry.context.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PluginUdsSocketPaths(
    val invokePath: Path,
    val adminPath: Path,
    val eventPath: Path
)

@OptIn(ExperimentalUuidApi::class)
class PluginProcessSupervisor(
    private val descriptor: PluginDescriptor,
    private val pluginClassName: String,
    private val config: PluginConfig,
    private val expectedEndpoints: List<PluginEndpointDefinition<*, *>>,
    private val classpath: String,
    private val socketPaths: PluginUdsSocketPaths,
    private val runtimeDir: Path,
    private val generation: PluginGeneration,
    private val onStateChange: (PluginProcessState) -> Unit,
    private val onHealthChange: (PluginHealthState) -> Unit,
    private val onFailure: (PluginFailureRecord) -> Unit = {},
    private val observabilityHub: ObservabilityHub? = null
) {
    private val logger = KeelLoggerService.getLogger("PluginProcessSupervisor")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authToken: String = Uuid.random().toString()

    private var process: Process? = null
    private var healthJob: Job? = null
    private var eventServerJob: Job? = null
    private var processExitJob: Job? = null
    private var eventServer: ServerSocketChannel? = null
    private var controlFailureCount: Int = 0
    private var eventChannelConnected: Boolean = false
    private var readyEventReceived: Boolean = false
    private var lastHealthLatencyMs: Long? = null
    private var lastAdminLatencyMs: Long? = null
    private var lastEventAtEpochMs: Long? = null
    private var droppedLogCount: Long = 0
    private var eventOverflowed: Boolean = false
    private var eventQueueDepth: Int = 0

    suspend fun start() {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                prepareEventServer()
                launchProcess()
                waitUntilReady()
                onStateChange(PluginProcessState.RUNNING)
                recomputeHealth()
                startHealthChecks()
                watchProcessExit()
                return
            } catch (error: Throwable) {
                lastError = error
                recordFailure("start", error.message ?: "Failed to start isolated plugin")
                logger.warn("Failed to start isolated plugin ${descriptor.pluginId} attempt=${attempt + 1}: ${error.message}")
                stopProcess(force = true)
                onStateChange(PluginProcessState.FAILED)
                onHealthChange(PluginHealthState.UNREACHABLE)
            }
        }
        throw IllegalStateException("Failed to start isolated plugin ${descriptor.pluginId}", lastError)
    }

    suspend fun stop() {
        onStateChange(PluginProcessState.STOPPING)
        healthJob?.cancel()
        runCatching {
            sendAdminMessage(
                ShutdownRequest(
                    pluginId = descriptor.pluginId,
                    generation = generation.value,
                    timestamp = System.currentTimeMillis(),
                    messageId = newMessageId(),
                    authToken = authToken
                ),
                ShutdownResponse.serializer(),
                config.stopTimeoutMs
            )
        }
        stopProcess(force = false)
        onStateChange(PluginProcessState.STOPPED)
        onHealthChange(PluginHealthState.UNKNOWN)
    }

    fun processId(): Long? = process?.pid()

    fun processHandle(): ProcessHandle? = process?.toHandle()

    fun forceKill(): Boolean {
        val current = process ?: return false
        current.destroyForcibly()
        recordFailure("force-kill", "Kernel force-killed external plugin process")
        return true
    }

    fun diagnosticsSnapshot(): PluginRuntimeDiagnostics {
        val processAlive = process?.isAlive
        return PluginRuntimeDiagnostics(
            processAlive = processAlive,
            adminChannelHealth = adminChannelHealth(processAlive),
            eventChannelHealth = eventChannelHealth(processAlive),
            droppedLogCount = droppedLogCount,
            eventQueueDepth = eventQueueDepth,
            eventOverflowed = eventOverflowed,
            lastHealthLatencyMs = lastHealthLatencyMs,
            lastAdminLatencyMs = lastAdminLatencyMs,
            lastEventAtEpochMs = lastEventAtEpochMs
        )
    }

    suspend fun invoke(
        endpoint: PluginEndpointDefinition<*, *>,
        call: ApplicationCall,
        bodyJson: String?
    ): InvokeResponse {
        if (process?.isAlive != true) {
            onStateChange(PluginProcessState.FAILED)
            onHealthChange(PluginHealthState.UNREACHABLE)
            return unavailableResponse(call, "Plugin '${descriptor.pluginId}' process is unavailable")
        }

        val bodyBytes = bodyJson?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        val maxPayloadBytes = endpoint.executionPolicy.maxPayloadBytes
        if (maxPayloadBytes != null && bodyBytes > maxPayloadBytes && !endpoint.executionPolicy.allowChunkedTransfer) {
            return oversizedResponse(call, "Request payload exceeds $maxPayloadBytes bytes")
        }

        val traceContext = call.attributes.getOrNull(ObservabilityTracing.TRACE_CONTEXT_KEY) ?: Context.current()
        val (traceparent, tracestate) = ObservabilityTracing.inject(traceContext)
        val request = InvokeRequest(
            pluginId = descriptor.pluginId,
            generation = generation.value,
            timestamp = System.currentTimeMillis(),
            messageId = newMessageId(),
            authToken = authToken,
            requestId = call.request.headers["X-Request-Id"] ?: newMessageId(),
            endpointId = endpoint.endpointId,
            method = endpoint.method.value,
            rawPath = call.request.path(),
            pathParameters = call.parameters.entries().associate { it.key to it.value.first() },
            queryParameters = call.request.queryParameters.entries().associate { it.key to it.value },
            headers = call.request.headers.entries().associate { it.key to it.value },
            traceparent = traceparent,
            tracestate = tracestate,
            bodyJson = bodyJson,
            maxPayloadBytes = maxPayloadBytes,
            allowChunkedTransfer = endpoint.executionPolicy.allowChunkedTransfer
        )

        val timeoutMs = endpoint.executionPolicy.timeoutMs ?: config.callTimeoutMs
        val response = runCatching {
            sendInvokeMessage(request, InvokeResponse.serializer(), timeoutMs)
        }.getOrElse { error ->
            logger.warn("Invoke channel failed pluginId=${descriptor.pluginId} endpoint=${endpoint.endpointId}: ${error.message}")
            return unavailableResponse(call, "Plugin '${descriptor.pluginId}' invoke channel is unavailable")
        }

        val responseBytes = response.bodyJson?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        if (maxPayloadBytes != null && responseBytes > maxPayloadBytes && !endpoint.executionPolicy.allowChunkedTransfer) {
            return oversizedResponse(call, "Response payload exceeds $maxPayloadBytes bytes")
        }
        return response
    }

    private fun prepareEventServer() {
        socketPaths.eventPath.parent?.toFile()?.mkdirs()
        socketPaths.eventPath.deleteIfExists()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPaths.eventPath))
        eventServer = server
        eventServerJob = scope.launch {
            while (isActive) {
                try {
                    server.accept().use { channel ->
                        eventChannelConnected = true
                        recomputeHealth()
                        while (isActive) {
                            val payload = PluginUdsFrameCodec.read(channel)
                            handleEventPayload(payload)
                        }
                    }
                } catch (error: Throwable) {
                    if (!isActive || process == null) {
                        break
                    }
                    logger.warn("Event channel interrupted pluginId=${descriptor.pluginId}: ${error.message}")
                } finally {
                    eventChannelConnected = false
                    recomputeHealth()
                }
            }
        }
    }

    private fun launchProcess() {
        onStateChange(PluginProcessState.STARTING)
        runtimeDir.toFile().mkdirs()
        socketPaths.invokePath.deleteIfExists()
        socketPaths.adminPath.deleteIfExists()
        val javaBinary = File(System.getProperty("java.home"), "bin/java").absolutePath
        val processBuilder = ProcessBuilder(
            javaBinary,
            "-cp",
            classpath,
            "com.keel.kernel.isolation.ExternalPluginHostMain",
            "--plugin-id=${descriptor.pluginId}",
            "--plugin-class=$pluginClassName",
            "--invoke-socket-path=${socketPaths.invokePath.pathString}",
            "--admin-socket-path=${socketPaths.adminPath.pathString}",
            "--event-socket-path=${socketPaths.eventPath.pathString}",
            "--auth-token=$authToken",
            "--generation=${generation.value}",
            "--config-path=${File(KeelConstants.CONFIG_PLUGINS_DIR, "${descriptor.pluginId}.json").absolutePath}",
            "--runtime-mode=${PluginRuntimeMode.EXTERNAL_JVM.name.lowercase()}"
        )
        process = processBuilder.start()
        captureOutput(process!!.inputStream, false)
        captureOutput(process!!.errorStream, true)
    }

    private suspend fun waitUntilReady() {
        val deadline = System.currentTimeMillis() + config.startupTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) {
                throw IllegalStateException("Isolated plugin process exited before UDS lanes became ready")
            }
            if (socketPaths.adminPath.exists() && readyEventReceived) {
                val handshake = runCatching {
                    sendAdminMessage(
                        HandshakeRequest(
                            pluginId = descriptor.pluginId,
                            generation = generation.value,
                            timestamp = System.currentTimeMillis(),
                            messageId = newMessageId(),
                            authToken = authToken,
                            runtimeMode = PluginRuntimeMode.EXTERNAL_JVM.name
                        ),
                        HandshakeResponse.serializer(),
                        config.startupTimeoutMs
                    )
                }.getOrNull()
                if (handshake != null && validateHandshakeResponse(handshake)) {
                    controlFailureCount = 0
                    recomputeHealth()
                    return
                }
            }
            delay(100)
        }
        throw IllegalStateException("Timed out waiting for isolated plugin ${descriptor.pluginId} handshake")
    }

    private fun startHealthChecks() {
        healthJob = scope.launch {
            while (isActive) {
                delay(config.healthCheckIntervalMs)
                if (process?.isAlive != true) {
                    onStateChange(PluginProcessState.FAILED)
                    onHealthChange(PluginHealthState.UNREACHABLE)
                    break
                }
                val startedAt = System.currentTimeMillis()
                val response = runCatching {
                    sendAdminMessage(
                        HealthRequest(
                            pluginId = descriptor.pluginId,
                            generation = generation.value,
                            timestamp = System.currentTimeMillis(),
                            messageId = newMessageId(),
                            authToken = authToken
                        ),
                        HealthResponse.serializer(),
                        config.healthCheckIntervalMs
                    )
                }.getOrNull()

                if (response == null) {
                    controlFailureCount += 1
                } else {
                    controlFailureCount = 0
                    lastHealthLatencyMs = System.currentTimeMillis() - startedAt
                    eventQueueDepth = response.eventQueueDepth
                    droppedLogCount = response.droppedLogCount
                }
                recomputeHealth()
            }
        }
    }

    private fun watchProcessExit() {
        processExitJob = scope.launch {
            val current = process ?: return@launch
            val exitCode = current.waitFor()
            logger.info("Observed isolated plugin exit pluginId=${descriptor.pluginId} exitCode=$exitCode")
            if (process === current) {
                eventChannelConnected = false
                controlFailureCount = 2
                if (exitCode != 0) {
                    recordFailure("process-exit", "External plugin process exited with code $exitCode")
                    onStateChange(PluginProcessState.FAILED)
                }
                onHealthChange(PluginHealthState.UNREACHABLE)
            }
        }
    }

    private fun stopProcess(force: Boolean) {
        healthJob?.cancel()
        processExitJob?.cancel()
        eventServerJob?.cancel()
        eventServer?.close()
        val current = process
        if (current != null) {
            if (force) {
                current.destroyForcibly()
            } else {
                current.destroy()
                if (!current.waitFor(config.stopTimeoutMs, TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly()
                }
            }
        }
        socketPaths.invokePath.deleteIfExists()
        socketPaths.adminPath.deleteIfExists()
        socketPaths.eventPath.deleteIfExists()
        process = null
        eventChannelConnected = false
        readyEventReceived = false
        controlFailureCount = 0
        eventQueueDepth = 0
    }

    private fun captureOutput(stream: java.io.InputStream, error: Boolean) {
        scope.launch {
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                lines.forEach { line ->
                    if (error) {
                        logger.error("[isolated:${descriptor.pluginId}] $line")
                    } else {
                        logger.info("[isolated:${descriptor.pluginId}] $line")
                    }
                }
            }
        }
    }

    private suspend fun <T> sendAdminMessage(
        message: Any,
        serializer: KSerializer<T>,
        timeoutMs: Long
    ): T {
        val startedAt = System.currentTimeMillis()
        val response = sendMessage(socketPaths.adminPath, message, serializer, timeoutMs)
        lastAdminLatencyMs = System.currentTimeMillis() - startedAt
        return response
    }

    private suspend fun <T> sendInvokeMessage(
        message: Any,
        serializer: KSerializer<T>,
        timeoutMs: Long
    ): T = sendMessage(socketPaths.invokePath, message, serializer, timeoutMs)

    private suspend fun <T> sendMessage(
        socketPath: Path,
        message: Any,
        serializer: KSerializer<T>,
        timeoutMs: Long
    ): T = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs.milliseconds) {
            val socketAddress = UnixDomainSocketAddress.of(socketPath)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(socketAddress)
                val payload = encodeMessage(message)
                PluginUdsFrameCodec.write(channel, payload)
                val responsePayload = PluginUdsFrameCodec.read(channel)
                PluginUdsJson.instance.decodeFromString(serializer, responsePayload)
            }
        }
    }

    private fun encodeMessage(message: Any): String = when (message) {
        is HandshakeRequest -> PluginUdsJson.instance.encodeToString(HandshakeRequest.serializer(), message)
        is HealthRequest -> PluginUdsJson.instance.encodeToString(HealthRequest.serializer(), message)
        is ShutdownRequest -> PluginUdsJson.instance.encodeToString(ShutdownRequest.serializer(), message)
        is ReloadPrepareRequest -> PluginUdsJson.instance.encodeToString(ReloadPrepareRequest.serializer(), message)
        is InvokeRequest -> PluginUdsJson.instance.encodeToString(InvokeRequest.serializer(), message)
        else -> error("Unsupported UDS message type: ${message::class.qualifiedName}")
    }

    private fun handleEventPayload(payload: String) {
        val json = PluginUdsJson.instance.parseToJsonElement(payload).jsonObject
        val kind = json["kind"]?.jsonPrimitive?.content.orEmpty()
        when (kind) {
            "plugin-ready-event" -> {
                val event = PluginUdsJson.instance.decodeFromString(PluginReadyEvent.serializer(), payload)
                if (validateEvent(event)) {
                    readyEventReceived = true
                    lastEventAtEpochMs = event.timestamp
                    recomputeHealth()
                }
            }
            "plugin-stopping-event" -> {
                val event = PluginUdsJson.instance.decodeFromString(PluginStoppingEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                }
            }
            "plugin-drain-complete-event" -> {
                val event = PluginUdsJson.instance.decodeFromString(PluginDrainCompleteEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                }
            }
            "plugin-disposed-event" -> {
                val event = PluginUdsJson.instance.decodeFromString(PluginDisposedEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                }
            }
            "plugin-failure-event" -> {
                val event = PluginUdsJson.instance.decodeFromString(PluginFailureEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                    recordFailure(event.errorType, event.errorMessage)
                    logger.error("Plugin failure event pluginId=${descriptor.pluginId} type=${event.errorType} message=${event.errorMessage}")
                    onStateChange(PluginProcessState.FAILED)
                    onHealthChange(PluginHealthState.UNREACHABLE)
                }
            }
            "plugin-log-event" -> {
                val event = PluginUdsJson.instance.decodeFromString(PluginLogEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                    droppedLogCount = event.droppedCount
                }
            }
            "plugin-trace-event" -> {
                val event = PluginUdsJson.instance.decodeFromString(PluginTraceEvent.serializer(), payload)
                if (validateEvent(event)) {
                    val durationMs = event.endEpochMs?.let { it - event.startEpochMs }
                    observabilityHub?.recordSpan(
                        TraceSpanEvent(
                            traceId = event.traceId,
                            spanId = event.spanId,
                            parentSpanId = event.parentSpanId,
                            service = event.service,
                            operation = event.name,
                            startEpochMs = event.startEpochMs,
                            endEpochMs = event.endEpochMs,
                            durationMs = durationMs,
                            status = event.status,
                            attributes = event.attributes,
                            edgeFrom = event.edgeFrom,
                            edgeTo = event.edgeTo
                        )
                    )
                }
            }
            "plugin-backpressure-event" -> {
                val event = PluginUdsJson.instance.decodeFromString(PluginBackpressureEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                    eventQueueDepth = event.eventQueueDepth
                    logger.warn("Plugin backpressure pluginId=${descriptor.pluginId} depth=${event.eventQueueDepth}")
                }
            }
            "plugin-event-queue-overflow-event" -> {
                val event = PluginUdsJson.instance.decodeFromString(PluginEventQueueOverflowEvent.serializer(), payload)
                if (validateEvent(event)) {
                    eventOverflowed = true
                    recordFailure("event-queue-overflow", "Plugin event queue overflowed (${event.queueType})")
                    logger.error("Plugin event queue overflow pluginId=${descriptor.pluginId} queue=${event.queueType} capacity=${event.capacity}")
                    onStateChange(PluginProcessState.FAILED)
                    onHealthChange(PluginHealthState.UNREACHABLE)
                }
            }
            else -> {
                logger.warn("Ignoring unknown event kind '$kind' for pluginId=${descriptor.pluginId}")
            }
        }
    }

    private fun validateEvent(event: PluginRuntimeEvent): Boolean {
        if (event.protocolVersion != PLUGIN_UDS_PROTOCOL_VERSION) {
            logger.warn("Discarding event with protocolVersion=${event.protocolVersion} pluginId=${descriptor.pluginId}")
            return false
        }
        if (event.pluginId != descriptor.pluginId || event.generation != generation.value) {
            logger.warn("Discarding mismatched event pluginId=${event.pluginId} generation=${event.generation}")
            return false
        }
        return true
    }

    private fun validateHandshakeResponse(response: HandshakeResponse): Boolean {
        if (!response.accepted) return false
        if (response.protocolVersion != PLUGIN_UDS_PROTOCOL_VERSION) return false
        if (response.pluginId != descriptor.pluginId) return false
        if (response.generation != generation.value) return false
        if (response.runtimeMode != PluginRuntimeMode.EXTERNAL_JVM.name) return false
        if (response.descriptorVersion != descriptor.version) return false

        val expectedInventory = expectedEndpoints.map {
            Triple(it.endpointId, it.method.value, it.path)
        }.toSet()
        val actualInventory = response.endpointInventory.map {
            Triple(it.endpointId, it.method, it.path)
        }.toSet()
        return expectedInventory == actualInventory
    }

    private fun recomputeHealth() {
        val processAlive = process?.isAlive == true
        val health = when {
            !processAlive || eventOverflowed -> PluginHealthState.UNREACHABLE
            controlFailureCount > 1 -> PluginHealthState.UNREACHABLE
            controlFailureCount == 1 -> PluginHealthState.DEGRADED
            !eventChannelConnected -> PluginHealthState.DEGRADED
            else -> PluginHealthState.HEALTHY
        }
        onHealthChange(health)
    }

    private fun adminChannelHealth(processAlive: Boolean?): PluginChannelHealth = when {
        processAlive != true -> PluginChannelHealth.UNREACHABLE
        controlFailureCount > 1 -> PluginChannelHealth.UNREACHABLE
        controlFailureCount == 1 -> PluginChannelHealth.DEGRADED
        else -> PluginChannelHealth.HEALTHY
    }

    private fun eventChannelHealth(processAlive: Boolean?): PluginChannelHealth = when {
        processAlive != true -> PluginChannelHealth.UNREACHABLE
        eventOverflowed -> PluginChannelHealth.UNREACHABLE
        !eventChannelConnected -> PluginChannelHealth.DEGRADED
        else -> PluginChannelHealth.HEALTHY
    }

    private fun recordFailure(source: String, message: String) {
        onFailure(
            PluginFailureRecord(
                timestamp = System.currentTimeMillis(),
                source = source,
                message = message
            )
        )
    }

    private fun unavailableResponse(call: ApplicationCall, message: String): InvokeResponse {
        return InvokeResponse(
            pluginId = descriptor.pluginId,
            generation = generation.value,
            timestamp = System.currentTimeMillis(),
            messageId = newMessageId(),
            correlationId = call.request.headers["X-Request-Id"] ?: newMessageId(),
            requestId = call.request.headers["X-Request-Id"] ?: newMessageId(),
            status = 503,
            errorMessage = message
        )
    }

    private fun oversizedResponse(call: ApplicationCall, message: String): InvokeResponse {
        return InvokeResponse(
            pluginId = descriptor.pluginId,
            generation = generation.value,
            timestamp = System.currentTimeMillis(),
            messageId = newMessageId(),
            correlationId = call.request.headers["X-Request-Id"] ?: newMessageId(),
            requestId = call.request.headers["X-Request-Id"] ?: newMessageId(),
            status = 413,
            errorMessage = message
        )
    }

    private fun newMessageId(): String = Uuid.random().toString()
}
