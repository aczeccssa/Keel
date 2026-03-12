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
import com.keel.kernel.plugin.JvmCommunicationMode
import com.keel.jvm.runtime.HandshakeRequest
import com.keel.jvm.runtime.HandshakeResponse
import com.keel.jvm.runtime.HealthRequest
import com.keel.jvm.runtime.HealthResponse
import com.keel.jvm.runtime.InvokeRequest
import com.keel.jvm.runtime.InvokeResponse
import com.keel.jvm.runtime.PLUGIN_JVM_PROTOCOL_VERSION
import com.keel.jvm.runtime.PluginBackpressureEvent
import com.keel.jvm.runtime.PluginDrainCompleteEvent
import com.keel.jvm.runtime.PluginEventQueueOverflowEvent
import com.keel.jvm.runtime.PluginFailureEvent
import com.keel.jvm.runtime.PluginLogEvent
import com.keel.jvm.runtime.PluginTraceEvent
import com.keel.jvm.runtime.PluginDisposedEvent
import com.keel.jvm.runtime.PluginRouteInventoryItem
import com.keel.jvm.runtime.PluginReadyEvent
import com.keel.jvm.runtime.PluginRuntimeEvent
import com.keel.jvm.runtime.PluginSseClosedEvent
import com.keel.jvm.runtime.PluginSseDataEvent
import com.keel.jvm.runtime.PluginStoppingEvent
import com.keel.jvm.runtime.PluginJvmFrameCodec
import com.keel.jvm.runtime.PluginJvmJson
import com.keel.jvm.runtime.ReloadPrepareRequest
import com.keel.jvm.runtime.ReloadPrepareResponse
import com.keel.jvm.runtime.SseCloseRequest
import com.keel.jvm.runtime.SseCloseResponse
import com.keel.jvm.runtime.SseOpenRequest
import com.keel.jvm.runtime.SseOpenResponse
import com.keel.jvm.runtime.StaticFetchRequest
import com.keel.jvm.runtime.StaticFetchResponse
import com.keel.jvm.runtime.ShutdownRequest
import com.keel.jvm.runtime.ShutdownResponse
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
import java.util.concurrent.ConcurrentHashMap
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

sealed interface PluginJvmConnectionInfo {
    val mode: JvmCommunicationMode
}

data class PluginJvmUdsConnectionInfo(
    val invokePath: Path,
    val adminPath: Path,
    val eventPath: Path
) : PluginJvmConnectionInfo {
    override val mode = JvmCommunicationMode.UDS
}

data class PluginJvmTcpConnectionInfo(
    val invokePort: Int,
    val adminPort: Int,
    val eventPort: Int
) : PluginJvmConnectionInfo {
    override val mode = JvmCommunicationMode.TCP
}

@OptIn(ExperimentalUuidApi::class)
class PluginProcessSupervisor(
    private val descriptor: PluginDescriptor,
    private val pluginClassName: String,
    private val config: PluginConfig,
    private val expectedRoutes: List<com.keel.kernel.plugin.PluginRouteDefinition>,
    private val classpath: String,
    private val runtimeDir: Path,
    private val generation: PluginGeneration,
    private val onStateChange: (PluginProcessState) -> Unit,
    private val onHealthChange: (PluginHealthState) -> Unit,
    private val onFailure: (PluginFailureRecord) -> Unit = {},
    private val onTerminalFailure: (reason: String, suggestTcpFallback: Boolean) -> Unit = { _, _ -> },
    private val forcedCommunicationMode: JvmCommunicationMode? = null,
    private val observabilityHub: ObservabilityHub? = null
) {
    private val logger = KeelLoggerService.getLogger("PluginProcessSupervisor")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authToken: String = Uuid.random().toString()

    private var currentConnectionInfo: PluginJvmConnectionInfo? = null
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
    private var activeCommunicationMode: JvmCommunicationMode? = null
    private var fallbackActivated: Boolean = false
    private var lastFallbackReason: String? = null
    private var stopping: Boolean = false
    private var terminalFailureReported: Boolean = false
    private val sseListeners = ConcurrentHashMap<String, SseStreamListener>()

    private data class SseStreamListener(
        val onData: (PluginSseDataEvent) -> Unit,
        val onClosed: () -> Unit
    )

    suspend fun start() {
        stopping = false
        terminalFailureReported = false
        var lastError: Throwable? = null
        val strategy = descriptor.communicationStrategy
        repeat(strategy.maxAttempts) { attempt ->
            try {
                val mode = forcedCommunicationMode ?: if (attempt == 0) strategy.preferredMode else strategy.fallbackMode
                currentConnectionInfo = createConnectionInfo(mode)
                activeCommunicationMode = mode
                fallbackActivated = mode != strategy.preferredMode
                if (fallbackActivated) {
                    lastFallbackReason = "startup-attempt-${attempt + 1}"
                }
                
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

    private fun createConnectionInfo(mode: JvmCommunicationMode): PluginJvmConnectionInfo = when (mode) {
        JvmCommunicationMode.UDS -> {
            val stem = "${descriptor.pluginId}-${generation.value}"
            PluginJvmUdsConnectionInfo(
                invokePath = runtimeDir.resolve("$stem-invoke.sock"),
                adminPath = runtimeDir.resolve("$stem-admin.sock"),
                eventPath = runtimeDir.resolve("$stem-event.sock")
            )
        }
        JvmCommunicationMode.TCP -> {
            val ports = NetworkUtils.allocateDistinctPorts(3)
            PluginJvmTcpConnectionInfo(
                invokePort = ports[0],
                adminPort = ports[1],
                eventPort = ports[2]
            )
        }
    }

    suspend fun stop() {
        stopping = true
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

    fun registerSseStreamListener(
        streamId: String,
        onData: (PluginSseDataEvent) -> Unit,
        onClosed: () -> Unit
    ) {
        sseListeners[streamId] = SseStreamListener(onData = onData, onClosed = onClosed)
    }

    fun unregisterSseStreamListener(streamId: String) {
        sseListeners.remove(streamId)
    }

    suspend fun fetchStaticResource(
        routePath: String,
        resourcePath: String,
        requestHeaders: Map<String, List<String>>
    ): StaticFetchResponse {
        val request = StaticFetchRequest(
            pluginId = descriptor.pluginId,
            generation = generation.value,
            timestamp = System.currentTimeMillis(),
            messageId = newMessageId(),
            authToken = authToken,
            routePath = routePath,
            resourcePath = resourcePath,
            requestHeaders = requestHeaders
        )
        return sendInvokeMessage(request, StaticFetchResponse.serializer(), config.callTimeoutMs)
    }

    suspend fun openSseStream(
        streamId: String,
        routePath: String,
        requestId: String,
        rawPath: String,
        pathParameters: Map<String, String>,
        queryParameters: Map<String, List<String>>,
        headers: Map<String, List<String>>
    ): SseOpenResponse {
        val request = SseOpenRequest(
            pluginId = descriptor.pluginId,
            generation = generation.value,
            timestamp = System.currentTimeMillis(),
            messageId = newMessageId(),
            authToken = authToken,
            streamId = streamId,
            routePath = routePath,
            requestId = requestId,
            rawPath = rawPath,
            pathParameters = pathParameters,
            queryParameters = queryParameters,
            headers = headers
        )
        return sendAdminMessage(request, SseOpenResponse.serializer(), config.callTimeoutMs)
    }

    suspend fun closeSseStream(streamId: String): SseCloseResponse {
        val request = SseCloseRequest(
            pluginId = descriptor.pluginId,
            generation = generation.value,
            timestamp = System.currentTimeMillis(),
            messageId = newMessageId(),
            authToken = authToken,
            streamId = streamId
        )
        return sendAdminMessage(request, SseCloseResponse.serializer(), config.callTimeoutMs)
    }

    fun diagnosticsSnapshot(): PluginRuntimeDiagnostics {
        val processAlive = process?.isAlive
        return PluginRuntimeDiagnostics(
            processAlive = processAlive,
            adminChannelHealth = adminChannelHealth(processAlive),
            eventChannelHealth = eventChannelHealth(processAlive),
            activeCommunicationMode = activeCommunicationMode,
            fallbackActivated = fallbackActivated,
            lastFallbackReason = lastFallbackReason,
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
            reportTerminalFailure(
                reason = "invoke-lane-unavailable:${error.message}",
                suggestTcpFallback = activeCommunicationMode == JvmCommunicationMode.UDS
            )
            return unavailableResponse(call, "Plugin '${descriptor.pluginId}' invoke channel is unavailable")
        }

        val responseBytes = response.bodyJson?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        if (maxPayloadBytes != null && responseBytes > maxPayloadBytes && !endpoint.executionPolicy.allowChunkedTransfer) {
            return oversizedResponse(call, "Response payload exceeds $maxPayloadBytes bytes")
        }
        return response
    }

    private enum class CommunicationLane {
        INVOKE, ADMIN, EVENT
    }

    private fun prepareEventServer() {
        val connection = currentConnectionInfo ?: return
        when (connection) {
            is PluginJvmUdsConnectionInfo -> {
                connection.eventPath.parent?.toFile()?.mkdirs()
                connection.eventPath.deleteIfExists()
                val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                server.bind(UnixDomainSocketAddress.of(connection.eventPath))
                eventServer = server
            }
            is PluginJvmTcpConnectionInfo -> {
                val server = ServerSocketChannel.open()
                server.bind(java.net.InetSocketAddress("127.0.0.1", connection.eventPort))
                eventServer = server
            }
        }
        
        val server = eventServer ?: return
        eventServerJob = scope.launch {
            while (isActive) {
                try {
                    server.accept()?.use { channel ->
                        eventChannelConnected = true
                        recomputeHealth()
                        while (isActive && channel.isOpen) {
                            val payload = PluginJvmFrameCodec.read(channel)
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
        val connection = currentConnectionInfo ?: return
        onStateChange(PluginProcessState.STARTING)
        runtimeDir.toFile().mkdirs()
        
        val javaBinary = File(System.getProperty("java.home"), "bin/java").absolutePath
        val baseArgs = mutableListOf(
            javaBinary,
            "-cp",
            classpath,
            "com.keel.kernel.isolation.ExternalPluginHostMain",
            "--plugin-id=${descriptor.pluginId}",
            "--plugin-class=$pluginClassName",
            "--auth-token=$authToken",
            "--generation=${generation.value}",
            "--config-path=${File(KeelConstants.CONFIG_PLUGINS_DIR, "${descriptor.pluginId}.json").absolutePath}",
            "--runtime-mode=${PluginRuntimeMode.EXTERNAL_JVM.name.lowercase()}",
            "--comm-mode=${connection.mode.name.lowercase()}"
        )

        when (connection) {
            is PluginJvmUdsConnectionInfo -> {
                connection.invokePath.deleteIfExists()
                connection.adminPath.deleteIfExists()
                baseArgs.add("--invoke-socket-path=${connection.invokePath.pathString}")
                baseArgs.add("--admin-socket-path=${connection.adminPath.pathString}")
                baseArgs.add("--event-socket-path=${connection.eventPath.pathString}")
            }
            is PluginJvmTcpConnectionInfo -> {
                baseArgs.add("--invoke-port=${connection.invokePort}")
                baseArgs.add("--admin-port=${connection.adminPort}")
                baseArgs.add("--event-port=${connection.eventPort}")
            }
        }

        val processBuilder = ProcessBuilder(baseArgs)
        process = processBuilder.start()
        captureOutput(process!!.inputStream, false)
        captureOutput(process!!.errorStream, true)
    }

    private suspend fun waitUntilReady() {
        val deadline = System.currentTimeMillis() + config.startupTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) {
                throw IllegalStateException("Isolated plugin process exited before JVM lanes became ready")
            }
            
            val connection = currentConnectionInfo ?: throw IllegalStateException("Connection info lost during startup")
            val lanesReady = when (connection) {
                is PluginJvmUdsConnectionInfo -> connection.adminPath.exists() && readyEventReceived
                is PluginJvmTcpConnectionInfo -> readyEventReceived // TCP ports are bound by kernel first, then sub-jvm connects back for event lane
            }

            if (lanesReady) {
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
                    if (controlFailureCount > 1) {
                        reportTerminalFailure(
                            reason = "admin-lane-unavailable",
                            suggestTcpFallback = activeCommunicationMode == JvmCommunicationMode.UDS
                        )
                    }
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
                if (!stopping) {
                    reportTerminalFailure(
                        reason = "process-exit:$exitCode",
                        suggestTcpFallback = activeCommunicationMode == JvmCommunicationMode.UDS
                    )
                }
            }
        }
    }

    private fun stopProcess(force: Boolean) {
        if (!force) {
            stopping = true
        }
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
        val connection = currentConnectionInfo
        if (connection is PluginJvmUdsConnectionInfo) {
            connection.invokePath.deleteIfExists()
            connection.adminPath.deleteIfExists()
            connection.eventPath.deleteIfExists()
        }
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
        val response = sendMessage(CommunicationLane.ADMIN, message, serializer, timeoutMs)
        lastAdminLatencyMs = System.currentTimeMillis() - startedAt
        return response
    }

    private suspend fun <T> sendInvokeMessage(
        message: Any,
        serializer: KSerializer<T>,
        timeoutMs: Long
    ): T = sendMessage(CommunicationLane.INVOKE, message, serializer, timeoutMs)

    private suspend fun <T> sendMessage(
        lane: CommunicationLane,
        message: Any,
        serializer: KSerializer<T>,
        timeoutMs: Long
    ): T = withContext(Dispatchers.IO) {
        val connection = currentConnectionInfo ?: throw IllegalStateException("Connection info unavailable")
        withTimeout(timeoutMs.milliseconds) {
            val channel = when (connection) {
                is PluginJvmUdsConnectionInfo -> {
                    val path = when (lane) {
                        CommunicationLane.INVOKE -> connection.invokePath
                        CommunicationLane.ADMIN -> connection.adminPath
                        CommunicationLane.EVENT -> connection.eventPath
                    }
                    val socketAddress = UnixDomainSocketAddress.of(path)
                    SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(socketAddress) }
                }
                is PluginJvmTcpConnectionInfo -> {
                    val port = when (lane) {
                        CommunicationLane.INVOKE -> connection.invokePort
                        CommunicationLane.ADMIN -> connection.adminPort
                        CommunicationLane.EVENT -> connection.eventPort
                    }
                    val socketAddress = java.net.InetSocketAddress("127.0.0.1", port)
                    SocketChannel.open().also { it.connect(socketAddress) }
                }
            }

            channel.use {
                val payload = encodeMessage(message)
                PluginJvmFrameCodec.write(it, payload)
                val responsePayload = PluginJvmFrameCodec.read(it)
                PluginJvmJson.instance.decodeFromString(serializer, responsePayload)
            }
        }
    }

    private fun encodeMessage(message: Any): String = when (message) {
        is HandshakeRequest -> PluginJvmJson.instance.encodeToString(HandshakeRequest.serializer(), message)
        is HealthRequest -> PluginJvmJson.instance.encodeToString(HealthRequest.serializer(), message)
        is ShutdownRequest -> PluginJvmJson.instance.encodeToString(ShutdownRequest.serializer(), message)
        is ReloadPrepareRequest -> PluginJvmJson.instance.encodeToString(ReloadPrepareRequest.serializer(), message)
        is InvokeRequest -> PluginJvmJson.instance.encodeToString(InvokeRequest.serializer(), message)
        is StaticFetchRequest -> PluginJvmJson.instance.encodeToString(StaticFetchRequest.serializer(), message)
        is SseOpenRequest -> PluginJvmJson.instance.encodeToString(SseOpenRequest.serializer(), message)
        is SseCloseRequest -> PluginJvmJson.instance.encodeToString(SseCloseRequest.serializer(), message)
        else -> error("Unsupported JVM protocol message type: ${message::class.qualifiedName}")
    }

    private fun handleEventPayload(payload: String) {
        val json = PluginJvmJson.instance.parseToJsonElement(payload).jsonObject
        val kind = json["kind"]?.jsonPrimitive?.content.orEmpty()
        when (kind) {
            "plugin-ready-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginReadyEvent.serializer(), payload)
                if (validateEvent(event)) {
                    readyEventReceived = true
                    lastEventAtEpochMs = event.timestamp
                    recomputeHealth()
                }
            }
            "plugin-stopping-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginStoppingEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                }
            }
            "plugin-drain-complete-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginDrainCompleteEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                }
            }
            "plugin-disposed-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginDisposedEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                }
            }
            "plugin-failure-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginFailureEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                    recordFailure(event.errorType, event.errorMessage)
                    logger.error("Plugin failure event pluginId=${descriptor.pluginId} type=${event.errorType} message=${event.errorMessage}")
                    onStateChange(PluginProcessState.FAILED)
                    onHealthChange(PluginHealthState.UNREACHABLE)
                }
            }
            "plugin-log-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginLogEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                    droppedLogCount = event.droppedCount
                }
            }
            "plugin-trace-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginTraceEvent.serializer(), payload)
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
                val event = PluginJvmJson.instance.decodeFromString(PluginBackpressureEvent.serializer(), payload)
                if (validateEvent(event)) {
                    lastEventAtEpochMs = event.timestamp
                    eventQueueDepth = event.eventQueueDepth
                    logger.warn("Plugin backpressure pluginId=${descriptor.pluginId} depth=${event.eventQueueDepth}")
                }
            }
            "plugin-event-queue-overflow-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginEventQueueOverflowEvent.serializer(), payload)
                if (validateEvent(event)) {
                    eventOverflowed = true
                    recordFailure("event-queue-overflow", "Plugin event queue overflowed (${event.queueType})")
                    logger.error("Plugin event queue overflow pluginId=${descriptor.pluginId} queue=${event.queueType} capacity=${event.capacity}")
                    onStateChange(PluginProcessState.FAILED)
                    onHealthChange(PluginHealthState.UNREACHABLE)
                }
            }
            "plugin-sse-data-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginSseDataEvent.serializer(), payload)
                if (validateEvent(event)) {
                    sseListeners[event.streamId]?.onData?.invoke(event)
                }
            }
            "plugin-sse-closed-event" -> {
                val event = PluginJvmJson.instance.decodeFromString(PluginSseClosedEvent.serializer(), payload)
                if (validateEvent(event)) {
                    sseListeners.remove(event.streamId)?.onClosed?.invoke()
                }
            }
            else -> {
                logger.warn("Ignoring unknown event kind '$kind' for pluginId=${descriptor.pluginId}")
            }
        }
    }

    private fun validateEvent(event: PluginRuntimeEvent): Boolean {
        if (event.protocolVersion != PLUGIN_JVM_PROTOCOL_VERSION) {
            logger.warn("Discarding event with protocolVersion=${event.protocolVersion} pluginId=${descriptor.pluginId}")
            return false
        }
        if (event.pluginId != descriptor.pluginId || event.generation != generation.value) {
            logger.warn("Discarding mismatched event pluginId=${event.pluginId} generation=${event.generation}")
            return false
        }
        if (event.authToken != authToken) {
            logger.warn("Discarding unauthenticated event pluginId=${event.pluginId} generation=${event.generation}")
            return false
        }
        return true
    }

    private fun validateHandshakeResponse(response: HandshakeResponse): Boolean {
        if (!response.accepted) return false
        if (response.protocolVersion != PLUGIN_JVM_PROTOCOL_VERSION) return false
        if (response.pluginId != descriptor.pluginId) return false
        if (response.generation != generation.value) return false
        if (response.runtimeMode != PluginRuntimeMode.EXTERNAL_JVM.name) return false
        if (response.descriptorVersion != descriptor.version) return false
        if (response.supportedServices.toSet() != descriptor.supportedServices.map { it.name }.toSet()) return false

        val expectedEndpointInventory = expectedRoutes
            .filterIsInstance<PluginEndpointDefinition<*, *>>()
            .map {
            Triple(it.endpointId, it.method.value, it.path)
        }.toSet()
        val actualEndpointInventory = response.endpointInventory.map {
            Triple(it.endpointId, it.method, it.path)
        }.toSet()
        if (expectedEndpointInventory != actualEndpointInventory) return false

        val expectedRouteInventory = expectedRoutes.map { route ->
            when (route) {
                is PluginEndpointDefinition<*, *> -> PluginRouteInventoryItem(
                    routeType = "ENDPOINT",
                    path = route.path,
                    method = route.method.value,
                    endpointId = route.endpointId
                )
                is com.keel.kernel.plugin.PluginSseDefinition -> PluginRouteInventoryItem(
                    routeType = "SSE",
                    path = route.path
                )
                is com.keel.kernel.plugin.PluginStaticResourceDefinition -> PluginRouteInventoryItem(
                    routeType = "STATIC_RESOURCE",
                    path = route.path
                )
            }
        }.toSet()
        val actualRouteInventory = response.routeInventory.toSet()
        return expectedRouteInventory == actualRouteInventory
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

    private fun reportTerminalFailure(reason: String, suggestTcpFallback: Boolean) {
        if (stopping || terminalFailureReported) return
        terminalFailureReported = true
        if (suggestTcpFallback) {
            fallbackActivated = true
            lastFallbackReason = reason
        }
        onTerminalFailure(reason, suggestTcpFallback)
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
