package com.keel.kernel.plugin

import com.keel.kernel.hotreload.DevReloadOutcome
import com.keel.kernel.hotreload.PluginDevelopmentSource
import com.keel.kernel.hotreload.ReloadAttemptResult
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.kernel.di.PluginPrivateScopeHandle
import com.keel.kernel.di.PluginScopeManager
import com.keel.kernel.isolation.PluginProcessSupervisor
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.observability.ObservabilityHub
import com.keel.kernel.observability.ObservabilityTracing
import com.keel.jvm.runtime.PluginSseDataEvent
import io.opentelemetry.context.Context
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.request.path
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.net.URLClassLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
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

    data class KtorScopeSignature(
        val applicationPluginKeys: List<String>,
        val servicePluginKeys: List<String>
    )

    data class ReloadCompatibilityDecision(
        val outcome: DevReloadOutcome,
        val message: String
    )

    companion object {
        private const val EXTERNAL_SSE_IDLE_TIMEOUT_MS: Long = 60_000

        fun hasKtorScopeDrift(previous: KtorScopeSignature, current: KtorScopeSignature): Boolean {
            return previous.applicationPluginKeys != current.applicationPluginKeys ||
                previous.servicePluginKeys != current.servicePluginKeys
        }

        fun decideReloadCompatibility(
            previousTopology: Set<String>,
            newTopology: Set<String>,
            previousKtorScope: KtorScopeSignature,
            newKtorScope: KtorScopeSignature
        ): ReloadCompatibilityDecision {
            if (newTopology != previousTopology) {
                return ReloadCompatibilityDecision(
                    outcome = DevReloadOutcome.RESTART_REQUIRED,
                    message = "Endpoint topology changed and requires restart"
                )
            }
            if (hasKtorScopeDrift(previousKtorScope, newKtorScope)) {
                return ReloadCompatibilityDecision(
                    outcome = DevReloadOutcome.RESTART_REQUIRED,
                    message = "Ktor scope configuration changed and requires restart"
                )
            }
            return ReloadCompatibilityDecision(
                outcome = DevReloadOutcome.RELOADED,
                message = "Reload-compatible generation shape"
            )
        }
    }
    private val pluginScopeManager = PluginScopeManager(kernelKoin)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var routing: Routing? = null

    private val kernelInstanceId = UUID.randomUUID().toString()
    private val kernelRuntimeDir = runtimeRoot.toPath().resolve(kernelInstanceId.take(8)).createDirectories().toFile()

    fun registerPlugin(
        plugin: KeelPlugin,
        enabledOverride: Boolean? = null
    ) {
        val descriptor = plugin.descriptor
        val config = descriptor.toConfig(enabled = enabledOverride ?: true)
        val capabilities = extractCapabilities(plugin, descriptor.pluginId)
        entries[descriptor.pluginId] = ManagedPlugin(
            plugin = plugin,
            pluginClassName = plugin.javaClass.name,
            endpointById = capabilities.endpointById.toMutableMap(),
            endpointTopology = capabilities.topology,
            sseByPath = capabilities.sseByPath.toMutableMap(),
            routeDefinitions = capabilities.routeDefinitions,
            config = config,
            lifecycleState = PluginLifecycleState.REGISTERED,
            healthState = PluginHealthState.UNKNOWN,
            generation = PluginGeneration.INITIAL,
            processState = if (config.runtimeMode == PluginRuntimeMode.EXTERNAL_JVM) PluginProcessState.STOPPED else null,
            pluginApplicationInstallers = capabilities.applicationInstallers,
            pluginServiceRouteInstallers = capabilities.serviceInstallers
        )
        logger.info("Registered unified plugin ${descriptor.pluginId} mode=${config.runtimeMode}")
    }

    fun installConfiguredPluginApplicationKtorPlugins(application: Application) {
        val installedPluginOwners = linkedMapOf<String, String>()
        entries.values
            .asSequence()
            .filter { it.config.enabled }
            .sortedBy { it.plugin.descriptor.pluginId }
            .forEach { entry ->
                entry.pluginApplicationInstallers.forEach { installer ->
                    val pluginId = entry.plugin.descriptor.pluginId
                    val existingOwner = installedPluginOwners.putIfAbsent(installer.pluginKey, pluginId)
                    if (existingOwner != null) {
                        throw IllegalStateException(
                            "Duplicate plugin application-scope Ktor plugin key='${installer.pluginKey}' " +
                                "already declared by pluginId=$existingOwner, conflicted pluginId=$pluginId"
                        )
                    }
                    runCatching {
                        installer.installer(application)
                    }.getOrElse { error ->
                        throw IllegalStateException(
                            "Failed to install plugin application-scope Ktor plugin key='${installer.pluginKey}' " +
                                "for pluginId=$pluginId: ${error.message}",
                            error
                        )
                    }
                }
            }
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
            val snapshot = snapshotEntry(entry)
            val loadResult = loadNewPluginGeneration(source, classpathModulePaths)
            val newGeneration = loadResult.generation ?: return@withPluginLock ReloadAttemptResult(
                pluginId = source.pluginId,
                outcome = DevReloadOutcome.RELOAD_FAILED,
                message = "Source load failed: ${loadResult.error ?: "unknown error"}"
            )
            val validationFailure = validateNewPluginGeneration(source, newGeneration, snapshot)
            if (validationFailure != null) {
                runCatching { newGeneration.classLoader.close() }
                return@withPluginLock validationFailure
            }

            return@withPluginLock runCatching {
                performGenerationSwap(entry, source, newGeneration, snapshot, reason)
            }.getOrElse { error ->
                logger.warn("Source reload failed for ${source.pluginId}: ${error.message}")
                rollbackGenerationSwap(entry, snapshot)
                runCatching { newGeneration.classLoader.close() }
                ReloadAttemptResult(
                    pluginId = source.pluginId,
                    outcome = DevReloadOutcome.RELOAD_FAILED,
                    message = "Source reload failed: ${error.message}"
                )
            }
        }
    }

    private data class EntrySnapshot(
        val plugin: KeelPlugin,
        val pluginClassName: String,
        val routeDefinitions: List<PluginRouteDefinition>,
        val endpointById: Map<String, PluginEndpointDefinition<*, *>>,
        val topology: Set<String>,
        val sseByPath: Map<String, PluginSseDefinition>,
        val pluginApplicationInstallers: List<ApplicationKtorInstaller>,
        val pluginServiceRouteInstallers: List<ServiceKtorInstaller>,
        val sourceClassLoader: URLClassLoader?,
        val config: PluginConfig,
        val generation: PluginGeneration
    )

    private data class NewGeneration(
        val plugin: KeelPlugin,
        val classLoader: URLClassLoader,
        val routeDefinitions: List<PluginRouteDefinition>,
        val endpointById: Map<String, PluginEndpointDefinition<*, *>>,
        val topology: Set<String>,
        val sseByPath: Map<String, PluginSseDefinition>,
        val pluginApplicationInstallers: List<ApplicationKtorInstaller>,
        val pluginServiceRouteInstallers: List<ServiceKtorInstaller>
    )

    private data class LoadResult(
        val generation: NewGeneration? = null,
        val error: String? = null
    )

    private data class PluginCapabilities(
        val routeDefinitions: List<PluginRouteDefinition>,
        val endpointById: Map<String, PluginEndpointDefinition<*, *>>,
        val topology: Set<String>,
        val sseByPath: Map<String, PluginSseDefinition>,
        val applicationInstallers: List<ApplicationKtorInstaller>,
        val serviceInstallers: List<ServiceKtorInstaller>
    )

    private fun extractCapabilities(plugin: KeelPlugin, topologyPluginId: String): PluginCapabilities {
        val routeDefinitions = plugin.endpoints()
        validateServiceDeclaration(plugin.descriptor, routeDefinitions)
        val endpointDefinitions = routeDefinitions.filterIsInstance<PluginEndpointDefinition<*, *>>()
        val endpointById = endpointDefinitions.associateBy { it.endpointId }
        val topology = endpointDefinitions
            .map { operationKey(it.method, fullPluginPath(topologyPluginId, it.path)) }
            .toSet()
        val sseByPath = routeDefinitions.filterIsInstance<PluginSseDefinition>().associateBy { it.path }
        val ktorConfig = plugin.ktorPlugins()
        return PluginCapabilities(
            routeDefinitions = routeDefinitions,
            endpointById = endpointById,
            topology = topology,
            sseByPath = sseByPath,
            applicationInstallers = ktorConfig.configuredApplicationInstallers(),
            serviceInstallers = ktorConfig.configuredServiceInstallers()
        )
    }

    private fun snapshotEntry(entry: ManagedPlugin): EntrySnapshot {
        return EntrySnapshot(
            plugin = entry.plugin,
            pluginClassName = entry.pluginClassName,
            routeDefinitions = entry.routeDefinitions,
            endpointById = entry.endpointById.toMap(),
            topology = entry.endpointTopology.toSet(),
            sseByPath = entry.sseByPath.toMap(),
            pluginApplicationInstallers = entry.pluginApplicationInstallers,
            pluginServiceRouteInstallers = entry.pluginServiceRouteInstallers,
            sourceClassLoader = entry.sourceClassLoader,
            config = entry.config,
            generation = entry.generation
        )
    }

    private fun loadNewPluginGeneration(
        source: PluginDevelopmentSource,
        classpathModulePaths: Set<String>
    ): LoadResult {
        val classLoader = buildSourceClassLoader(classpathModulePaths, source)
        val plugin = runCatching {
            val clazz = classLoader.loadClass(source.implementationClassName)
            require(KeelPlugin::class.java.isAssignableFrom(clazz)) {
                "Class ${source.implementationClassName} does not implement KeelPlugin"
            }
            clazz.getDeclaredConstructor().newInstance() as KeelPlugin
        }.getOrElse { error ->
            runCatching { classLoader.close() }
            return LoadResult(error = error.message)
        }
        val capabilities = extractCapabilities(plugin, source.pluginId)

        return LoadResult(
            generation = NewGeneration(
                plugin = plugin,
                classLoader = classLoader,
                routeDefinitions = capabilities.routeDefinitions,
                endpointById = capabilities.endpointById,
                topology = capabilities.topology,
                sseByPath = capabilities.sseByPath,
                pluginApplicationInstallers = capabilities.applicationInstallers,
                pluginServiceRouteInstallers = capabilities.serviceInstallers
            )
        )
    }

    private fun validateNewPluginGeneration(
        source: PluginDevelopmentSource,
        newGeneration: NewGeneration,
        snapshot: EntrySnapshot
    ): ReloadAttemptResult? {
        if (newGeneration.plugin.descriptor.pluginId != source.pluginId) {
            return ReloadAttemptResult(
                pluginId = source.pluginId,
                outcome = DevReloadOutcome.RELOAD_FAILED,
                message = "Descriptor pluginId mismatch: expected ${source.pluginId}, actual ${newGeneration.plugin.descriptor.pluginId}"
            )
        }
        val previousKtorScope = KtorScopeSignature(
            applicationPluginKeys = snapshot.pluginApplicationInstallers.map { it.pluginKey },
            servicePluginKeys = snapshot.pluginServiceRouteInstallers.map { it.pluginKey }
        )
        val newKtorScope = KtorScopeSignature(
            applicationPluginKeys = newGeneration.pluginApplicationInstallers.map { it.pluginKey },
            servicePluginKeys = newGeneration.pluginServiceRouteInstallers.map { it.pluginKey }
        )
        val decision = decideReloadCompatibility(
            previousTopology = snapshot.topology,
            newTopology = newGeneration.topology,
            previousKtorScope = previousKtorScope,
            newKtorScope = newKtorScope
        )
        if (decision.outcome != DevReloadOutcome.RELOADED) {
            return ReloadAttemptResult(
                pluginId = source.pluginId,
                outcome = decision.outcome,
                message = decision.message
            )
        }
        return null
    }

    private suspend fun performGenerationSwap(
        entry: ManagedPlugin,
        source: PluginDevelopmentSource,
        newGeneration: NewGeneration,
        snapshot: EntrySnapshot,
        reason: String
    ): ReloadAttemptResult {
        stopPluginLocked(entry)
        disposePluginLocked(entry)
        entry.plugin = newGeneration.plugin
        entry.pluginClassName = source.implementationClassName
        entry.routeDefinitions = newGeneration.routeDefinitions
        entry.endpointById = newGeneration.endpointById.toMutableMap()
        entry.endpointTopology = newGeneration.topology
        entry.sseByPath = newGeneration.sseByPath.toMutableMap()
        entry.pluginApplicationInstallers = newGeneration.pluginApplicationInstallers
        entry.pluginServiceRouteInstallers = newGeneration.pluginServiceRouteInstallers
        entry.sourceClassLoader = newGeneration.classLoader
        entry.config = newGeneration.plugin.descriptor.toConfig().copy(runtimeMode = source.runtimeMode)
        entry.generation = snapshot.generation.next()
        normalizeProcessState(entry)
        entry.lastFailure = null
        startPluginLocked(entry)
        withContext(Dispatchers.IO) {
            snapshot.sourceClassLoader?.close()
        }
        return ReloadAttemptResult(
            pluginId = source.pluginId,
            outcome = DevReloadOutcome.RELOADED,
            message = "Reloaded from source ($reason)"
        )
    }

    private suspend fun rollbackGenerationSwap(entry: ManagedPlugin, snapshot: EntrySnapshot) {
        runCatching {
            entry.plugin = snapshot.plugin
            entry.pluginClassName = snapshot.pluginClassName
            entry.routeDefinitions = snapshot.routeDefinitions
            entry.endpointById = snapshot.endpointById.toMutableMap()
            entry.endpointTopology = snapshot.topology
            entry.sseByPath = snapshot.sseByPath.toMutableMap()
            entry.pluginApplicationInstallers = snapshot.pluginApplicationInstallers
            entry.pluginServiceRouteInstallers = snapshot.pluginServiceRouteInstallers
            entry.sourceClassLoader = snapshot.sourceClassLoader
            entry.config = snapshot.config
            entry.generation = snapshot.generation
            normalizeProcessState(entry)
            startPluginLocked(entry)
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
                entry.plugin.descriptor.toConfig()
            }
        }
    }

    suspend fun replacePlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            restartPluginGenerationLocked(entry, "replace") {
                entry.plugin.descriptor.toConfig()
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

    @Suppress("unused")
    fun getPlugin(pluginId: String): KeelPlugin? = entries[pluginId]?.plugin

    fun getRuntimeMode(pluginId: String): PluginRuntimeMode? = entries[pluginId]?.config?.runtimeMode

    fun getLifecycleState(pluginId: String): PluginLifecycleState = entries[pluginId]?.lifecycleState ?: PluginLifecycleState.REGISTERED

    @Suppress("unused")
    fun getHealthState(pluginId: String): PluginHealthState = entries[pluginId]?.healthState ?: PluginHealthState.UNKNOWN

    fun getGeneration(pluginId: String): PluginGeneration = entries[pluginId]?.generation ?: PluginGeneration.INITIAL

    @Suppress("unused")
    fun getProcessState(pluginId: String): PluginProcessState? = entries[pluginId]?.processState

    fun getProcessId(pluginId: String): Long? = entries[pluginId]?.processId

    fun getProcessHandle(pluginId: String): ProcessHandle? = entries[pluginId]?.processHandle

    @Suppress("unused")
    fun isProcessAlive(pluginId: String): Boolean = entries[pluginId]?.processHandle?.isAlive ?: false

    fun getRuntimeConfig(pluginId: String): PluginConfig? = entries[pluginId]?.config

    @Suppress("unused")
    fun getLastFailure(pluginId: String): PluginFailureRecord? = entries[pluginId]?.lastFailure

    fun getRuntimeSnapshot(pluginId: String): PluginRuntimeSnapshot? = entries[pluginId]?.let(::buildSnapshot)

    fun getRuntimeSnapshots(): List<PluginRuntimeSnapshot> = entries.values
        .sortedBy { it.plugin.descriptor.pluginId }
        .map(::buildSnapshot)

    @Suppress("unused")
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
            entry.plugin.onInit(
                BasicPluginInitContext(entry.plugin.descriptor.pluginId, entry.plugin.descriptor, kernelKoin)
            )
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
            descriptor = entry.plugin.descriptor,
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

        val supervisor = PluginProcessSupervisor(
            descriptor = entry.plugin.descriptor,
            pluginClassName = entry.pluginClassName,
            config = entry.config,
            expectedRoutes = entry.routeDefinitions,
            classpath = currentClasspath,
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
            onTerminalFailure = { reason, suggestTcpFallback ->
                managerScope.launch {
                    attemptProcessRecovery(entry.plugin.descriptor.pluginId, reason, suggestTcpFallback)
                }
            },
            forcedCommunicationMode = entry.stickyCommunicationMode,
            observabilityHub = observabilityHub
        )
        supervisor.start()
        entry.supervisor = supervisor
        entry.processId = supervisor.processId()
        entry.processHandle = supervisor.processHandle()
        entry.processState = PluginProcessState.RUNNING
    }

    private suspend fun startPluginLocked(entry: ManagedPlugin, resetRecoveryBudget: Boolean = true) {
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
        if (resetRecoveryBudget) {
            entry.recoveryAttempts = 0
            entry.lastRecoveryAtEpochMs = null
        }
        if (entry.config.runtimeMode == PluginRuntimeMode.IN_PROCESS) {
            entry.healthState = PluginHealthState.HEALTHY
        }
    }

    private suspend fun attemptProcessRecovery(
        pluginId: String,
        reason: String,
        suggestTcpFallback: Boolean
    ) {
        withPluginLock(pluginId) { entry ->
            if (entry.config.runtimeMode != PluginRuntimeMode.EXTERNAL_JVM) return@withPluginLock
            if (!entry.recoveryInProgress.compareAndSet(false, true)) return@withPluginLock
            try {
                if (shouldSkipRecovery(entry)) {
                    return@withPluginLock
                }
                if (entry.lifecycleState == PluginLifecycleState.STOPPING || entry.lifecycleState == PluginLifecycleState.DISPOSING || entry.lifecycleState == PluginLifecycleState.DISPOSED) {
                    return@withPluginLock
                }
                val policy = entry.config.recoveryPolicy
                val now = System.currentTimeMillis()
                val last = entry.lastRecoveryAtEpochMs
                if (last == null || now - last > policy.resetWindowMs) {
                    entry.recoveryAttempts = 0
                }
                if (entry.recoveryAttempts >= policy.maxRestarts) {
                    runCatching {
                        stopPluginLocked(entry)
                    }.onFailure { stopError ->
                        logger.warn("Failed to stop plugin while exhausting recovery budget pluginId=$pluginId: ${stopError.message}")
                    }
                    entry.stickyCommunicationMode = null
                    entry.lifecycleState = PluginLifecycleState.FAILED
                    entry.processState = PluginProcessState.FAILED
                    entry.healthState = PluginHealthState.UNREACHABLE
                    recordFailure(entry, "recovery", "Recovery budget exhausted: $reason")
                    return@withPluginLock
                }

                entry.recoveryAttempts += 1
                entry.lastRecoveryAtEpochMs = now
                val backoffMs = computeRecoveryBackoffMs(policy, entry.recoveryAttempts)
                delay(backoffMs)

                if (shouldSkipRecovery(entry)) {
                    return@withPluginLock
                }

                if (suggestTcpFallback) {
                    entry.stickyCommunicationMode = JvmCommunicationMode.TCP
                }
                runCatching {
                    stopPluginLocked(entry)
                    startPluginLocked(entry, resetRecoveryBudget = false)
                }.onFailure { error ->
                    recordFailure(entry, "recovery", "Recovery attempt failed: ${error.message ?: reason}")
                    entry.lifecycleState = PluginLifecycleState.FAILED
                    entry.healthState = PluginHealthState.UNREACHABLE
                    entry.processState = PluginProcessState.FAILED
                }
            } finally {
                entry.recoveryInProgress.set(false)
            }
        }
    }

    private fun shouldSkipRecovery(entry: ManagedPlugin): Boolean {
        return entry.lifecycleState == PluginLifecycleState.RUNNING &&
            entry.processState == PluginProcessState.RUNNING &&
            entry.processHandle?.isAlive == true &&
            entry.healthState != PluginHealthState.UNREACHABLE
    }

    private fun computeRecoveryBackoffMs(policy: PluginRecoveryPolicy, attempts: Int): Long {
        val shift = (attempts - 1).coerceAtLeast(0)
        val exponential = 1L shl shift
        return (policy.baseBackoffMs * exponential).coerceAtMost(policy.maxBackoffMs)
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
        withContext(Dispatchers.IO) {
            runCatching { entry.sourceClassLoader?.close() }
        }
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
        entry.stickyCommunicationMode = null
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

    private fun validateServiceDeclaration(
        descriptor: PluginDescriptor,
        routeDefinitions: List<PluginRouteDefinition>
    ) {
        val requiredServices = buildSet {
            if (routeDefinitions.any { it is PluginEndpointDefinition<*, *> }) add(PluginServiceType.ENDPOINT)
            if (routeDefinitions.any { it is PluginSseDefinition }) add(PluginServiceType.SSE)
            if (routeDefinitions.any { it is PluginStaticResourceDefinition }) add(PluginServiceType.STATIC_RESOURCE)
        }
        val missing = requiredServices - descriptor.supportedServices
        require(missing.isEmpty()) {
            "Plugin ${descriptor.pluginId} is missing service declarations: ${missing.joinToString()}"
        }
        val declaredButUnused = descriptor.supportedServices - requiredServices
        if (declaredButUnused.isNotEmpty()) {
            logger.warn(
                "Plugin ${descriptor.pluginId} declares unused services: ${declaredButUnused.joinToString()}"
            )
        }
    }

    private fun mountPluginRoutes(routing: Routing, entry: ManagedPlugin) {
        val pluginId = entry.plugin.descriptor.pluginId
        val routes = entry.routeDefinitions
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
        val staticOperationKeys = staticRoutes.map { operationKey(HttpMethod.Get, fullPluginPath(pluginId, it.path)) }
        val staticKeys = staticRoutes.map { fullPluginPath(pluginId, it.path) }
        val duplicateStatic = staticKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateStatic.isNotEmpty()) {
            error("Duplicate plugin static resource registration for pluginId=$pluginId: ${duplicateStatic.joinToString()}")
        }
        val pathCollisions = endpointKeys.intersect(sseKeys)
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
        routing.route("/api/plugins/$pluginId") {
            entry.pluginServiceRouteInstallers.forEach { installer ->
                installer.installer(this)
            }
            for (endpoint in endpoints) {
                registerPluginOperation(pluginId, endpoint)
                val fullPath = endpoint.path.ifBlank { "" }
                when (endpoint.method) {
                    HttpMethod.Get -> mountGet(fullPath, pluginId, endpoint.endpointId, endpoint.doc.responseEnvelope)
                    HttpMethod.Post -> mountPost(fullPath, pluginId, endpoint.endpointId, endpoint.doc.responseEnvelope)
                    HttpMethod.Put -> mountPut(fullPath, pluginId, endpoint.endpointId, endpoint.doc.responseEnvelope)
                    HttpMethod.Delete -> mountDelete(fullPath, pluginId, endpoint.endpointId, endpoint.doc.responseEnvelope)
                    else -> error("Unsupported method: ${endpoint.method}")
                }
            }
            for (definition in sseRoutes) {
                registerPluginSseOperation(pluginId, definition.path, definition.doc)
                mountSse(entry, definition.path, pluginId, definition.path)
            }
            for (definition in staticRoutes) {
                registerPluginStaticOperation(pluginId, definition.path, definition.index != null, definition.doc)
                when (entry.config.runtimeMode) {
                    PluginRuntimeMode.IN_PROCESS -> staticResources(definition.path, definition.basePackage, definition.index)
                    PluginRuntimeMode.EXTERNAL_JVM -> mountExternalStatic(definition.path, pluginId, definition.path)
                }
            }
        }
        validateOpenApiTopologyRegistration(
            pluginId = pluginId,
            expectedKeys = buildSet {
                addAll(endpointKeys)
                addAll(sseKeys)
                addAll(staticOperationKeys)
            }
        )
    }

    private fun validateOpenApiTopologyRegistration(pluginId: String, expectedKeys: Set<String>) {
        if (expectedKeys.isEmpty()) {
            return
        }
        val pluginPrefix = "/api/plugins/$pluginId"
        val actualKeys = OpenApiRegistry.operations()
            .asSequence()
            .filter { operation ->
                operation.path == pluginPrefix || operation.path.startsWith("$pluginPrefix/")
            }
            .map { operationKey(it.method, it.path) }
            .toSet()
        val missingKeys = expectedKeys - actualKeys
        if (missingKeys.isNotEmpty()) {
            error(
                "OpenAPI registered operations for pluginId=$pluginId are missing " +
                    "route topology keys: ${missingKeys.sorted().joinToString()}"
            )
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

    private fun Route.mountSse(entry: ManagedPlugin, path: String, pluginId: String, ssePath: String) {
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
                    when (entry.config.runtimeMode) {
                        PluginRuntimeMode.IN_PROCESS -> {
                            val definition = resolveSseDefinition(pluginId, ssePath)
                            if (definition == null) {
                                send(ServerSentEvent(data = """{"error":"plugin route unavailable"}""", event = "error"))
                                close()
                            } else {
                                val context = buildRequestContext(call, pluginId, HttpMethod.Get, call.request.path())
                                definition.handler.invoke(
                                    PluginSseSession(
                                        request = context,
                                        sender = { event -> send(event) }
                                    )
                                )
                            }
                        }
                        PluginRuntimeMode.EXTERNAL_JVM -> {
                            streamSseFromExternal(entry, ssePath)
                        }
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

    private fun Route.mountExternalStatic(path: String, pluginId: String, staticPath: String) {
        if (path.isBlank()) {
            get("{...}") { proxyExternalStatic(pluginId, staticPath) }
        } else {
            get("$path/{...}") { proxyExternalStatic(pluginId, staticPath) }
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.proxyExternalStatic(
        pluginId: String,
        staticPath: String
    ) {
        val entry = entries[pluginId] ?: run {
            call.respond(HttpStatusCode.NotFound, "Plugin not found")
            return
        }
        val supervisor = entry.supervisor ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, "Plugin supervisor unavailable")
            return
        }
        val tail = call.parameters.getAll("...")?.joinToString("/") ?: ""
        val resourcePath = if (tail.isBlank()) "/" else "/$tail"
        val response = runCatching {
            supervisor.fetchStaticResource(
                routePath = staticPath,
                resourcePath = resourcePath,
                requestHeaders = call.request.headers.entries().associate { it.key to it.value }
            )
        }.getOrElse { error ->
            call.respond(HttpStatusCode.ServiceUnavailable, "Static proxy failed: ${error.message}")
            return
        }
        response.headers.forEach { (key, values) ->
            values.forEach { call.response.headers.append(key, it, safeOnly = false) }
        }
        val status = HttpStatusCode.fromValue(response.status)
        val body = response.bodyBase64?.let { Base64.getDecoder().decode(it) }
        if (body == null) {
            call.respond(status, response.errorMessage ?: "")
            return
        }
        val contentType = response.headers["Content-Type"]?.firstOrNull()?.let { ContentType.parse(it) }
            ?: ContentType.Application.OctetStream
        call.respondBytes(body, contentType = contentType, status = status)
    }

    private suspend fun io.ktor.server.sse.ServerSSESession.streamSseFromExternal(
        entry: ManagedPlugin,
        routePath: String
    ) {
        val supervisor = entry.supervisor ?: run {
            send(ServerSentEvent(data = """{"error":"plugin supervisor unavailable"}""", event = "error"))
            close()
            return
        }
        val streamId = UUID.randomUUID().toString()
        val eventChannel = Channel<PluginSseDataEvent>(capacity = Channel.UNLIMITED)
        supervisor.registerSseStreamListener(
            streamId = streamId,
            onData = { event ->
                if (eventChannel.trySend(event).isFailure) {
                    logger.warn("Dropped SSE event for pluginId=${entry.plugin.descriptor.pluginId} streamId=$streamId because the stream is closed")
                }
            },
            onClosed = {
                eventChannel.close()
            }
        )
        try {
            val call = call
            val requestId = call.request.headers["X-Request-Id"] ?: streamId
            val open = supervisor.openSseStream(
                streamId = streamId,
                routePath = routePath,
                requestId = requestId,
                rawPath = call.request.path(),
                pathParameters = call.parameters.entries().associate { it.key to it.value.first() },
                queryParameters = call.request.queryParameters.entries().associate { it.key to it.value },
                headers = call.request.headers.entries().associate { it.key to it.value }
            )
            if (!open.accepted) {
                send(ServerSentEvent(data = open.errorMessage ?: "SSE open rejected", event = "error"))
                close()
                return
            }
            while (true) {
                val event = withTimeoutOrNull(EXTERNAL_SSE_IDLE_TIMEOUT_MS) {
                    eventChannel.receiveCatching().getOrNull()
                } ?: break
                send(
                    ServerSentEvent(
                        data = event.data,
                        event = event.event,
                        id = event.id,
                        retry = event.retry
                    )
                )
            }
        } finally {
            runCatching { supervisor.closeSseStream(streamId) }
                .onFailure { error ->
                    logger.warn(
                        "Failed to close SSE stream pluginId=${entry.plugin.descriptor.pluginId} streamId=$streamId: ${error.message}"
                    )
                }
            supervisor.unregisterSseStreamListener(streamId)
            eventChannel.close()
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
            var invocationStarted = false
            try {
                val requestPayload = readRequestPayload(entry, endpoint, responseEnvelope) ?: return
                entry.inFlightInvocations.incrementAndGet()
                invocationStarted = true
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
                if (invocationStarted) {
                    entry.inFlightInvocations.decrementAndGet()
                }
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

    @Suppress("unused")
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
            parent = this::class.java.classLoader
        )
    }

    private class SourceFirstClassLoader(
        urls: Array<java.net.URL>,
        parent: ClassLoader
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                if (!isAlwaysParentLoaded(name) && hasChildResource(name)) {
                    runCatching { findClass(name) }.getOrNull()?.let { loaded ->
                        if (resolve) resolveClass(loaded)
                        return loaded
                    }
                }
                return super.loadClass(name, resolve)
            }
        }

        private fun hasChildResource(className: String): Boolean {
            val resource = className.replace('.', '/') + ".class"
            return findResource(resource) != null
        }

        private fun isAlwaysParentLoaded(name: String): Boolean {
            return name.startsWith("java.") ||
                name.startsWith("javax.") ||
                name.startsWith("kotlin.") ||
                name.startsWith("kotlinx.")
        }
    }

    private data class ManagedPlugin(
        var plugin: KeelPlugin,
        var pluginClassName: String,
        var endpointById: MutableMap<String, PluginEndpointDefinition<*, *>>,
        var endpointTopology: Set<String>,
        var sseByPath: MutableMap<String, PluginSseDefinition>,
        var routeDefinitions: List<PluginRouteDefinition>,
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
        var sourceClassLoader: URLClassLoader? = null,
        var stickyCommunicationMode: JvmCommunicationMode? = null,
        var recoveryAttempts: Int = 0,
        var lastRecoveryAtEpochMs: Long? = null,
        val recoveryInProgress: AtomicBoolean = AtomicBoolean(false),
        var pluginApplicationInstallers: List<ApplicationKtorInstaller> = emptyList(),
        var pluginServiceRouteInstallers: List<ServiceKtorInstaller> = emptyList()
    )

    private data class BasicPluginInitContext(
        override val pluginId: String,
        override val descriptor: PluginDescriptor,
        override val kernelKoin: Koin
    ) : PluginInitContext

    private data class BasicPluginRuntimeContext(
        override val pluginId: String,
        override val descriptor: PluginDescriptor,
        override val kernelKoin: Koin,
        override val privateScope: Scope,
        private val teardownRegistry: PluginTeardownRegistry
    ) : PluginRuntimeContext {
        override fun registerTeardown(action: () -> Unit) {
            teardownRegistry.register(action)
        }
    }
}
