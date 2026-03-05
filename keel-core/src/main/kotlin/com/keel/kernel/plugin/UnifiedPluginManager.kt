package com.keel.kernel.plugin

import com.keel.kernel.hotreload.DevReloadOutcome
import com.keel.kernel.hotreload.PluginDevelopmentSource
import com.keel.kernel.hotreload.ReloadAttemptResult
import com.keel.kernel.plugin.PluginDocumentationLookup
import com.keel.kernel.plugin.buildRequestContext
import com.keel.kernel.plugin.decodeRequestBody
import com.keel.kernel.plugin.encodeResponseBody
import com.keel.kernel.plugin.fullPluginPath
import com.keel.kernel.plugin.operationKey
import com.keel.kernel.plugin.readValidatedRequestBody
import com.keel.kernel.plugin.registerPluginOperation
import com.keel.kernel.plugin.registerPluginSseOperation
import com.keel.kernel.plugin.registerPluginStaticOperation
import com.keel.kernel.plugin.respondPluginResult
import com.keel.kernel.plugin.runtimeJson
import com.keel.kernel.plugin.serializer
import com.keel.kernel.di.PluginPrivateScopeHandle
import com.keel.kernel.di.PluginScopeManager
import com.keel.kernel.isolation.PluginProcessSupervisor
import com.keel.kernel.isolation.PluginUdsSocketPaths
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.observability.ObservabilityHub
import com.keel.kernel.observability.ObservabilityTracing
import io.opentelemetry.context.Context
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import java.util.concurrent.atomic.AtomicInteger
import java.net.URLClassLoader
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
    private val currentClasspath: String = System.getProperty("java.class.path"),
    private val observabilityHub: ObservabilityHub? = null
) : PluginAvailability {
    private val logger = KeelLoggerService.getLogger("UnifiedPluginManager")
    private val entries = ConcurrentHashMap<String, ManagedPlugin>()
    private val developmentSources = ConcurrentHashMap<String, PluginDevelopmentSource>()
    private val pluginScopeManager = PluginScopeManager(kernelKoin)
    private var routing: Routing? = null

    @OptIn(ExperimentalUuidApi::class)
    private val kernelInstanceId = Uuid.random().toString()
    private val kernelRuntimeDir = runtimeRoot.toPath().resolve(kernelInstanceId.take(8)).createDirectories().toFile()

    fun registerPlugin(plugin: KeelPlugin, enabledOverride: Boolean? = null) {
        val descriptor = plugin.descriptor
        val config = PluginConfigLoader.load(descriptor).let { loaded ->
            enabledOverride?.let { loaded.copy(enabled = it) } ?: loaded
        }
        val routeDefinitions = plugin.endpoints()
        val endpointDefinitions = routeDefinitions.filterIsInstance<PluginEndpointDefinition<*, *>>()
        val endpointById = endpointDefinitions.associateBy { it.endpointId }.toMutableMap()
        val endpointTopology = endpointDefinitions.map { operationKey(it.method, fullPluginPath(descriptor.pluginId, it.path)) }.toSet()
        val sseByPath = routeDefinitions.filterIsInstance<PluginSseDefinition>().associateBy { it.path }.toMutableMap()
        entries[descriptor.pluginId] = ManagedPlugin(
            plugin = plugin,
            pluginClassName = plugin.javaClass.name,
            endpointById = endpointById,
            endpointTopology = endpointTopology,
            sseByPath = sseByPath,
            config = config,
            lifecycleState = PluginLifecycleState.REGISTERED,
            healthState = PluginHealthState.UNKNOWN,
            generation = PluginGeneration.INITIAL,
            processState = if (config.runtimeMode == PluginRuntimeMode.EXTERNAL_JVM) PluginProcessState.STOPPED else null
        )
        logger.info("Registered unified plugin ${descriptor.pluginId} mode=${config.runtimeMode}")
    }

    fun registerPluginSource(source: PluginDevelopmentSource) {
        developmentSources[source.pluginId] = source
    }

    fun hasPluginSource(pluginId: String): Boolean = developmentSources.containsKey(pluginId)

    suspend fun reloadPluginFromSource(
        source: PluginDevelopmentSource,
        classpathModulePaths: Set<String>,
        reason: String
    ): ReloadAttemptResult {
        registerPluginSource(source)
        return withPluginLock(source.pluginId) { entry ->
            val previousPlugin = entry.plugin
            val previousPluginClassName = entry.pluginClassName
            val previousEndpointById = entry.endpointById.toMap()
            val previousTopology = entry.endpointTopology.toSet()
            val previousSseByPath = entry.sseByPath.toMap()
            val previousSourceClassLoader = entry.sourceClassLoader
            val previousConfig = entry.config
            val previousGeneration = entry.generation

            val newClassLoader = buildSourceClassLoader(classpathModulePaths, source)
            val newPlugin = runCatching {
                val clazz = newClassLoader.loadClass(source.implementationClassName)
                require(KeelPlugin::class.java.isAssignableFrom(clazz)) {
                    "Class ${source.implementationClassName} does not implement KeelPlugin"
                }
                @Suppress("UNCHECKED_CAST")
                clazz.getDeclaredConstructor().newInstance() as KeelPlugin
            }.getOrElse { error ->
                newClassLoader.close()
                return@withPluginLock ReloadAttemptResult(
                    pluginId = source.pluginId,
                    outcome = DevReloadOutcome.RELOAD_FAILED,
                    message = "Source load failed: ${error.message}"
                )
            }

            if (newPlugin.descriptor.pluginId != source.pluginId) {
                newClassLoader.close()
                return@withPluginLock ReloadAttemptResult(
                    pluginId = source.pluginId,
                    outcome = DevReloadOutcome.RELOAD_FAILED,
                    message = "Descriptor pluginId mismatch: expected ${source.pluginId}, actual ${newPlugin.descriptor.pluginId}"
                )
            }

            val newEndpoints = newPlugin.endpoints().filterIsInstance<PluginEndpointDefinition<*, *>>()
            val newEndpointById = newEndpoints.associateBy { it.endpointId }
            val newTopology = newEndpoints.map { operationKey(it.method, fullPluginPath(source.pluginId, it.path)) }.toSet()
            val newSseByPath = newPlugin.endpoints().filterIsInstance<PluginSseDefinition>().associateBy { it.path }
            if (newTopology != previousTopology) {
                newClassLoader.close()
                return@withPluginLock ReloadAttemptResult(
                    pluginId = source.pluginId,
                    outcome = DevReloadOutcome.RESTART_REQUIRED,
                    message = "Endpoint topology changed and requires restart"
                )
            }

            return@withPluginLock runCatching {
                stopPluginLocked(entry)
                disposePluginLocked(entry)
                entry.plugin = newPlugin
                entry.pluginClassName = source.implementationClassName
                entry.endpointById = newEndpointById.toMutableMap()
                entry.endpointTopology = newTopology
                entry.sseByPath = newSseByPath.toMutableMap()
                entry.sourceClassLoader = newClassLoader
                entry.config = PluginConfigLoader.load(newPlugin.descriptor).copy(runtimeMode = source.runtimeMode)
                entry.generation = previousGeneration.next()
                normalizeProcessState(entry)
                entry.lastFailure = null
                startPluginLocked(entry)
                previousSourceClassLoader?.close()
                ReloadAttemptResult(
                    pluginId = source.pluginId,
                    outcome = DevReloadOutcome.RELOADED,
                    message = "Reloaded from source ($reason)"
                )
            }.getOrElse { error ->
                logger.warn("Source reload failed for ${source.pluginId}: ${error.message}")
                runCatching {
                    entry.plugin = previousPlugin
                    entry.pluginClassName = previousPluginClassName
                    entry.endpointById = previousEndpointById.toMutableMap()
                    entry.endpointTopology = previousTopology
                    entry.sseByPath = previousSseByPath.toMutableMap()
                    entry.sourceClassLoader = previousSourceClassLoader
                    entry.config = previousConfig
                    entry.generation = previousGeneration
                    normalizeProcessState(entry)
                    startPluginLocked(entry)
                }
                runCatching { newClassLoader.close() }
                ReloadAttemptResult(
                    pluginId = source.pluginId,
                    outcome = DevReloadOutcome.RELOAD_FAILED,
                    message = "Source reload failed: ${error.message}"
                )
            }
        }
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
            expectedEndpoints = entry.plugin.endpoints().filterIsInstance<PluginEndpointDefinition<*, *>>(),
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
            },
            observabilityHub = observabilityHub
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
        runCatching { entry.sourceClassLoader?.close() }
        entry.sourceClassLoader = null
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
        val routes = entry.plugin.endpoints()
        val endpoints = routes.filterIsInstance<PluginEndpointDefinition<*, *>>()
        val sseRoutes = routes.filterIsInstance<PluginSseDefinition>()
        val staticRoutes = routes.filterIsInstance<PluginStaticResourceDefinition>()
        val endpointKeys = endpoints.map { operationKey(it.method, fullPluginPath(pluginId, it.path)) }
        val duplicates = endpointKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            error("Duplicate plugin endpoint registration for pluginId=$pluginId: ${duplicates.joinToString()}")
        }
        val sseKeys = sseRoutes.map { operationKey(HttpMethod.Get, fullPluginPath(pluginId, it.path)) }
        val duplicateSse = sseKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateSse.isNotEmpty()) {
            error("Duplicate plugin SSE registration for pluginId=$pluginId: ${duplicateSse.joinToString()}")
        }
        val staticKeys = staticRoutes.map { fullPluginPath(pluginId, it.path) }
        val duplicateStatic = staticKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateStatic.isNotEmpty()) {
            error("Duplicate plugin static resource registration for pluginId=$pluginId: ${duplicateStatic.joinToString()}")
        }
        val pathCollisions = endpointKeys.toSet().intersect(sseKeys.toSet())
        if (pathCollisions.isNotEmpty()) {
            error("Plugin endpoint/SSE path conflict for pluginId=$pluginId: ${pathCollisions.joinToString()}")
        }
        val staticConflicts = staticRoutes.filter { definition ->
            val prefix = fullPluginPath(pluginId, definition.path)
            endpointKeys.any { it == operationKey(HttpMethod.Get, prefix) || it.startsWith("${HttpMethod.Get.value} $prefix/") } ||
                sseKeys.any { it == operationKey(HttpMethod.Get, prefix) || it.startsWith("${HttpMethod.Get.value} $prefix/") }
        }
        if (staticConflicts.isNotEmpty()) {
            error("Plugin static resource path conflict for pluginId=$pluginId: ${staticConflicts.joinToString { it.path }}")
        }
        val declaredKeys = PluginDocumentationLookup.declaredOperationsForPlugin(pluginId)
            .map { operationKey(it.method, it.path) }
            .toSet()
        val routeKeySet = endpointKeys.toMutableSet().apply {
            addAll(sseKeys)
            addAll(staticRoutes.map { operationKey(HttpMethod.Get, fullPluginPath(pluginId, it.path)) })
        }
        val missing = declaredKeys - routeKeySet
        if (missing.isNotEmpty()) {
            error("OpenAPI declared operations for pluginId=$pluginId are not backed by KeelPlugin.endpoints(): ${missing.sorted().joinToString()}")
        }
        routing.route("/api/plugins/$pluginId") {
            for (endpoint in endpoints) {
                registerPluginOperation(pluginId, endpoint)
                val fullPath = endpoint.path.ifBlank { "" }
                val responseEnvelope = PluginDocumentationLookup.find(endpoint.method, fullPluginPath(pluginId, endpoint.path))?.responseEnvelope ?: false
                when (endpoint.method) {
                    HttpMethod.Get -> mountGet(fullPath, pluginId, endpoint.endpointId, responseEnvelope)
                    HttpMethod.Post -> mountPost(fullPath, pluginId, endpoint.endpointId, responseEnvelope)
                    HttpMethod.Put -> mountPut(fullPath, pluginId, endpoint.endpointId, responseEnvelope)
                    HttpMethod.Delete -> mountDelete(fullPath, pluginId, endpoint.endpointId, responseEnvelope)
                    else -> error("Unsupported method: ${endpoint.method}")
                }
            }
            for (definition in sseRoutes) {
                registerPluginSseOperation(pluginId, definition.path)
                mountSse(definition.path, pluginId, definition.path)
            }
            for (definition in staticRoutes) {
                registerPluginStaticOperation(pluginId, definition.path, definition.index != null)
                staticResources(definition.path, definition.basePackage, definition.index)
            }
        }
    }

    private fun Route.mountGet(path: String, pluginId: String, endpointId: String, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            get { handleInvocation(pluginId, endpointId, responseEnvelope) }
        } else {
            get(path) { handleInvocation(pluginId, endpointId, responseEnvelope) }
        }
    }

    private fun Route.mountPost(path: String, pluginId: String, endpointId: String, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            post { handleInvocation(pluginId, endpointId, responseEnvelope) }
        } else {
            post(path) { handleInvocation(pluginId, endpointId, responseEnvelope) }
        }
    }

    private fun Route.mountPut(path: String, pluginId: String, endpointId: String, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            put { handleInvocation(pluginId, endpointId, responseEnvelope) }
        } else {
            put(path) { handleInvocation(pluginId, endpointId, responseEnvelope) }
        }
    }

    private fun Route.mountDelete(path: String, pluginId: String, endpointId: String, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            delete { handleInvocation(pluginId, endpointId, responseEnvelope) }
        } else {
            delete(path) { handleInvocation(pluginId, endpointId, responseEnvelope) }
        }
    }

    private fun Route.mountSse(path: String, pluginId: String, ssePath: String) {
        val handler: suspend io.ktor.server.sse.ServerSSESession.() -> Unit = {
            when (resolveDispatchDisposition(pluginId)) {
                PluginDispatchDisposition.NOT_FOUND -> {
                    close()
                }
                PluginDispatchDisposition.UNAVAILABLE -> {
                    send(ServerSentEvent(data = """{"error":"plugin unavailable"}""", event = "error"))
                    close()
                }
                PluginDispatchDisposition.PASS_THROUGH,
                PluginDispatchDisposition.AVAILABLE -> {
                    val definition = resolveSseDefinition(pluginId, ssePath)
                    if (definition == null) {
                        send(ServerSentEvent(data = """{"error":"plugin route unavailable"}""", event = "error"))
                        close()
                    } else {
                        val context = buildRequestContext(call, pluginId, HttpMethod.Get, call.request.path())
                        definition.handler.invoke(PluginSseSession(context, this))
                    }
                }
            }
        }
        if (path.isBlank()) {
            sse(handler)
        } else {
            sse(path, handler)
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleInvocation(
        pluginId: String,
        endpointId: String,
        responseEnvelope: Boolean
    ) {
        val entry = entries[pluginId] ?: return
        val endpoint = resolveEndpoint(pluginId, endpointId) ?: run {
            call.respond(HttpStatusCode.NotFound, "Plugin endpoint not found")
            return
        }
        try {
            val tracer = ObservabilityTracing.kernelTracer()
            if (!ensureDispatchAvailable(entry, endpoint, responseEnvelope)) return
            if (!tryAcquireInvokeLimiter(entry, endpoint, responseEnvelope)) return
            val requestPayload = readRequestPayload(entry, endpoint, responseEnvelope) ?: return

            try {
                entry.inFlightInvocations.incrementAndGet()
                val timeoutMs = endpoint.executionPolicy.timeoutMs ?: entry.config.callTimeoutMs
                val parentContext = call.attributes.getOrNull(ObservabilityTracing.TRACE_CONTEXT_KEY) ?: Context.current()
                when (entry.config.runtimeMode) {
                    PluginRuntimeMode.IN_PROCESS -> {
                        invokeInProcess(
                            entry = entry,
                            endpoint = endpoint,
                            responseEnvelope = responseEnvelope,
                            rawBody = requestPayload.rawBody,
                            maxPayloadBytes = requestPayload.maxPayloadBytes,
                            timeoutMs = timeoutMs,
                            tracer = tracer,
                            parentContext = parentContext
                        )
                    }
                    PluginRuntimeMode.EXTERNAL_JVM -> {
                        invokeExternal(
                            entry = entry,
                            endpoint = endpoint,
                            responseEnvelope = responseEnvelope,
                            rawBody = requestPayload.rawBody,
                            tracer = tracer,
                            parentContext = parentContext
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

    private suspend fun io.ktor.server.routing.RoutingContext.ensureDispatchAvailable(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean
    ): Boolean {
        return when (resolveDispatchDisposition(entry.plugin.descriptor.pluginId)) {
            PluginDispatchDisposition.NOT_FOUND -> {
                respondPluginResult(
                    call = call,
                    result = PluginResult(status = HttpStatusCode.NotFound.value, body = null),
                    responseType = endpoint.responseType,
                    responseEnvelope = responseEnvelope,
                    errorMessage = "Plugin '${entry.plugin.descriptor.pluginId}' is disposed"
                )
                false
            }
            PluginDispatchDisposition.UNAVAILABLE -> {
                respondPluginResult(
                    call = call,
                    result = PluginResult(status = HttpStatusCode.ServiceUnavailable.value, body = null),
                    responseType = endpoint.responseType,
                    responseEnvelope = responseEnvelope,
                    errorMessage = "Plugin '${entry.plugin.descriptor.pluginId}' is currently unavailable"
                )
                false
            }
            PluginDispatchDisposition.PASS_THROUGH,
            PluginDispatchDisposition.AVAILABLE -> true
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.tryAcquireInvokeLimiter(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean
    ): Boolean {
        if (entry.invokeLimiter.tryAcquire()) return true
        respondPluginResult(
            call = call,
            result = PluginResult(status = HttpStatusCode.ServiceUnavailable.value, body = null),
            responseType = endpoint.responseType,
            responseEnvelope = responseEnvelope,
            errorMessage = "Plugin '${entry.plugin.descriptor.pluginId}' is at max concurrency"
        )
        return false
    }

    private data class RequestPayload(
        val rawBody: String?,
        val maxPayloadBytes: Long?
    )

    private suspend fun io.ktor.server.routing.RoutingContext.readRequestPayload(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean
    ): RequestPayload? {
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
            return null
        }
        return RequestPayload(rawBody, maxPayloadBytes)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.invokeInProcess(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean,
        rawBody: String?,
        maxPayloadBytes: Long?,
        timeoutMs: Long,
        tracer: io.opentelemetry.api.trace.Tracer?,
        parentContext: Context
    ) {
        val span = tracer?.spanBuilder("plugin.invoke")
            ?.setParent(parentContext)
            ?.setAttribute("keel.pluginId", entry.plugin.descriptor.pluginId)
            ?.setAttribute("keel.jvm", "kernel")
            ?.setAttribute("keel.edge.from", "kernel")
            ?.setAttribute("keel.edge.to", entry.plugin.descriptor.pluginId)
            ?.startSpan()
        val scope = span?.makeCurrent()
        try {
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
        } finally {
            scope?.close()
            span?.end()
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.invokeExternal(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean,
        rawBody: String?,
        tracer: io.opentelemetry.api.trace.Tracer?,
        parentContext: Context
    ) {
        val span = tracer?.spanBuilder("plugin.dispatch")
            ?.setParent(parentContext)
            ?.setAttribute("keel.pluginId", entry.plugin.descriptor.pluginId)
            ?.setAttribute("keel.jvm", "kernel")
            ?.setAttribute("keel.edge.from", "kernel")
            ?.setAttribute("keel.edge.to", entry.plugin.descriptor.pluginId)
            ?.startSpan()
        val scope = span?.makeCurrent()
        try {
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
        } finally {
            scope?.close()
            span?.end()
        }
    }

    fun applicationRouting(): Routing = requireNotNull(routing) { "Routing has not been mounted yet" }

    private fun resolveEndpoint(pluginId: String, endpointId: String): PluginEndpointDefinition<*, *>? {
        return entries[pluginId]?.endpointById?.get(endpointId)
    }

    private fun resolveSseDefinition(pluginId: String, path: String): PluginSseDefinition? {
        return entries[pluginId]?.sseByPath?.get(path)
    }

    private fun buildSourceClassLoader(
        classpathModulePaths: Set<String>,
        source: PluginDevelopmentSource
    ): URLClassLoader {
        val urls = linkedSetOf<java.net.URL>()
        classpathModulePaths.forEach { modulePath ->
            val moduleDir = File(modulePath)
            val classDirs = listOf(
                File(moduleDir, "build/classes/kotlin/main"),
                File(moduleDir, "build/classes/java/main"),
                File(moduleDir, "build/resources/main")
            )
            classDirs.filter { it.exists() }.forEach { urls += it.toURI().toURL() }
        }
        val classpathEntries = currentClasspath.split(File.pathSeparator)
            .map(::File)
            .filter(File::exists)
            .map { it.toURI().toURL() }
        urls += classpathEntries
        return SourceFirstClassLoader(
            urls = urls.toTypedArray(),
            parent = this::class.java.classLoader,
            protectedPrefixes = setOf(
                "java.",
                "javax.",
                "kotlin.",
                "kotlinx.",
                "sun.",
                "io.ktor.",
                "org.slf4j.",
                "com.keel.kernel.",
                "com.keel.contract.",
                "com.keel.openapi."
            ),
            fallbackPreferredPrefixes = setOf(
                source.implementationClassName.substringBeforeLast('.', missingDelimiterValue = source.implementationClassName)
            )
        )
    }

    private class SourceFirstClassLoader(
        urls: Array<java.net.URL>,
        parent: ClassLoader,
        private val protectedPrefixes: Set<String>,
        private val fallbackPreferredPrefixes: Set<String>
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                val parentOnly = protectedPrefixes.any { name.startsWith(it) }
                if (!parentOnly) {
                    runCatching { findClass(name) }.getOrNull()?.let { loaded ->
                        if (resolve) resolveClass(loaded)
                        return loaded
                    }
                    val fallbackChildFirst = fallbackPreferredPrefixes.any { prefix -> name == prefix || name.startsWith("$prefix.") }
                    if (fallbackChildFirst) {
                        runCatching { findClass(name) }.getOrNull()?.let { loaded ->
                            if (resolve) resolveClass(loaded)
                            return loaded
                        }
                    }
                }
                return super.loadClass(name, resolve)
            }
        }
    }

    private data class ManagedPlugin(
        var plugin: KeelPlugin,
        var pluginClassName: String,
        var endpointById: MutableMap<String, PluginEndpointDefinition<*, *>>,
        var endpointTopology: Set<String>,
        var sseByPath: MutableMap<String, PluginSseDefinition>,
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
        var runtimeContext: BasicPluginRuntimeContext? = null,
        var sourceClassLoader: URLClassLoader? = null
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
