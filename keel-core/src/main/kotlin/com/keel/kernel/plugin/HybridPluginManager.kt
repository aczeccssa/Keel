package com.keel.kernel.plugin

import com.keel.kernel.isolation.PluginProcessSupervisor
import com.keel.kernel.logging.KeelLoggerService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.math.absoluteValue

class HybridPluginManager(
    runtimeRoot: File = File("/tmp/keel"),
    private val currentClasspath: String = System.getProperty("java.class.path")
) : PluginAvailability {
    private val logger = KeelLoggerService.getLogger("HybridPluginManager")
    private val entries = ConcurrentHashMap<String, ManagedPlugin>()
    private val lifecycleMutex = Mutex()
    private var routing: Routing? = null

    @OptIn(ExperimentalUuidApi::class)
    private val kernelInstanceId = Uuid.random().toString()
    private val kernelRuntimeDir = runtimeRoot.toPath().resolve(kernelInstanceId.take(8)).createDirectories().toFile()

    fun registerPlugin(plugin: KeelPluginV2) {
        val descriptor = plugin.descriptor
        val config = PluginRuntimeConfigLoader.load(descriptor)
        require(config.executionMode in descriptor.supportedExecutionModes) {
            "Plugin ${descriptor.pluginId} does not support execution mode ${config.executionMode}"
        }
        entries[descriptor.pluginId] = ManagedPlugin(
            plugin = plugin,
            pluginClassName = plugin.javaClass.name,
            config = config,
            state = PluginState.INIT,
            processState = if (config.executionMode == PluginExecutionMode.ISOLATED_JVM) PluginProcessState.STOPPED else null
        )
        logger.info("Registered hybrid plugin ${descriptor.pluginId} mode=${config.executionMode}")
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
                enablePlugin(entry.plugin.descriptor.pluginId)
            }
        }
    }

    suspend fun enablePlugin(pluginId: String) {
        lifecycleMutex.withLock {
            val entry = entries[pluginId] ?: error("Plugin not registered: $pluginId")
            when (entry.config.executionMode) {
                PluginExecutionMode.IN_PROCESS -> startInProcess(entry)
                PluginExecutionMode.ISOLATED_JVM -> startIsolated(entry)
            }
            entry.state = PluginState.ENABLED
        }
    }

    suspend fun disablePlugin(pluginId: String) {
        lifecycleMutex.withLock {
            val entry = entries[pluginId] ?: error("Plugin not registered: $pluginId")
            when (entry.config.executionMode) {
                PluginExecutionMode.IN_PROCESS -> entry.plugin.onStop()
                PluginExecutionMode.ISOLATED_JVM -> entry.supervisor?.stop()
            }
            entry.state = PluginState.DISABLED
            if (entry.config.executionMode == PluginExecutionMode.ISOLATED_JVM) {
                entry.processState = PluginProcessState.STOPPED
            }
        }
    }

    suspend fun stopAll() {
        lifecycleMutex.withLock {
            entries.values.forEach { entry ->
                runCatching {
                    when (entry.config.executionMode) {
                        PluginExecutionMode.IN_PROCESS -> entry.plugin.onStop()
                        PluginExecutionMode.ISOLATED_JVM -> entry.supervisor?.stop()
                    }
                    entry.state = PluginState.DISABLED
                    if (entry.config.executionMode == PluginExecutionMode.ISOLATED_JVM) {
                        entry.processState = PluginProcessState.STOPPED
                    }
                }.onFailure { error ->
                    logger.warn("Failed to stop plugin ${entry.plugin.descriptor.pluginId}: ${error.message}")
                }
            }
        }
    }

    fun getAllPlugins(): Map<String, KeelPluginV2> = entries.mapValues { it.value.plugin }.toSortedMap()

    fun getPlugin(pluginId: String): KeelPluginV2? = entries[pluginId]?.plugin

    fun getPluginState(pluginId: String): PluginState = entries[pluginId]?.state ?: PluginState.INIT

    fun getExecutionMode(pluginId: String): PluginExecutionMode? = entries[pluginId]?.config?.executionMode

    fun getProcessState(pluginId: String): PluginProcessState? = entries[pluginId]?.processState

    fun getRuntimeConfig(pluginId: String): PluginRuntimeConfig? = entries[pluginId]?.config

    fun isIsolated(pluginId: String): Boolean = getExecutionMode(pluginId) == PluginExecutionMode.ISOLATED_JVM

    override fun isPluginEnabled(pluginId: String): Boolean = getPluginState(pluginId) == PluginState.ENABLED

    private suspend fun startInProcess(entry: ManagedPlugin) {
        if (!entry.initialized) {
            entry.plugin.onInit(BasicPluginInitContext(entry.plugin.descriptor.pluginId, entry.config))
            entry.initialized = true
            entry.state = PluginState.INSTALLED
        }
        entry.plugin.onInstall(BasicPluginScope(entry.plugin.descriptor.pluginId))
    }

    private suspend fun startIsolated(entry: ManagedPlugin) {
        val socketPath = kernelRuntimeDir.toPath().resolve(socketFileName(entry.plugin.descriptor.pluginId))
        val supervisor = PluginProcessSupervisor(
            descriptor = entry.plugin.descriptor,
            pluginClassName = entry.pluginClassName,
            config = entry.config,
            classpath = currentClasspath,
            socketPath = socketPath,
            runtimeDir = kernelRuntimeDir.toPath(),
            onStateChange = { state -> entry.processState = state }
        )
        supervisor.start()
        entry.supervisor = supervisor
        entry.processState = PluginProcessState.RUNNING
    }

    private fun socketFileName(pluginId: String): String {
        val sanitized = pluginId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "plugin" }
        val prefix = sanitized.take(12)
        val suffix = sanitized.hashCode().absoluteValue.toString(16)
        return "$prefix-$suffix.sock"
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
            when (entry.config.executionMode) {
                PluginExecutionMode.IN_PROCESS -> {
                    val request = decodeRequestBody(call, endpoint.requestType)
                    val context = buildRequestContext(call, entry.plugin.descriptor.pluginId, endpoint.method, call.request.path())
                    val result = endpoint.execute(context, request)
                    respondPluginResult(call, result, endpoint.responseType, responseEnvelope)
                }
                PluginExecutionMode.ISOLATED_JVM -> {
                    val bodyJson = if (endpoint.requestType != null) call.receiveText().ifBlank { null } else null
                    val response = requireNotNull(entry.supervisor) { "No supervisor available for isolated plugin ${entry.plugin.descriptor.pluginId}" }
                        .invoke(endpoint, call, bodyJson)
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
        val plugin: KeelPluginV2,
        val pluginClassName: String,
        val config: PluginRuntimeConfig,
        var state: PluginState,
        var processState: PluginProcessState?,
        var supervisor: PluginProcessSupervisor? = null,
        var initialized: Boolean = false
    )

    private data class BasicPluginInitContext(
        override val pluginId: String,
        override val config: PluginRuntimeConfig
    ) : PluginInitContextV2

    private data class BasicPluginScope(
        override val pluginId: String
    ) : PluginScopeV2
}
