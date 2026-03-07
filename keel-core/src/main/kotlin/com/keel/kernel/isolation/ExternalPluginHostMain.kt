package com.keel.kernel.isolation

import com.keel.kernel.di.PluginScopeManager
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.observability.ObservabilityTracing
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginConfig
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.toConfig
import com.keel.kernel.plugin.PluginHealthState
import com.keel.kernel.plugin.PluginRequestContext
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.decodeRequestBody
import com.keel.kernel.plugin.encodeResponseBody
import com.keel.kernel.plugin.serializer
import com.keel.jvm.runtime.HandshakeRequest
import com.keel.jvm.runtime.HandshakeResponse
import com.keel.jvm.runtime.HealthRequest
import com.keel.jvm.runtime.HealthResponse
import com.keel.jvm.runtime.InvokeRequest
import com.keel.jvm.runtime.InvokeResponse
import com.keel.jvm.runtime.PluginDisposedEvent
import com.keel.jvm.runtime.PluginDrainCompleteEvent
import com.keel.jvm.runtime.PluginEndpointInventoryItem
import com.keel.jvm.runtime.PluginEventQueueOverflowEvent
import com.keel.jvm.runtime.PluginFailureEvent
import com.keel.jvm.runtime.PluginLogEvent
import com.keel.jvm.runtime.PluginReadyEvent
import com.keel.jvm.runtime.PluginTraceEvent
import com.keel.jvm.runtime.PluginRuntimeEvent
import com.keel.jvm.runtime.PluginStoppingEvent
import com.keel.jvm.runtime.PluginJvmFrameCodec
import com.keel.jvm.runtime.PluginJvmJson
import com.keel.jvm.runtime.ReloadPrepareRequest
import com.keel.jvm.runtime.ReloadPrepareResponse
import com.keel.jvm.runtime.ShutdownRequest
import com.keel.jvm.runtime.ShutdownResponse
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

import com.keel.kernel.plugin.JvmCommunicationMode

data class ExternalPluginHostArgs(
    val pluginClass: String,
    val commMode: JvmCommunicationMode,
    val invokeSocketPath: Path? = null,
    val adminSocketPath: Path? = null,
    val eventSocketPath: Path? = null,
    val invokePort: Int? = null,
    val adminPort: Int? = null,
    val eventPort: Int? = null,
    val authToken: String,
    val generation: Long,
    val configPath: File?
)

fun parseExternalPluginHostArgs(args: Array<String>): ExternalPluginHostArgs {
    val parsedArgs = args.associate {
        val parts = it.removePrefix("--").split("=", limit = 2)
        parts[0] to parts.getOrElse(1) { "" }
    }
    val commMode = parsedArgs["comm-mode"]?.let { JvmCommunicationMode.valueOf(it.uppercase()) } 
        ?: JvmCommunicationMode.UDS

    return ExternalPluginHostArgs(
        pluginClass = requireNotNull(parsedArgs["plugin-class"]) { "Missing --plugin-class" },
        commMode = commMode,
        invokeSocketPath = parsedArgs["invoke-socket-path"]?.let { Path.of(it) },
        adminSocketPath = parsedArgs["admin-socket-path"]?.let { Path.of(it) },
        eventSocketPath = parsedArgs["event-socket-path"]?.let { Path.of(it) },
        invokePort = parsedArgs["invoke-port"]?.toIntOrNull(),
        adminPort = parsedArgs["admin-port"]?.toIntOrNull(),
        eventPort = parsedArgs["event-port"]?.toIntOrNull(),
        authToken = requireNotNull(parsedArgs["auth-token"]) { "Missing --auth-token" },
        generation = parsedArgs["generation"]?.toLongOrNull() ?: 1L,
        configPath = parsedArgs["config-path"]?.takeIf { it.isNotBlank() }?.let(::File)
    )
}

object ExternalPluginHostMain {
    private val logger = KeelLoggerService.getLogger("ExternalPluginHostMain")
    private const val MAX_INVOKE_THREADS = 8
    private const val MIN_INVOKE_THREADS = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val hostArgs = parseExternalPluginHostArgs(args)
        ExternalPluginHost(hostArgs).run()
    }

    private class ExternalPluginHost(
        private val hostArgs: ExternalPluginHostArgs
    ) {
        private val plugin = instantiatePlugin(hostArgs.pluginClass)
        private val startedAt = System.currentTimeMillis()
        private val endpoints = plugin.endpoints()
            .filterIsInstance<com.keel.kernel.plugin.PluginEndpointDefinition<*, *>>()
            .associateBy { it.endpointId }
        private val running = AtomicBoolean(true)
        private val inFlightInvokes = AtomicInteger(0)
        private val lifecycleState = AtomicReferenceState("STARTING")

        private val koin = startKoin {}.koin
        private val pluginScopeManager = PluginScopeManager(koin)
        private val privateScopeHandle = pluginScopeManager.createScope(
            pluginId = plugin.descriptor.pluginId,
            config = plugin.descriptor.toConfig(),
            modules = plugin.modules()
        )
        private val initContext: com.keel.kernel.plugin.PluginInitContext
        private val runtimeContext: com.keel.kernel.plugin.PluginRuntimeContext

        private val invokeDispatcher = Executors.newFixedThreadPool(
            plugin.descriptor.maxConcurrentCalls.coerceAtMost(MAX_INVOKE_THREADS).coerceAtLeast(MIN_INVOKE_THREADS)
        ).asCoroutineDispatcher()
        private val adminDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val eventDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val eventEmitter = PluginEventEmitter(
            pluginId = plugin.descriptor.pluginId,
            generation = hostArgs.generation,
            authToken = hostArgs.authToken,
            descriptor = plugin.descriptor,
            commMode = hostArgs.commMode,
            socketPath = hostArgs.eventSocketPath,
            port = hostArgs.eventPort,
            dispatcher = eventDispatcher
        )
        private val tracing = ObservabilityTracing.initExternal(
            object : ObservabilityTracing.PluginTraceSink {
                override fun emitTrace(event: com.keel.kernel.observability.TraceSpanEvent) {
                    runBlocking {
                        eventEmitter.emitCritical(
                            PluginTraceEvent(
                                pluginId = plugin.descriptor.pluginId,
                                generation = hostArgs.generation,
                                authToken = hostArgs.authToken,
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

        init {
            val descriptor = plugin.descriptor
            initContext = object : com.keel.kernel.plugin.PluginInitContext {
                override val pluginId: String = descriptor.pluginId
                override val descriptor: PluginDescriptor = descriptor
                override val kernelKoin = koin
            }
            runtimeContext = object : com.keel.kernel.plugin.PluginRuntimeContext {
                override val pluginId: String = descriptor.pluginId
                override val descriptor: PluginDescriptor = descriptor
                override val kernelKoin = koin
                override val privateScope = privateScopeHandle.privateScope

                override fun registerTeardown(action: () -> Unit) {
                    privateScopeHandle.teardownRegistry.register(action)
                }
            }
        }

        fun run() {
            prepareSockets()
            try {
                startPlugin()
                serveConnections()
            } catch (error: Throwable) {
                recordFailure(error)
                throw error
            } finally {
                shutdown()
            }
        }

        private fun createServerSocket(mode: JvmCommunicationMode, socketPath: Path?, port: Int?): ServerSocketChannel {
            return when (mode) {
                JvmCommunicationMode.UDS -> {
                    ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
                        it.bind(UnixDomainSocketAddress.of(requireNotNull(socketPath)))
                    }
                }
                JvmCommunicationMode.TCP -> {
                    ServerSocketChannel.open().also {
                        it.bind(java.net.InetSocketAddress("127.0.0.1", requireNotNull(port)))
                    }
                }
            }
        }

        private fun prepareSockets() {
            if (hostArgs.commMode == JvmCommunicationMode.UDS) {
                Files.createDirectories(requireNotNull(hostArgs.invokeSocketPath).parent)
                Files.createDirectories(requireNotNull(hostArgs.adminSocketPath).parent)
                hostArgs.invokeSocketPath.deleteIfExists()
                hostArgs.adminSocketPath.deleteIfExists()
            }
        }

        private fun startPlugin() {
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
                        authToken = hostArgs.authToken,
                        runtimeMode = PluginRuntimeMode.EXTERNAL_JVM.name
                    )
                )
            }
            lifecycleState.set("RUNNING")
        }

        private fun serveConnections() {
            val adminServer = createServerSocket(hostArgs.commMode, hostArgs.adminSocketPath, hostArgs.adminPort)
            val invokeServer = createServerSocket(hostArgs.commMode, hostArgs.invokeSocketPath, hostArgs.invokePort)

            adminServer.use { _ ->
                invokeServer.use { _ ->
                    val adminJob = scope.launch {
                        while (isActive && running.get()) {
                            try {
                                val channel = adminServer.accept()
                                launch(adminDispatcher) {
                                    channel.use {
                                        safeHandleAdminConnection(
                                            channel = it,
                                            plugin = plugin,
                                            descriptor = plugin.descriptor,
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
                                            descriptor = plugin.descriptor,
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

                    stopRuntime(adminJob, invokeJob)
                }
            }
        }

        private fun stopRuntime(adminJob: Job, invokeJob: Job) {
            runBlocking {
                waitForDrain(inFlightInvokes, plugin.descriptor.stopTimeoutMs)
                eventEmitter.emitCritical(
                    PluginDrainCompleteEvent(
                        pluginId = plugin.descriptor.pluginId,
                        generation = hostArgs.generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        authToken = hostArgs.authToken,
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
                        messageId = eventEmitter.newMessageId(),
                        authToken = hostArgs.authToken
                    )
                )
                eventEmitter.flush(plugin.descriptor.stopTimeoutMs)
            }
            lifecycleState.set("STOPPED")
            adminJob.cancel()
            invokeJob.cancel()
        }

        private fun recordFailure(error: Throwable) {
            logger.error("External plugin host failed pluginId=${plugin.descriptor.pluginId}", error)
            runBlocking {
                eventEmitter.emitCritical(
                    PluginFailureEvent(
                        pluginId = plugin.descriptor.pluginId,
                        generation = hostArgs.generation,
                        timestamp = System.currentTimeMillis(),
                        messageId = eventEmitter.newMessageId(),
                        authToken = hostArgs.authToken,
                        errorType = error::class.simpleName ?: "RuntimeException",
                        errorMessage = error.message ?: "Unknown failure"
                    )
                )
                eventEmitter.flush(plugin.descriptor.stopTimeoutMs)
            }
        }

        private fun shutdown() {
            runBlocking {
                eventEmitter.close()
            }
            if (hostArgs.commMode == JvmCommunicationMode.UDS) {
                hostArgs.invokeSocketPath?.deleteIfExists()
                hostArgs.adminSocketPath?.deleteIfExists()
            }
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
        descriptor: PluginDescriptor,
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
                descriptor = plugin.descriptor,
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
                    authToken = authToken,
                    errorType = "MalformedAdminFrame",
                    errorMessage = error.message ?: "Malformed admin frame"
                )
            )
        }
    }

    private suspend fun safeHandleInvokeConnection(
        channel: SocketChannel,
        plugin: KeelPlugin,
        descriptor: PluginDescriptor,
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
                descriptor = plugin.descriptor,
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
                    authToken = authToken,
                    errorType = "MalformedInvokeFrame",
                    errorMessage = error.message ?: "Malformed invoke frame"
                )
            )
        }
    }

    private suspend fun handleAdminConnection(
        channel: SocketChannel,
        plugin: KeelPlugin,
        descriptor: PluginDescriptor,
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
        val payload = PluginJvmFrameCodec.read(channel)
        val json = PluginJvmJson.instance.parseToJsonElement(payload).jsonObject
        val kind = json["kind"]?.jsonPrimitive?.content.orEmpty()
        val response = when (kind) {
            "handshake-request" -> {
                val request = PluginJvmJson.instance.decodeFromString(HandshakeRequest.serializer(), payload)
                val accepted = validateControlRequest(request.pluginId, request.generation, request.authToken, plugin.descriptor.pluginId, generation, authToken)
                PluginJvmJson.instance.encodeToString(
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
                val request = PluginJvmJson.instance.decodeFromString(HealthRequest.serializer(), payload)
                val accepted = validateControlRequest(request.pluginId, request.generation, request.authToken, plugin.descriptor.pluginId, generation, authToken)
                PluginJvmJson.instance.encodeToString(
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
                val request = PluginJvmJson.instance.decodeFromString(ShutdownRequest.serializer(), payload)
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
                            authToken = authToken,
                            reason = request.reason
                        )
                    )
                    withContext(Dispatchers.IO) {
                        invokeServer.close()
                        adminServer.close()
                    }
                }
                PluginJvmJson.instance.encodeToString(
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
                val request = PluginJvmJson.instance.decodeFromString(ReloadPrepareRequest.serializer(), payload)
                val accepted = validateControlRequest(request.pluginId, request.generation, request.authToken, plugin.descriptor.pluginId, generation, authToken) &&
                    inFlightInvokes.get() == 0
                PluginJvmJson.instance.encodeToString(
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
        PluginJvmFrameCodec.write(channel, response)
    }

    private suspend fun handleInvokeConnection(
        channel: SocketChannel,
        plugin: KeelPlugin,
        descriptor: PluginDescriptor,
        authToken: String,
        generation: Long,
        endpoints: Map<String, com.keel.kernel.plugin.PluginEndpointDefinition<*, *>>,
        inFlightInvokes: AtomicInteger,
        eventEmitter: PluginEventEmitter,
        tracer: io.opentelemetry.api.trace.Tracer
    ) {
        val payload = PluginJvmFrameCodec.read(channel)
        val request = PluginJvmJson.instance.decodeFromString(InvokeRequest.serializer(), payload)
        if (!validateControlRequest(request.pluginId, request.generation, request.authToken, plugin.descriptor.pluginId, generation, authToken)) {
            PluginJvmFrameCodec.write(
                channel,
                PluginJvmJson.instance.encodeToString(
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
            PluginJvmFrameCodec.write(
                channel,
                PluginJvmJson.instance.encodeToString(
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
            PluginJvmFrameCodec.write(
                channel,
                PluginJvmJson.instance.encodeToString(
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

            val timeoutMs = endpoint.executionPolicy.timeoutMs ?: descriptor.callTimeoutMs
            val result = withTimeoutOrNull(timeoutMs.milliseconds) {
                endpoint.execute(context, requestBody)
            } ?: run {
                PluginJvmFrameCodec.write(
                    channel,
                    PluginJvmJson.instance.encodeToString(
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
                PluginJvmFrameCodec.write(
                    channel,
                    PluginJvmJson.instance.encodeToString(
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

            PluginJvmFrameCodec.write(
                channel,
                PluginJvmJson.instance.encodeToString(
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
            PluginJvmFrameCodec.write(
                channel,
                PluginJvmJson.instance.encodeToString(
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
            PluginJvmFrameCodec.write(
                channel,
                PluginJvmJson.instance.encodeToString(
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
    private val authToken: String,
    private val descriptor: PluginDescriptor,
    private val commMode: JvmCommunicationMode,
    private val socketPath: Path?,
    private val port: Int?,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher
) {
    private val logger = KeelLoggerService.getLogger("PluginEventEmitter")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val criticalEvents = kotlinx.coroutines.channels.Channel<PluginRuntimeEvent>(capacity = descriptor.criticalEventQueueSize)
    private val criticalDepth = AtomicInteger(0)
    private val droppedLogs = AtomicLong(0)
    private val logBuffer = ArrayDeque<PluginLogEvent>()
    private val overflowPending = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var senderJob: kotlinx.coroutines.Job? = null

    suspend fun start() {
        senderJob = scope.launch {
            while (isActive) {
                try {
                    val channel = when (commMode) {
                        JvmCommunicationMode.UDS -> {
                            val address = UnixDomainSocketAddress.of(requireNotNull(socketPath))
                            SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(address) }
                        }
                        JvmCommunicationMode.TCP -> {
                            val address = java.net.InetSocketAddress("127.0.0.1", requireNotNull(port))
                            SocketChannel.open().also { it.connect(address) }
                        }
                    }

                    channel.use {
                        connected.set(true)
                        while (isActive && it.isOpen) {
                            val overflowEvent = if (overflowPending.getAndSet(false)) {
                                PluginEventQueueOverflowEvent(
                                    pluginId = pluginId,
                                    generation = generation,
                                    timestamp = System.currentTimeMillis(),
                                    messageId = newMessageId(),
                                    authToken = authToken,
                                    queueType = "critical",
                                    capacity = descriptor.criticalEventQueueSize
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
                            PluginJvmFrameCodec.write(it, encodeEvent(event))
                        }
                    }
                } catch (error: Throwable) {
                    connected.set(false)
                    if (!isActive) break
                    logger.warn("EventEmitter failed to connect/write, retrying in 1s: ${error.message}")
                    delay(1000)
                } finally {
                    connected.set(false)
                }
            }
        }
    }

    fun emitCritical(event: PluginRuntimeEvent) {
        criticalDepth.incrementAndGet()
        if (criticalEvents.trySend(event).isFailure) {
            criticalDepth.decrementAndGet()
            overflowPending.set(true)
        }
    }

    fun emitLog(level: String, message: String) {
        synchronized(logBuffer) {
            if (logBuffer.size >= descriptor.eventLogRingBufferSize) {
                logBuffer.removeFirst()
                droppedLogs.incrementAndGet()
            }
            logBuffer.addLast(
                PluginLogEvent(
                    pluginId = pluginId,
                    generation = generation,
                    timestamp = System.currentTimeMillis(),
                    messageId = newMessageId(),
                    authToken = authToken,
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
        flush(descriptor.stopTimeoutMs)
        senderJob?.cancel()
        criticalEvents.close()
    }

    fun queueDepth(): Int = criticalDepth.get() + synchronized(logBuffer) { logBuffer.size }

    fun droppedLogCount(): Long = droppedLogs.get()

    fun connected(): Boolean = connected.get()

    fun newMessageId(): String = java.util.UUID.randomUUID().toString()

    private fun encodeEvent(event: PluginRuntimeEvent): String = when (event) {
        is PluginReadyEvent -> PluginJvmJson.instance.encodeToString(PluginReadyEvent.serializer(), event)
        is PluginStoppingEvent -> PluginJvmJson.instance.encodeToString(PluginStoppingEvent.serializer(), event)
        is PluginDrainCompleteEvent -> PluginJvmJson.instance.encodeToString(PluginDrainCompleteEvent.serializer(), event)
        is PluginDisposedEvent -> PluginJvmJson.instance.encodeToString(PluginDisposedEvent.serializer(), event)
        is PluginFailureEvent -> PluginJvmJson.instance.encodeToString(PluginFailureEvent.serializer(), event)
        is PluginLogEvent -> PluginJvmJson.instance.encodeToString(PluginLogEvent.serializer(), event)
        is PluginEventQueueOverflowEvent -> PluginJvmJson.instance.encodeToString(PluginEventQueueOverflowEvent.serializer(), event)
        is PluginTraceEvent -> PluginJvmJson.instance.encodeToString(PluginTraceEvent.serializer(), event)
        else -> {
            logger.warn("Unsupported runtime event type ${event::class.qualifiedName}")
            PluginJvmJson.instance.encodeToString(
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
