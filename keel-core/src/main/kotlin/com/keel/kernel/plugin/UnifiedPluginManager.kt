package com.keel.kernel.plugin

import com.keel.kernel.di.PluginPrivateScopeHandle
import com.keel.kernel.di.PluginScopeManager
import com.keel.kernel.isolation.PluginProcessSupervisor
import com.keel.kernel.isolation.PluginUdsSocketPaths
import com.keel.kernel.logging.KeelLoggerService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.math.absoluteValue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.koin.core.Koin
import org.koin.core.scope.Scope

class UnifiedPluginManager(
    private val kernelKoin: Koin,
    runtimeRoot: File = File("/tmp/keel"),
    private val currentClasspath: String = System.getProperty("java.class.path")
) : PluginAvailability {
    private val logger = KeelLoggerService.getLogger("UnifiedPluginManager")
    private val entries = ConcurrentHashMap<String, ManagedPlugin>()
    private val pluginScopeManager = PluginScopeManager(kernelKoin)
    private var routing: Routing? = null

    @OptIn(ExperimentalUuidApi::class)
    private val kernelInstanceId = Uuid.random().toString()
    private val kernelRuntimeDir = runtimeRoot.toPath().resolve(kernelInstanceId.take(8)).createDirectories().toFile()

    fun registerPlugin(plugin: KeelPlugin) {
        val descriptor = plugin.descriptor
        val config = PluginConfigLoader.load(descriptor)
        entries[descriptor.pluginId] = ManagedPlugin(
            plugin = plugin,
            pluginClassName = plugin.javaClass.name,
            config = config,
            lifecycleState = PluginLifecycleState.REGISTERED,
            healthState = PluginHealthState.UNKNOWN,
            generation = PluginGeneration.INITIAL,
            processState = if (config.runtimeMode == PluginRuntimeMode.EXTERNAL_JVM) PluginProcessState.STOPPED else null
        )
        logger.info("Registered unified plugin ${descriptor.pluginId} mode=${config.runtimeMode}")
    }

    fun mountRoutes(routing: Routing) {
        this.routing = routing
        for (entry in entries.values) {
            mountPluginRoutes(routing, entry)
        }
    }

    suspend fun startEnabledPlugins() {
        for (entry in entries.values.sortedBy { it.plugin.descriptor.pluginId }) {
            if (entry.config.enabled) {
                startPlugin(entry.plugin.descriptor.pluginId)
            }
        }
    }

    suspend fun startPlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            startPluginLocked(entry)
        }
    }

    suspend fun enablePlugin(pluginId: String) {
        startPlugin(pluginId)
    }

    suspend fun stopPlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            stopPluginLocked(entry)
        }
    }

    suspend fun disposePlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            disposePluginLocked(entry)
        }
    }

    suspend fun reloadPlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            restartPluginGenerationLocked(entry, "reload") {
                PluginConfigLoader.load(entry.plugin.descriptor)
            }
        }
    }

    suspend fun replacePlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            restartPluginGenerationLocked(entry, "replace") {
                PluginConfigLoader.load(entry.plugin.descriptor)
            }
        }
    }

    suspend fun disablePlugin(pluginId: String) {
        disposePlugin(pluginId)
    }

    suspend fun stopAll() {
        entries.keys.sorted().forEach { pluginId ->
            runCatching {
                disposePlugin(pluginId)
            }.onFailure { error ->
                logger.warn("Failed to stop plugin $pluginId: ${error.message}")
            }
        }
    }

    fun getAllPlugins(): Map<String, KeelPlugin> = entries.mapValues { it.value.plugin }.toSortedMap()

    fun getPlugin(pluginId: String): KeelPlugin? = entries[pluginId]?.plugin

    fun getRuntimeMode(pluginId: String): PluginRuntimeMode? = entries[pluginId]?.config?.runtimeMode

    fun getLifecycleState(pluginId: String): PluginLifecycleState = entries[pluginId]?.lifecycleState ?: PluginLifecycleState.REGISTERED

    fun getHealthState(pluginId: String): PluginHealthState = entries[pluginId]?.healthState ?: PluginHealthState.UNKNOWN

    fun getGeneration(pluginId: String): PluginGeneration = entries[pluginId]?.generation ?: PluginGeneration.INITIAL

    fun getProcessState(pluginId: String): PluginProcessState? = entries[pluginId]?.processState

    fun getProcessId(pluginId: String): Long? = entries[pluginId]?.processId

    fun getProcessHandle(pluginId: String): ProcessHandle? = entries[pluginId]?.processHandle

    fun isProcessAlive(pluginId: String): Boolean = entries[pluginId]?.processHandle?.isAlive ?: false

    fun getRuntimeConfig(pluginId: String): PluginConfig? = entries[pluginId]?.config

    fun getLastFailure(pluginId: String): PluginFailureRecord? = entries[pluginId]?.lastFailure

    fun getRuntimeSnapshot(pluginId: String): PluginRuntimeSnapshot? = entries[pluginId]?.let(::buildSnapshot)

    fun getRuntimeSnapshots(): List<PluginRuntimeSnapshot> = entries.values
        .sortedBy { it.plugin.descriptor.pluginId }
        .map(::buildSnapshot)

    fun isIsolated(pluginId: String): Boolean = getRuntimeMode(pluginId) == PluginRuntimeMode.EXTERNAL_JVM

    override fun isPluginEnabled(pluginId: String): Boolean = getLifecycleState(pluginId) == PluginLifecycleState.RUNNING

    fun resolveDispatchDisposition(pluginId: String): PluginDispatchDisposition {
        val entry = entries[pluginId] ?: return PluginDispatchDisposition.PASS_THROUGH
        return when {
            entry.lifecycleState == PluginLifecycleState.DISPOSED -> PluginDispatchDisposition.NOT_FOUND
            entry.lifecycleState != PluginLifecycleState.RUNNING -> PluginDispatchDisposition.UNAVAILABLE
            entry.healthState == PluginHealthState.UNREACHABLE -> PluginDispatchDisposition.UNAVAILABLE
            else -> PluginDispatchDisposition.AVAILABLE
        }
    }

    suspend fun forceKill(pluginId: String): Boolean {
        return withPluginLock(pluginId) { entry ->
            val killed = entry.supervisor?.forceKill() ?: false
            if (killed) {
                entry.lifecycleState = PluginLifecycleState.FAILED
                entry.healthState = PluginHealthState.UNREACHABLE
                entry.processState = PluginProcessState.FAILED
                recordFailure(entry, "force-kill", "Kernel force-killed plugin process")
            }
            killed
        }
    }

    private suspend fun startInProcess(entry: ManagedPlugin) {
        if (!entry.initialized) {
            entry.lifecycleState = PluginLifecycleState.INITIALIZING
            entry.plugin.onInit(BasicPluginInitContext(entry.plugin.descriptor.pluginId, entry.config, kernelKoin))
            entry.initialized = true
            entry.lifecycleState = PluginLifecycleState.STOPPED
        }
        if (entry.runtimeContext != null) {
            entry.lifecycleState = PluginLifecycleState.STARTING
            entry.plugin.onStart(requireNotNull(entry.runtimeContext))
            return
        }
        entry.lifecycleState = PluginLifecycleState.STARTING
        val scopeHandle = recreateScope(entry)
        val runtimeContext = BasicPluginRuntimeContext(
            pluginId = entry.plugin.descriptor.pluginId,
            config = entry.config,
            kernelKoin = kernelKoin,
            privateScope = scopeHandle.privateScope,
            teardownRegistry = scopeHandle.teardownRegistry
        )
        entry.runtimeContext = runtimeContext
        runCatching {
            entry.plugin.onStart(runtimeContext)
        }.onFailure { error ->
            recordFailure(entry, "start", error.message ?: "Plugin start failed")
            closeScope(entry)
            entry.runtimeContext = null
        }.getOrThrow()
    }

    private suspend fun startIsolated(entry: ManagedPlugin) {
        entry.lifecycleState = PluginLifecycleState.STARTING
        val socketStem = socketFileStem(entry.plugin.descriptor.pluginId)
        val socketPaths = PluginUdsSocketPaths(
            invokePath = kernelRuntimeDir.toPath().resolve("$socketStem-invoke.sock"),
            adminPath = kernelRuntimeDir.toPath().resolve("$socketStem-admin.sock"),
            eventPath = kernelRuntimeDir.toPath().resolve("$socketStem-event.sock")
        )
        val supervisor = PluginProcessSupervisor(
            descriptor = entry.plugin.descriptor,
            pluginClassName = entry.pluginClassName,
            config = entry.config,
            expectedEndpoints = entry.plugin.endpoints(),
            classpath = currentClasspath,
            socketPaths = socketPaths,
            runtimeDir = kernelRuntimeDir.toPath(),
            generation = entry.generation,
            onStateChange = { state ->
                entry.processState = state
                if (state == PluginProcessState.FAILED) {
                    entry.lifecycleState = PluginLifecycleState.FAILED
                    entry.healthState = PluginHealthState.UNREACHABLE
                }
            },
            onHealthChange = { health ->
                entry.healthState = health
            },
            onFailure = { failure ->
                entry.lastFailure = failure
            }
        )
        supervisor.start()
        entry.supervisor = supervisor
        entry.processId = supervisor.processId()
        entry.processHandle = supervisor.processHandle()
        entry.processState = PluginProcessState.RUNNING
    }

    private suspend fun startPluginLocked(entry: ManagedPlugin) {
        if (entry.lifecycleState == PluginLifecycleState.RUNNING) {
            return
        }
        logger.info(
            "Lifecycle action=start pluginId=${entry.plugin.descriptor.pluginId} mode=${entry.config.runtimeMode} generation=${entry.generation.value}"
        )
        when (entry.config.runtimeMode) {
            PluginRuntimeMode.IN_PROCESS -> startInProcess(entry)
            PluginRuntimeMode.EXTERNAL_JVM -> startIsolated(entry)
        }
        entry.lifecycleState = PluginLifecycleState.RUNNING
        entry.lastFailure = null
        if (entry.config.runtimeMode == PluginRuntimeMode.IN_PROCESS) {
            entry.healthState = PluginHealthState.HEALTHY
        }
    }

    private suspend fun stopPluginLocked(entry: ManagedPlugin) {
        when (entry.lifecycleState) {
            PluginLifecycleState.REGISTERED -> {
                entry.lifecycleState = PluginLifecycleState.STOPPED
                normalizeProcessState(entry)
                return
            }
            PluginLifecycleState.STOPPED,
            PluginLifecycleState.DISPOSED -> return
            PluginLifecycleState.STOPPING,
            PluginLifecycleState.DISPOSING -> return
            else -> Unit
        }

        logger.info(
            "Lifecycle action=stop pluginId=${entry.plugin.descriptor.pluginId} mode=${entry.config.runtimeMode} generation=${entry.generation.value}"
        )
        entry.lifecycleState = PluginLifecycleState.STOPPING
        when (entry.config.runtimeMode) {
            PluginRuntimeMode.IN_PROCESS -> {
                awaitDrain(entry, entry.config.stopTimeoutMs)
                runCatching {
                    entry.runtimeContext?.let { context -> entry.plugin.onStop(context) }
                }.onFailure { error ->
                    recordFailure(entry, "stop", error.message ?: "Plugin stop failed")
                }.getOrThrow()
            }
            PluginRuntimeMode.EXTERNAL_JVM -> {
                entry.supervisor?.stop()
                awaitDrain(entry, entry.config.stopTimeoutMs)
            }
        }
        entry.supervisor = null
        entry.processId = null
        entry.processHandle = null
        entry.lifecycleState = PluginLifecycleState.STOPPED
        entry.healthState = PluginHealthState.UNKNOWN
        normalizeProcessState(entry)
    }

    private suspend fun disposePluginLocked(entry: ManagedPlugin) {
        if (entry.lifecycleState != PluginLifecycleState.DISPOSED) {
            stopPluginLocked(entry)
        }
        logger.info(
            "Lifecycle action=dispose pluginId=${entry.plugin.descriptor.pluginId} mode=${entry.config.runtimeMode} generation=${entry.generation.value}"
        )
        entry.lifecycleState = PluginLifecycleState.DISPOSING
        runCatching {
            entry.runtimeContext?.let { context ->
                if (entry.config.runtimeMode == PluginRuntimeMode.IN_PROCESS) {
                    entry.plugin.onDispose(context)
                }
            }
        }.onFailure { error ->
            recordFailure(entry, "dispose", error.message ?: "Plugin dispose failed")
        }.getOrThrow()
        closeScope(entry)
        entry.runtimeContext = null
        entry.supervisor = null
        entry.processId = null
        entry.processHandle = null
        entry.initialized = false
        entry.healthState = PluginHealthState.UNKNOWN
        entry.lifecycleState = PluginLifecycleState.DISPOSED
        normalizeProcessState(entry)
    }

    private suspend fun restartPluginGenerationLocked(
        entry: ManagedPlugin,
        action: String,
        loadConfig: () -> PluginConfig
    ) {
        logger.info("Lifecycle action=$action pluginId=${entry.plugin.descriptor.pluginId} generation=${entry.generation.value}")
        stopPluginLocked(entry)
        disposePluginLocked(entry)
        entry.config = loadConfig()
        entry.generation = entry.generation.next()
        normalizeProcessState(entry)
        entry.lastFailure = null
        startPluginLocked(entry)
    }

    private fun recreateScope(entry: ManagedPlugin): PluginPrivateScopeHandle {
        closeScope(entry)
        return pluginScopeManager.createScope(
            pluginId = entry.plugin.descriptor.pluginId,
            config = entry.config,
            modules = entry.plugin.modules()
        ).also { entry.privateScopeHandle = it }
    }

    private fun closeScope(entry: ManagedPlugin) {
        pluginScopeManager.closeScope(entry.plugin.descriptor.pluginId)
        entry.privateScopeHandle = null
    }

    private suspend fun awaitDrain(entry: ManagedPlugin, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (entry.inFlightInvocations.get() > 0 && System.currentTimeMillis() < deadline) {
            delay(25)
        }
        if (entry.inFlightInvocations.get() > 0) {
            logger.warn(
                "Plugin drain timed out pluginId=${entry.plugin.descriptor.pluginId} inflight=${entry.inFlightInvocations.get()} timeoutMs=$timeoutMs"
            )
        }
    }

    private fun normalizeProcessState(entry: ManagedPlugin) {
        entry.processState = when (entry.config.runtimeMode) {
            PluginRuntimeMode.IN_PROCESS -> null
            PluginRuntimeMode.EXTERNAL_JVM -> {
                if (entry.processHandle?.isAlive == true) {
                    entry.processState ?: PluginProcessState.RUNNING
                } else {
                    PluginProcessState.STOPPED
                }
            }
        }
    }

    private fun buildSnapshot(entry: ManagedPlugin): PluginRuntimeSnapshot {
        val diagnostics = when (entry.config.runtimeMode) {
            PluginRuntimeMode.IN_PROCESS -> PluginRuntimeDiagnostics(
                processAlive = null,
                inflightInvocations = entry.inFlightInvocations.get()
            )
            PluginRuntimeMode.EXTERNAL_JVM -> {
                val supervisorDiagnostics = entry.supervisor?.diagnosticsSnapshot()
                    ?: PluginRuntimeDiagnostics(processAlive = entry.processHandle?.isAlive)
                supervisorDiagnostics.copy(inflightInvocations = entry.inFlightInvocations.get())
            }
        }
        return PluginRuntimeSnapshot(
            pluginId = entry.plugin.descriptor.pluginId,
            displayName = entry.plugin.descriptor.displayName,
            version = entry.plugin.descriptor.version,
            runtimeMode = entry.config.runtimeMode,
            lifecycleState = entry.lifecycleState,
            healthState = entry.healthState,
            generation = entry.generation,
            processState = entry.processState,
            processId = entry.processId,
            processHandleAlive = entry.processHandle?.isAlive,
            diagnostics = diagnostics,
            lastFailure = entry.lastFailure
        )
    }

    private fun recordFailure(entry: ManagedPlugin, source: String, message: String) {
        entry.lastFailure = PluginFailureRecord(
            timestamp = System.currentTimeMillis(),
            source = source,
            message = message
        )
    }

    private suspend fun <T> withPluginLock(pluginId: String, block: suspend (ManagedPlugin) -> T): T {
        val entry = entries[pluginId] ?: error("Plugin not registered: $pluginId")
        return entry.lifecycleMutex.withLock { block(entry) }
    }

    private fun socketFileStem(pluginId: String): String {
        val sanitized = pluginId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "plugin" }
        val prefix = sanitized.take(12)
        val suffix = sanitized.hashCode().absoluteValue.toString(16)
        return "$prefix-$suffix"
    }

    private fun mountPluginRoutes(routing: Routing, entry: ManagedPlugin) {
        val pluginId = entry.plugin.descriptor.pluginId
        routing.route("/api/plugins/$pluginId") {
            for (endpoint in entry.plugin.endpoints()) {
                registerPluginOperation(pluginId, endpoint)
                val fullPath = endpoint.path.ifBlank { "" }
                val responseEnvelope = PluginDocumentationLookup.find(endpoint.method, fullPluginPath(pluginId, endpoint.path))?.responseEnvelope ?: false
                when (endpoint.method) {
                    HttpMethod.Get -> mountGet(fullPath, entry, endpoint, responseEnvelope)
                    HttpMethod.Post -> mountPost(fullPath, entry, endpoint, responseEnvelope)
                    HttpMethod.Put -> mountPut(fullPath, entry, endpoint, responseEnvelope)
                    HttpMethod.Delete -> mountDelete(fullPath, entry, endpoint, responseEnvelope)
                    else -> error("Unsupported method: ${endpoint.method}")
                }
            }
        }
    }

    private fun Route.mountGet(path: String, entry: ManagedPlugin, endpoint: PluginEndpointDefinition<*, *>, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            get { handleInvocation(entry, endpoint, responseEnvelope) }
        } else {
            get(path) { handleInvocation(entry, endpoint, responseEnvelope) }
        }
    }

    private fun Route.mountPost(path: String, entry: ManagedPlugin, endpoint: PluginEndpointDefinition<*, *>, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            post { handleInvocation(entry, endpoint, responseEnvelope) }
        } else {
            post(path) { handleInvocation(entry, endpoint, responseEnvelope) }
        }
    }

    private fun Route.mountPut(path: String, entry: ManagedPlugin, endpoint: PluginEndpointDefinition<*, *>, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            put { handleInvocation(entry, endpoint, responseEnvelope) }
        } else {
            put(path) { handleInvocation(entry, endpoint, responseEnvelope) }
        }
    }

    private fun Route.mountDelete(path: String, entry: ManagedPlugin, endpoint: PluginEndpointDefinition<*, *>, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            delete { handleInvocation(entry, endpoint, responseEnvelope) }
        } else {
            delete(path) { handleInvocation(entry, endpoint, responseEnvelope) }
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleInvocation(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean
    ) {
        try {
            when (resolveDispatchDisposition(entry.plugin.descriptor.pluginId)) {
                PluginDispatchDisposition.NOT_FOUND -> {
                    respondPluginResult(
                        call = call,
                        result = PluginResult(status = HttpStatusCode.NotFound.value, body = null),
                        responseType = endpoint.responseType,
                        responseEnvelope = responseEnvelope,
                        errorMessage = "Plugin '${entry.plugin.descriptor.pluginId}' is disposed"
                    )
                    return
                }
                PluginDispatchDisposition.UNAVAILABLE -> {
                    respondPluginResult(
                        call = call,
                        result = PluginResult(status = HttpStatusCode.ServiceUnavailable.value, body = null),
                        responseType = endpoint.responseType,
                        responseEnvelope = responseEnvelope,
                        errorMessage = "Plugin '${entry.plugin.descriptor.pluginId}' is currently unavailable"
                    )
                    return
                }
                PluginDispatchDisposition.PASS_THROUGH,
                PluginDispatchDisposition.AVAILABLE -> Unit
            }

            if (!entry.invokeLimiter.tryAcquire()) {
                respondPluginResult(
                    call = call,
                    result = PluginResult(status = HttpStatusCode.ServiceUnavailable.value, body = null),
                    responseType = endpoint.responseType,
                    responseEnvelope = responseEnvelope,
                    errorMessage = "Plugin '${entry.plugin.descriptor.pluginId}' is at max concurrency"
                )
                return
            }

            val rawBody = readValidatedRequestBody(call, endpoint.requestType)
            val requestBytes = rawBody?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
            val maxPayloadBytes = endpoint.executionPolicy.maxPayloadBytes
            if (maxPayloadBytes != null && requestBytes > maxPayloadBytes && !endpoint.executionPolicy.allowChunkedTransfer) {
                respondPluginResult(
                    call = call,
                    result = PluginResult(status = HttpStatusCode.PayloadTooLarge.value, body = null),
                    responseType = endpoint.responseType,
                    responseEnvelope = responseEnvelope,
                    errorMessage = "Request payload exceeds $maxPayloadBytes bytes"
                )
                return
            }

            try {
                entry.inFlightInvocations.incrementAndGet()
                val timeoutMs = endpoint.executionPolicy.timeoutMs ?: entry.config.callTimeoutMs
                when (entry.config.runtimeMode) {
                    PluginRuntimeMode.IN_PROCESS -> {
                        val request = decodeRequestBody(rawBody, endpoint.requestType)
                        val context = buildRequestContext(call, entry.plugin.descriptor.pluginId, endpoint.method, call.request.path())
                        val result = withTimeout(timeoutMs) {
                            endpoint.execute(context, request)
                        }
                        val responseBytes = encodeResponseBody(result.body, endpoint.responseType)
                            ?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
                        if (maxPayloadBytes != null && responseBytes > maxPayloadBytes && !endpoint.executionPolicy.allowChunkedTransfer) {
                            respondPluginResult(
                                call = call,
                                result = PluginResult(status = HttpStatusCode.PayloadTooLarge.value, body = null),
                                responseType = endpoint.responseType,
                                responseEnvelope = responseEnvelope,
                                errorMessage = "Response payload exceeds $maxPayloadBytes bytes"
                            )
                            return
                        }
                        respondPluginResult(call, result, endpoint.responseType, responseEnvelope)
                    }
                    PluginRuntimeMode.EXTERNAL_JVM -> {
                        val response = requireNotNull(entry.supervisor) { "No supervisor available for isolated plugin ${entry.plugin.descriptor.pluginId}" }
                            .invoke(endpoint, call, rawBody)
                        val body = response.bodyJson?.let { runtimeJson.decodeFromString(serializer(endpoint.responseType), it) }
                        respondPluginResult(
                            call = call,
                            result = PluginResult(status = response.status, headers = response.headers, body = body),
                            responseType = endpoint.responseType,
                            responseEnvelope = responseEnvelope,
                            errorMessage = response.errorMessage
                        )
                    }
                }
            } finally {
                entry.inFlightInvocations.decrementAndGet()
                entry.invokeLimiter.release()
            }
        } catch (error: TimeoutCancellationException) {
            respondPluginResult(
                call = call,
                result = PluginResult(status = HttpStatusCode.GatewayTimeout.value, body = null),
                responseType = endpoint.responseType,
                responseEnvelope = responseEnvelope,
                errorMessage = "Plugin call timed out"
            )
        } catch (error: PluginApiException) {
            respondPluginResult(
                call = call,
                result = PluginResult(status = error.status, body = null),
                responseType = endpoint.responseType,
                responseEnvelope = responseEnvelope,
                errorMessage = error.message
            )
        } catch (error: Exception) {
            logger.error("Plugin endpoint failed pluginId=${entry.plugin.descriptor.pluginId} endpoint=${endpoint.endpointId}", error)
            respondPluginResult(
                call = call,
                result = PluginResult(status = HttpStatusCode.InternalServerError.value, body = null),
                responseType = endpoint.responseType,
                responseEnvelope = responseEnvelope,
                errorMessage = error.message ?: "Internal server error"
            )
        }
    }

    fun applicationRouting(): Routing = requireNotNull(routing) { "Routing has not been mounted yet" }

    private data class ManagedPlugin(
        val plugin: KeelPlugin,
        val pluginClassName: String,
        var config: PluginConfig,
        var lifecycleState: PluginLifecycleState,
        var healthState: PluginHealthState,
        var generation: PluginGeneration,
        var processState: PluginProcessState?,
        val lifecycleMutex: Mutex = Mutex(),
        val invokeLimiter: Semaphore = Semaphore(config.maxConcurrentCalls),
        var supervisor: PluginProcessSupervisor? = null,
        var processId: Long? = null,
        var processHandle: ProcessHandle? = null,
        var initialized: Boolean = false,
        val inFlightInvocations: AtomicInteger = AtomicInteger(0),
        var privateScopeHandle: PluginPrivateScopeHandle? = null,
        var lastFailure: PluginFailureRecord? = null,
        var runtimeContext: BasicPluginRuntimeContext? = null
    )

    private data class BasicPluginInitContext(
        override val pluginId: String,
        override val config: PluginConfig,
        override val kernelKoin: Koin
    ) : PluginInitContext

    private data class BasicPluginRuntimeContext(
        override val pluginId: String,
        override val config: PluginConfig,
        override val kernelKoin: Koin,
        override val privateScope: Scope,
        private val teardownRegistry: PluginTeardownRegistry
    ) : PluginRuntimeContext {
        override fun registerTeardown(action: () -> Unit) {
            teardownRegistry.register(action)
        }
    }
}
