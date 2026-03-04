package com.keel.kernel.isolation

import com.keel.kernel.di.PluginScopeManager
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.observability.ObservabilityTracing
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginConfig
import com.keel.kernel.plugin.PluginConfigLoader
import com.keel.kernel.plugin.PluginHealthState
import com.keel.kernel.plugin.PluginRequestContext
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.decodeRequestBody
import com.keel.kernel.plugin.encodeResponseBody
import com.keel.kernel.plugin.serializer
import com.keel.uds.runtime.HandshakeRequest
import com.keel.uds.runtime.HandshakeResponse
import com.keel.uds.runtime.HealthRequest
import com.keel.uds.runtime.HealthResponse
import com.keel.uds.runtime.InvokeRequest
import com.keel.uds.runtime.InvokeResponse
import com.keel.uds.runtime.PluginDisposedEvent
import com.keel.uds.runtime.PluginDrainCompleteEvent
import com.keel.uds.runtime.PluginEndpointInventoryItem
import com.keel.uds.runtime.PluginEventQueueOverflowEvent
import com.keel.uds.runtime.PluginFailureEvent
import com.keel.uds.runtime.PluginLogEvent
import com.keel.uds.runtime.PluginReadyEvent
import com.keel.uds.runtime.PluginTraceEvent
import com.keel.uds.runtime.PluginRuntimeEvent
import com.keel.uds.runtime.PluginStoppingEvent
import com.keel.uds.runtime.PluginUdsFrameCodec
import com.keel.uds.runtime.PluginUdsJson
import com.keel.uds.runtime.ReloadPrepareRequest
import com.keel.uds.runtime.ReloadPrepareResponse
import com.keel.uds.runtime.ShutdownRequest
import com.keel.uds.runtime.ShutdownResponse
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.deleteIfExists
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

data class ExternalPluginHostArgs(
    val pluginClass: String,
    val invokeSocketPath: Path,
    val adminSocketPath: Path,
    val eventSocketPath: Path,
    val authToken: String,
    val generation: Long,
    val configPath: File?
)

fun parseExternalPluginHostArgs(args: Array<String>): ExternalPluginHostArgs {
    val parsedArgs = args.associate {
        val parts = it.removePrefix("--").split("=", limit = 2)
        parts[0] to parts.getOrElse(1) { "" }
    }
    return ExternalPluginHostArgs(
        pluginClass = requireNotNull(parsedArgs["plugin-class"]) { "Missing --plugin-class" },
        invokeSocketPath = Path.of(requireNotNull(parsedArgs["invoke-socket-path"]) { "Missing --invoke-socket-path" }),
        adminSocketPath = Path.of(requireNotNull(parsedArgs["admin-socket-path"]) { "Missing --admin-socket-path" }),
        eventSocketPath = Path.of(requireNotNull(parsedArgs["event-socket-path"]) { "Missing --event-socket-path" }),
        authToken = requireNotNull(parsedArgs["auth-token"]) { "Missing --auth-token" },
        generation = parsedArgs["generation"]?.toLongOrNull() ?: 1L,
        configPath = parsedArgs["config-path"]?.takeIf { it.isNotBlank() }?.let(::File)
    )
}

object ExternalPluginHostMain {
    private val logger = KeelLoggerService.getLogger("ExternalPluginHostMain")

    @JvmStatic
    fun main(args: Array<String>) {
        val hostArgs = parseExternalPluginHostArgs(args)
        val plugin = instantiatePlugin(hostArgs.pluginClass)
        val config = hostArgs.configPath?.let { PluginConfigLoader.load(plugin.descriptor, it) }
            ?: PluginConfigLoader.load(plugin.descriptor)
        val startedAt = System.currentTimeMillis()
        val endpoints = plugin.endpoints()
            .filterIsInstance<com.keel.kernel.plugin.PluginEndpointDefinition<*, *>>()
            .associateBy { it.endpointId }
        val running = AtomicBoolean(true)
        val inFlightInvokes = AtomicInteger(0)
        val lifecycleState = AtomicReferenceState("STARTING")

        val koin = startKoin {}.koin
        val pluginScopeManager = PluginScopeManager(koin)
        val privateScopeHandle = pluginScopeManager.createScope(
            pluginId = plugin.descriptor.pluginId,
            config = config,
            modules = plugin.modules()
        )
        val initContext = object : com.keel.kernel.plugin.PluginInitContext {
            override val pluginId: String = plugin.descriptor.pluginId
            override val config: PluginConfig = config
            override val kernelKoin = koin
        }
        val runtimeContext = object : com.keel.kernel.plugin.PluginRuntimeContext {
            override val pluginId: String = plugin.descriptor.pluginId
            override val config: PluginConfig = config
            override val kernelKoin = koin
            override val privateScope = privateScopeHandle.privateScope

            override fun registerTeardown(action: () -> Unit) {
                privateScopeHandle.teardownRegistry.register(action)
            }
        }

        val invokeDispatcher = Executors.newFixedThreadPool(config.maxConcurrentCalls.coerceAtMost(8).coerceAtLeast(2)).asCoroutineDispatcher()
        val adminDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val eventDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val eventEmitter = PluginEventEmitter(
            pluginId = plugin.descriptor.pluginId,
            generation = hostArgs.generation,
            config = config,
            socketPath = hostArgs.eventSocketPath,
            dispatcher = eventDispatcher
        )
        val tracing = ObservabilityTracing.initExternal(
            object : ObservabilityTracing.PluginTraceSink {
                override fun emitTrace(event: com.keel.kernel.observability.TraceSpanEvent) {
                    runBlocking {
                        eventEmitter.emitCritical(
                            PluginTraceEvent(
                                pluginId = plugin.descriptor.pluginId,
                                generation = hostArgs.generation,
                                timestamp = System.currentTimeMillis(),
                                messageId = eventEmitter.newMessageId(),
                                traceId = event.traceId,
                                spanId = event.spanId,
                                parentSpanId = event.parentSpanId,
                                service = "plugin:${plugin.descriptor.pluginId}",
                                name = event.operation,
                                startEpochMs = event.startEpochMs,
                                endEpochMs = event.endEpochMs,
                                status = event.status,
                                attributes = event.attributes,
                                edgeFrom = event.edgeFrom,
                                edgeTo = event.edgeTo
                            )
                        )
                    }
                }
            }
        )

        Files.createDirectories(hostArgs.invokeSocketPath.parent)
        Files.createDirectories(hostArgs.adminSocketPath.parent)
        hostArgs.invokeSocketPath.deleteIfExists()
        hostArgs.adminSocketPath.deleteIfExists()

        try {
            runBlocking {
                plugin.onInit(initContext)
                plugin.onStart(runtimeContext)
                eventEmitter.start()
                eventEmitter.emitCritical(
                    PluginReadyEvent(
                        pluginId = plugin.descriptor.pluginId,
                        generation = hostArgs.generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        runtimeMode = PluginRuntimeMode.EXTERNAL_JVM.name
                    )
                )
            }
            lifecycleState.set("RUNNING")

            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { adminServer ->
                adminServer.bind(UnixDomainSocketAddress.of(hostArgs.adminSocketPath))
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { invokeServer ->
                    invokeServer.bind(UnixDomainSocketAddress.of(hostArgs.invokeSocketPath))

                    val adminJob = scope.launch {
                        while (isActive && running.get()) {
                            try {
                                val channel = adminServer.accept()
                                launch(adminDispatcher) {
                                    channel.use {
                                        safeHandleAdminConnection(
                                            channel = it,
                                            plugin = plugin,
                                            config = config,
                                            authToken = hostArgs.authToken,
                                            generation = hostArgs.generation,
                                            startedAt = startedAt,
                                            lifecycleState = lifecycleState,
                                            running = running,
                                            inFlightInvokes = inFlightInvokes,
                                            adminServer = adminServer,
                                            invokeServer = invokeServer,
                                            eventEmitter = eventEmitter
                                        )
                                    }
                                }
                            } catch (_: Throwable) {
                                if (!running.get()) break
                            }
                        }
                    }

                    val invokeJob = scope.launch {
                        while (isActive && running.get()) {
                            try {
                                val channel = invokeServer.accept()
                                launch(invokeDispatcher) {
                                    channel.use {
                                        safeHandleInvokeConnection(
                                            channel = it,
                                            plugin = plugin,
                                            config = config,
                                            authToken = hostArgs.authToken,
                                            generation = hostArgs.generation,
                                            endpoints = endpoints,
                                            inFlightInvokes = inFlightInvokes,
                                            eventEmitter = eventEmitter,
                                            tracer = tracing.tracer
                                        )
                                    }
                                }
                            } catch (_: Throwable) {
                                if (!running.get()) break
                            }
                        }
                    }

                    runBlocking {
                        while (running.get()) {
                            delay(50)
                        }
                    }

                    runBlocking {
                        waitForDrain(inFlightInvokes, config.stopTimeoutMs)
                        eventEmitter.emitCritical(
                            PluginDrainCompleteEvent(
                                pluginId = plugin.descriptor.pluginId,
                                generation = hostArgs.generation,
                                timestamp = System.currentTimeMillis(),
                                messageId = eventEmitter.newMessageId(),
                                remainingInvokes = inFlightInvokes.get()
                            )
                        )
                        plugin.onStop(runtimeContext)
                        lifecycleState.set("DISPOSING")
                        plugin.onDispose(runtimeContext)
                        eventEmitter.emitCritical(
                            PluginDisposedEvent(
                                pluginId = plugin.descriptor.pluginId,
                                generation = hostArgs.generation,
                                timestamp = System.currentTimeMillis(),
                                messageId = eventEmitter.newMessageId()
                            )
                        )
                        eventEmitter.flush(config.stopTimeoutMs)
                    }
                    lifecycleState.set("STOPPED")
                    adminJob.cancel()
                    invokeJob.cancel()
                }
            }
        } catch (error: Throwable) {
            logger.error("External plugin host failed pluginId=${plugin.descriptor.pluginId}", error)
            runBlocking {
                eventEmitter.emitCritical(
                    PluginFailureEvent(
                        pluginId = plugin.descriptor.pluginId,
                        generation = hostArgs.generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        errorType = error::class.simpleName ?: "RuntimeException",
                        errorMessage = error.message ?: "Unknown failure"
                    )
                )
                eventEmitter.flush(config.stopTimeoutMs)
            }
            throw error
        } finally {
            runBlocking {
                eventEmitter.close()
            }
            hostArgs.invokeSocketPath.deleteIfExists()
            hostArgs.adminSocketPath.deleteIfExists()
            pluginScopeManager.closeScope(plugin.descriptor.pluginId)
            stopKoin()
            invokeDispatcher.close()
            adminDispatcher.close()
            eventDispatcher.close()
        }
    }

    private suspend fun safeHandleAdminConnection(
        channel: SocketChannel,
        plugin: KeelPlugin,
        config: PluginConfig,
        authToken: String,
        generation: Long,
        startedAt: Long,
        lifecycleState: AtomicReferenceState,
        running: AtomicBoolean,
        inFlightInvokes: AtomicInteger,
        adminServer: ServerSocketChannel,
        invokeServer: ServerSocketChannel,
        eventEmitter: PluginEventEmitter
    ) {
        runCatching {
            handleAdminConnection(
                channel = channel,
                plugin = plugin,
                config = config,
                authToken = authToken,
                generation = generation,
                startedAt = startedAt,
                lifecycleState = lifecycleState,
                running = running,
                inFlightInvokes = inFlightInvokes,
                adminServer = adminServer,
                invokeServer = invokeServer,
                eventEmitter = eventEmitter
            )
        }.onFailure { error ->
            logger.warn("Malformed or failed admin frame pluginId=${plugin.descriptor.pluginId}: ${error.message}")
            eventEmitter.emitCritical(
                PluginFailureEvent(
                    pluginId = plugin.descriptor.pluginId,
                    generation = generation,
                    timestamp = System.currentTimeMillis(),
                    messageId = eventEmitter.newMessageId(),
                    errorType = "MalformedAdminFrame",
                    errorMessage = error.message ?: "Malformed admin frame"
                )
            )
        }
    }

    private suspend fun safeHandleInvokeConnection(
        channel: SocketChannel,
        plugin: KeelPlugin,
        config: PluginConfig,
        authToken: String,
        generation: Long,
        endpoints: Map<String, com.keel.kernel.plugin.PluginEndpointDefinition<*, *>>,
        inFlightInvokes: AtomicInteger,
        eventEmitter: PluginEventEmitter,
        tracer: io.opentelemetry.api.trace.Tracer
    ) {
        runCatching {
            handleInvokeConnection(
                channel = channel,
                plugin = plugin,
                config = config,
                authToken = authToken,
                generation = generation,
                endpoints = endpoints,
                inFlightInvokes = inFlightInvokes,
                eventEmitter = eventEmitter,
                tracer = tracer
            )
        }.onFailure { error ->
            logger.warn("Malformed or failed invoke frame pluginId=${plugin.descriptor.pluginId}: ${error.message}")
            eventEmitter.emitCritical(
                PluginFailureEvent(
                    pluginId = plugin.descriptor.pluginId,
                    generation = generation,
                    timestamp = System.currentTimeMillis(),
                    messageId = eventEmitter.newMessageId(),
                    errorType = "MalformedInvokeFrame",
                    errorMessage = error.message ?: "Malformed invoke frame"
                )
            )
        }
    }

    private suspend fun handleAdminConnection(
        channel: SocketChannel,
        plugin: KeelPlugin,
        config: PluginConfig,
        authToken: String,
        generation: Long,
        startedAt: Long,
        lifecycleState: AtomicReferenceState,
        running: AtomicBoolean,
        inFlightInvokes: AtomicInteger,
        adminServer: ServerSocketChannel,
        invokeServer: ServerSocketChannel,
        eventEmitter: PluginEventEmitter
    ) {
        val payload = PluginUdsFrameCodec.read(channel)
        val json = PluginUdsJson.instance.parseToJsonElement(payload).jsonObject
        val kind = json["kind"]?.jsonPrimitive?.content.orEmpty()
        val response = when (kind) {
            "handshake-request" -> {
                val request = PluginUdsJson.instance.decodeFromString(HandshakeRequest.serializer(), payload)
                val accepted = validateControlRequest(request.pluginId, request.generation, request.authToken, plugin.descriptor.pluginId, generation, authToken)
                PluginUdsJson.instance.encodeToString(
                    HandshakeResponse.serializer(),
                    HandshakeResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        descriptorVersion = plugin.descriptor.version,
                        runtimeMode = PluginRuntimeMode.EXTERNAL_JVM.name,
                        endpointInventory = plugin.endpoints()
                            .filterIsInstance<com.keel.kernel.plugin.PluginEndpointDefinition<*, *>>()
                            .map {
                            PluginEndpointInventoryItem(it.endpointId, it.method.value, it.path)
                        },
                        accepted = accepted,
                        reason = if (accepted) null else "Handshake rejected"
                    )
                )
            }
            "health-request" -> {
                val request = PluginUdsJson.instance.decodeFromString(HealthRequest.serializer(), payload)
                val accepted = validateControlRequest(request.pluginId, request.generation, request.authToken, plugin.descriptor.pluginId, generation, authToken)
                PluginUdsJson.instance.encodeToString(
                    HealthResponse.serializer(),
                    HealthResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        lifecycleState = lifecycleState.get(),
                        healthState = if (accepted && eventEmitter.connected()) PluginHealthState.HEALTHY.name else PluginHealthState.DEGRADED.name,
                        startedAtEpochMs = startedAt,
                        eventQueueDepth = eventEmitter.queueDepth(),
                        droppedLogCount = eventEmitter.droppedLogCount()
                    )
                )
            }
            "shutdown-request" -> {
                val request = PluginUdsJson.instance.decodeFromString(ShutdownRequest.serializer(), payload)
                val accepted = validateControlRequest(request.pluginId, request.generation, request.authToken, plugin.descriptor.pluginId, generation, authToken)
                if (accepted) {
                    lifecycleState.set("STOPPING")
                    running.set(false)
                    eventEmitter.emitCritical(
                        PluginStoppingEvent(
                            pluginId = plugin.descriptor.pluginId,
                            generation = generation,
                            timestamp = System.currentTimeMillis(),
                            messageId = eventEmitter.newMessageId(),
                            reason = request.reason
                        )
                    )
                    invokeServer.close()
                    adminServer.close()
                }
                PluginUdsJson.instance.encodeToString(
                    ShutdownResponse.serializer(),
                    ShutdownResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        accepted = accepted,
                        inflightInvokes = inFlightInvokes.get()
                    )
                )
            }
            "reload-prepare-request" -> {
                val request = PluginUdsJson.instance.decodeFromString(ReloadPrepareRequest.serializer(), payload)
                val accepted = validateControlRequest(request.pluginId, request.generation, request.authToken, plugin.descriptor.pluginId, generation, authToken) &&
                    inFlightInvokes.get() == 0
                PluginUdsJson.instance.encodeToString(
                    ReloadPrepareResponse.serializer(),
                    ReloadPrepareResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        accepted = accepted
                    )
                )
            }
            else -> error("Unsupported admin message kind: $kind")
        }
        PluginUdsFrameCodec.write(channel, response)
    }

    private suspend fun handleInvokeConnection(
        channel: SocketChannel,
        plugin: KeelPlugin,
        config: PluginConfig,
        authToken: String,
        generation: Long,
        endpoints: Map<String, com.keel.kernel.plugin.PluginEndpointDefinition<*, *>>,
        inFlightInvokes: AtomicInteger,
        eventEmitter: PluginEventEmitter,
        tracer: io.opentelemetry.api.trace.Tracer
    ) {
        val payload = PluginUdsFrameCodec.read(channel)
        val request = PluginUdsJson.instance.decodeFromString(InvokeRequest.serializer(), payload)
        if (!validateControlRequest(request.pluginId, request.generation, request.authToken, plugin.descriptor.pluginId, generation, authToken)) {
            PluginUdsFrameCodec.write(
                channel,
                PluginUdsJson.instance.encodeToString(
                    InvokeResponse.serializer(),
                    InvokeResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        requestId = request.requestId,
                        status = 403,
                        errorMessage = "Invalid auth token"
                    )
                )
            )
            return
        }

        val endpoint = endpoints[request.endpointId]
        if (endpoint == null) {
            PluginUdsFrameCodec.write(
                channel,
                PluginUdsJson.instance.encodeToString(
                    InvokeResponse.serializer(),
                    InvokeResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        requestId = request.requestId,
                        status = 404,
                        errorMessage = "Endpoint not found"
                    )
                )
            )
            return
        }

        val payloadLimit = endpoint.executionPolicy.maxPayloadBytes
        val requestBytes = request.bodyJson?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        if (payloadLimit != null && requestBytes > payloadLimit && !endpoint.executionPolicy.allowChunkedTransfer) {
            PluginUdsFrameCodec.write(
                channel,
                PluginUdsJson.instance.encodeToString(
                    InvokeResponse.serializer(),
                    InvokeResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        requestId = request.requestId,
                        status = 413,
                        errorMessage = "Payload too large"
                    )
                )
            )
            return
        }

        inFlightInvokes.incrementAndGet()
        var scope: io.opentelemetry.context.Scope? = null
        var span: io.opentelemetry.api.trace.Span? = null
        try {
            val parentContext = ObservabilityTracing.extract(request.traceparent, request.tracestate)
            span = tracer.spanBuilder("plugin.handle")
                .setParent(parentContext)
                .setAttribute("keel.pluginId", plugin.descriptor.pluginId)
                .setAttribute("keel.jvm", "plugin")
                .setAttribute("keel.edge.from", "kernel")
                .setAttribute("keel.edge.to", plugin.descriptor.pluginId)
                .startSpan()
            scope = span?.makeCurrent()
            val requestBody = decodeRequestBody(request.bodyJson, endpoint.requestType)
            val context = object : PluginRequestContext {
                override val pluginId: String = request.pluginId
                override val method: String = request.method
                override val rawPath: String = request.rawPath
                override val pathParameters: Map<String, String> = request.pathParameters
                override val queryParameters: Map<String, List<String>> = request.queryParameters
                override val requestHeaders: Map<String, List<String>> = request.headers
                override val requestId: String = request.requestId
            }

            val timeoutMs = endpoint.executionPolicy.timeoutMs ?: config.callTimeoutMs
            val result = withTimeoutOrNull(timeoutMs.milliseconds) {
                endpoint.execute(context, requestBody)
            } ?: run {
                PluginUdsFrameCodec.write(
                    channel,
                    PluginUdsJson.instance.encodeToString(
                        InvokeResponse.serializer(),
                        InvokeResponse(
                            pluginId = plugin.descriptor.pluginId,
                            generation = generation,
                            timestamp = System.currentTimeMillis(),
                            messageId = eventEmitter.newMessageId(),
                            correlationId = request.messageId,
                            requestId = request.requestId,
                            status = 504,
                            errorMessage = "Invoke timed out after ${timeoutMs}ms"
                        )
                    )
                )
                return
            }

            val encodedBody = encodeResponseBody(result.body, endpoint.responseType)
            val responseBytes = encodedBody?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
            if (payloadLimit != null && responseBytes > payloadLimit && !endpoint.executionPolicy.allowChunkedTransfer) {
                PluginUdsFrameCodec.write(
                    channel,
                    PluginUdsJson.instance.encodeToString(
                        InvokeResponse.serializer(),
                        InvokeResponse(
                            pluginId = plugin.descriptor.pluginId,
                            generation = generation,
                            timestamp = System.currentTimeMillis(),
                            messageId = eventEmitter.newMessageId(),
                            correlationId = request.messageId,
                            requestId = request.requestId,
                            status = 413,
                            errorMessage = "Response payload too large"
                        )
                    )
                )
                return
            }

            PluginUdsFrameCodec.write(
                channel,
                PluginUdsJson.instance.encodeToString(
                    InvokeResponse.serializer(),
                    InvokeResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        requestId = request.requestId,
                        status = result.status,
                        headers = result.headers,
                        bodyJson = encodedBody
                    )
                )
            )
        } catch (error: PluginApiException) {
            PluginUdsFrameCodec.write(
                channel,
                PluginUdsJson.instance.encodeToString(
                    InvokeResponse.serializer(),
                    InvokeResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        requestId = request.requestId,
                        status = error.status,
                        errorMessage = error.message
                    )
                )
            )
        } catch (error: Throwable) {
            eventEmitter.emitCritical(
                PluginFailureEvent(
                    pluginId = plugin.descriptor.pluginId,
                    generation = generation,
                    timestamp = System.currentTimeMillis(),
                    messageId = eventEmitter.newMessageId(),
                    errorType = error::class.simpleName ?: "RuntimeException",
                    errorMessage = error.message ?: "Internal server error"
                )
            )
            PluginUdsFrameCodec.write(
                channel,
                PluginUdsJson.instance.encodeToString(
                    InvokeResponse.serializer(),
                    InvokeResponse(
                        pluginId = plugin.descriptor.pluginId,
                        generation = generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        correlationId = request.messageId,
                        requestId = request.requestId,
                        status = 500,
                        errorMessage = error.message ?: "Internal server error"
                    )
                )
            )
        } finally {
            scope?.close()
            span?.end()
            inFlightInvokes.decrementAndGet()
        }
    }

    private suspend fun waitForDrain(inFlightInvokes: AtomicInteger, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (inFlightInvokes.get() > 0 && System.currentTimeMillis() < deadline) {
            delay(50)
        }
    }

    private fun validateControlRequest(
        requestPluginId: String,
        requestGeneration: Long,
        requestAuthToken: String,
        pluginId: String,
        generation: Long,
        authToken: String
    ): Boolean {
        return requestPluginId == pluginId && requestGeneration == generation && requestAuthToken == authToken
    }

    private fun instantiatePlugin(pluginClass: String): KeelPlugin {
        val instance = Class.forName(pluginClass).getDeclaredConstructor().newInstance()
        return instance as? KeelPlugin
            ?: error("Class $pluginClass does not implement KeelPlugin")
    }
}

private class AtomicReferenceState(initial: String) {
    @Volatile
    private var value: String = initial

    fun get(): String = value

    fun set(next: String) {
        value = next
    }
}

private class PluginEventEmitter(
    private val pluginId: String,
    private val generation: Long,
    private val config: PluginConfig,
    private val socketPath: Path,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher
) {
    private val logger = KeelLoggerService.getLogger("PluginEventEmitter")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val criticalEvents = kotlinx.coroutines.channels.Channel<PluginRuntimeEvent>(capacity = config.criticalEventQueueSize)
    private val criticalDepth = AtomicInteger(0)
    private val droppedLogs = AtomicLong(0)
    private val logBuffer = ArrayDeque<PluginLogEvent>()
    private val overflowPending = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var senderJob: kotlinx.coroutines.Job? = null

    suspend fun start() {
        senderJob = scope.launch {
            val address = UnixDomainSocketAddress.of(socketPath)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                try {
                    channel.connect(address)
                    connected.set(true)
                    while (isActive) {
                        val overflowEvent = if (overflowPending.getAndSet(false)) {
                            PluginEventQueueOverflowEvent(
                                pluginId = pluginId,
                                generation = generation,
                                timestamp = System.currentTimeMillis(),
                                messageId = newMessageId(),
                                queueType = "critical",
                                capacity = config.criticalEventQueueSize
                            )
                        } else {
                            null
                        }
                        val event = overflowEvent ?: criticalEvents.tryReceive().getOrNull()?.also {
                            criticalDepth.decrementAndGet()
                        } ?: synchronized(logBuffer) {
                            if (logBuffer.isEmpty()) null else logBuffer.removeFirst()
                        }
                        if (event == null) {
                            delay(25)
                            continue
                        }
                        PluginUdsFrameCodec.write(channel, encodeEvent(event))
                    }
                } finally {
                    connected.set(false)
                }
            }
        }
    }

    suspend fun emitCritical(event: PluginRuntimeEvent) {
        criticalDepth.incrementAndGet()
        if (criticalEvents.trySend(event).isFailure) {
            criticalDepth.decrementAndGet()
            overflowPending.set(true)
        }
    }

    fun emitLog(level: String, message: String) {
        synchronized(logBuffer) {
            if (logBuffer.size >= config.eventLogRingBufferSize) {
                logBuffer.removeFirst()
                droppedLogs.incrementAndGet()
            }
            logBuffer.addLast(
                PluginLogEvent(
                    pluginId = pluginId,
                    generation = generation,
                    timestamp = System.currentTimeMillis(),
                    messageId = newMessageId(),
                    level = level,
                    message = message,
                    droppedCount = droppedLogs.get()
                )
            )
        }
    }

    suspend fun flush(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (queueDepth() > 0 && System.currentTimeMillis() < deadline) {
            delay(25)
        }
    }

    suspend fun close() {
        flush(config.stopTimeoutMs)
        senderJob?.cancel()
        criticalEvents.close()
    }

    fun queueDepth(): Int = criticalDepth.get() + synchronized(logBuffer) { logBuffer.size }

    fun droppedLogCount(): Long = droppedLogs.get()

    fun connected(): Boolean = connected.get()

    fun newMessageId(): String = java.util.UUID.randomUUID().toString()

    private fun encodeEvent(event: PluginRuntimeEvent): String = when (event) {
        is PluginReadyEvent -> PluginUdsJson.instance.encodeToString(PluginReadyEvent.serializer(), event)
        is PluginStoppingEvent -> PluginUdsJson.instance.encodeToString(PluginStoppingEvent.serializer(), event)
        is PluginDrainCompleteEvent -> PluginUdsJson.instance.encodeToString(PluginDrainCompleteEvent.serializer(), event)
        is PluginDisposedEvent -> PluginUdsJson.instance.encodeToString(PluginDisposedEvent.serializer(), event)
        is PluginFailureEvent -> PluginUdsJson.instance.encodeToString(PluginFailureEvent.serializer(), event)
        is PluginLogEvent -> PluginUdsJson.instance.encodeToString(PluginLogEvent.serializer(), event)
        is PluginEventQueueOverflowEvent -> PluginUdsJson.instance.encodeToString(PluginEventQueueOverflowEvent.serializer(), event)
        is PluginTraceEvent -> PluginUdsJson.instance.encodeToString(PluginTraceEvent.serializer(), event)
        else -> {
            logger.warn("Unsupported runtime event type ${event::class.qualifiedName}")
            PluginUdsJson.instance.encodeToString(
                PluginFailureEvent.serializer(),
                PluginFailureEvent(
                    pluginId = pluginId,
                    generation = generation,
                    timestamp = System.currentTimeMillis(),
                    messageId = newMessageId(),
                    errorType = "UnsupportedEvent",
                    errorMessage = "Unsupported runtime event ${event::class.qualifiedName}"
                )
            )
        }
    }
}
