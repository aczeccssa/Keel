package com.keel.kernel.plugin

import com.keel.kernel.di.PluginPrivateScopeHandle
import com.keel.kernel.di.PluginScopeManager
import com.keel.kernel.isolation.PluginProcessSupervisor
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.observability.ObservabilityHub
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.Koin

internal class PluginLifecycleCoordinator(
    private val registry: PluginRegistry,
    private val kernelKoin: Koin,
    private val pluginScopeManager: PluginScopeManager,
    private val currentClasspath: String,
    private val kernelRuntimeDir: File,
    private val observabilityHub: ObservabilityHub?,
    private val managerScope: CoroutineScope
) {
    private val logger = KeelLoggerService.getLogger("PluginLifecycleCoordinator")

    suspend fun startEnabledPlugins(startPlugin: suspend (String) -> Unit) {
        registry.sortedValues().forEach { entry ->
            if (entry.config.enabled) {
                startPlugin(entry.plugin.descriptor.pluginId)
            }
        }
    }

    suspend fun stopAll(disposePlugin: suspend (String) -> Unit) {
        registry.ids().sorted().forEach { pluginId ->
            runCatching {
                disposePlugin(pluginId)
            }.onFailure { error ->
                logger.warn("Failed to stop plugin $pluginId: ${error.message}")
            }
        }
    }

    fun buildSnapshot(entry: ManagedPlugin): PluginRuntimeSnapshot {
        val diagnostics = when (entry.config.runtimeMode) {
            PluginRuntimeMode.IN_PROCESS -> PluginRuntimeDiagnostics(
                processAlive = null,
                inflightInvocations = entry.inFlightInvocations.get(),
                assetMetadata = entry.plugin.descriptor.nodeAssetMetadata
            )
            PluginRuntimeMode.EXTERNAL_JVM -> {
                val supervisorDiagnostics = entry.supervisor?.diagnosticsSnapshot()
                    ?: PluginRuntimeDiagnostics(processAlive = entry.processHandle?.isAlive)
                supervisorDiagnostics.copy(
                    inflightInvocations = entry.inFlightInvocations.get(),
                    assetMetadata = supervisorDiagnostics.assetMetadata ?: entry.plugin.descriptor.nodeAssetMetadata
                )
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

    suspend fun startPluginLocked(
        entry: ManagedPlugin,
        normalizeProcessState: (ManagedPlugin) -> Unit,
        recordFailure: (ManagedPlugin, String, String) -> Unit,
        resetRecoveryBudget: Boolean = true
    ) {
        if (entry.lifecycleState == PluginLifecycleState.RUNNING) {
            return
        }
        logger.info(
            "Lifecycle action=start pluginId=${entry.plugin.descriptor.pluginId} mode=${entry.config.runtimeMode} generation=${entry.generation.value}"
        )
        when (entry.config.runtimeMode) {
            PluginRuntimeMode.IN_PROCESS -> startInProcess(entry, recordFailure)
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
        normalizeProcessState(entry)
    }

    suspend fun stopPluginLocked(
        entry: ManagedPlugin,
        normalizeProcessState: (ManagedPlugin) -> Unit,
        recordFailure: (ManagedPlugin, String, String) -> Unit
    ) {
        when (entry.lifecycleState) {
            PluginLifecycleState.REGISTERED -> {
                entry.lifecycleState = PluginLifecycleState.STOPPED
                normalizeProcessState(entry)
                return
            }
            PluginLifecycleState.STOPPED,
            PluginLifecycleState.DISPOSED,
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

    suspend fun disposePluginLocked(
        entry: ManagedPlugin,
        normalizeProcessState: (ManagedPlugin) -> Unit,
        recordFailure: (ManagedPlugin, String, String) -> Unit
    ) {
        if (entry.lifecycleState != PluginLifecycleState.DISPOSED) {
            stopPluginLocked(entry, normalizeProcessState, recordFailure)
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
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { entry.sourceClassLoader?.close() }
        }
        entry.sourceClassLoader = null
        entry.initialized = false
        entry.healthState = PluginHealthState.UNKNOWN
        entry.lifecycleState = PluginLifecycleState.DISPOSED
        normalizeProcessState(entry)
    }

    suspend fun restartPluginGenerationLocked(
        entry: ManagedPlugin,
        action: String,
        loadConfig: () -> PluginConfig,
        normalizeProcessState: (ManagedPlugin) -> Unit,
        recordFailure: (ManagedPlugin, String, String) -> Unit
    ) {
        logger.info("Lifecycle action=$action pluginId=${entry.plugin.descriptor.pluginId} generation=${entry.generation.value}")
        stopPluginLocked(entry, normalizeProcessState, recordFailure)
        disposePluginLocked(entry, normalizeProcessState, recordFailure)
        entry.config = loadConfig()
        entry.stickyCommunicationMode = null
        entry.generation = entry.generation.next()
        normalizeProcessState(entry)
        entry.lastFailure = null
        startPluginLocked(entry, normalizeProcessState, recordFailure)
    }

    suspend fun attemptProcessRecovery(
        entry: ManagedPlugin,
        reason: String,
        suggestTcpFallback: Boolean,
        normalizeProcessState: (ManagedPlugin) -> Unit,
        recordFailure: (ManagedPlugin, String, String) -> Unit
    ) {
        if (entry.config.runtimeMode != PluginRuntimeMode.EXTERNAL_JVM) return
        if (!entry.recoveryInProgress.compareAndSet(false, true)) return
        try {
            if (shouldSkipRecovery(entry)) return
            if (entry.lifecycleState == PluginLifecycleState.STOPPING || entry.lifecycleState == PluginLifecycleState.DISPOSING || entry.lifecycleState == PluginLifecycleState.DISPOSED) {
                return
            }
            val policy = entry.config.recoveryPolicy
            val now = System.currentTimeMillis()
            val last = entry.lastRecoveryAtEpochMs
            if (last == null || now - last > policy.resetWindowMs) {
                entry.recoveryAttempts = 0
            }
            if (entry.recoveryAttempts >= policy.maxRestarts) {
                runCatching {
                    stopPluginLocked(entry, normalizeProcessState, recordFailure)
                }.onFailure { stopError ->
                    logger.warn("Failed to stop plugin while exhausting recovery budget pluginId=${entry.plugin.descriptor.pluginId}: ${stopError.message}")
                }
                entry.stickyCommunicationMode = null
                entry.lifecycleState = PluginLifecycleState.FAILED
                entry.processState = PluginProcessState.FAILED
                entry.healthState = PluginHealthState.UNREACHABLE
                recordFailure(entry, "recovery", "Recovery budget exhausted: $reason")
                return
            }

            entry.recoveryAttempts += 1
            entry.lastRecoveryAtEpochMs = now
            delay(computeRecoveryBackoffMs(policy, entry.recoveryAttempts))
            if (shouldSkipRecovery(entry)) return
            if (suggestTcpFallback) {
                entry.stickyCommunicationMode = JvmCommunicationMode.TCP
            }
            runCatching {
                stopPluginLocked(entry, normalizeProcessState, recordFailure)
                startPluginLocked(entry, normalizeProcessState, recordFailure, resetRecoveryBudget = false)
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

    fun recreateScope(entry: ManagedPlugin): PluginPrivateScopeHandle {
        closeScope(entry)
        return pluginScopeManager.createScope(
            pluginId = entry.plugin.descriptor.pluginId,
            config = entry.config,
            modules = entry.plugin.modules()
        ).also { entry.privateScopeHandle = it }
    }

    fun closeScope(entry: ManagedPlugin) {
        pluginScopeManager.closeScope(entry.plugin.descriptor.pluginId)
        entry.privateScopeHandle = null
    }

    fun normalizeProcessState(entry: ManagedPlugin) {
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

    private suspend fun startInProcess(
        entry: ManagedPlugin,
        recordFailure: (ManagedPlugin, String, String) -> Unit
    ) {
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
                    attemptProcessRecovery(
                        entry = entry,
                        reason = reason,
                        suggestTcpFallback = suggestTcpFallback,
                        normalizeProcessState = ::normalizeProcessState,
                        recordFailure = { managed, source, message -> managed.lastFailure = PluginFailureRecord(System.currentTimeMillis(), source, message) }
                    )
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
}
