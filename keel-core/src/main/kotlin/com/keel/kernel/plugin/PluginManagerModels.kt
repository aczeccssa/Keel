package com.keel.kernel.plugin

import com.keel.kernel.di.PluginPrivateScopeHandle
import com.keel.kernel.hotreload.DevReloadOutcome
import com.keel.kernel.isolation.PluginProcessSupervisor
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import org.koin.core.Koin
import org.koin.core.scope.Scope

internal data class PluginCapabilities(
    val routeDefinitions: List<PluginRouteDefinition>,
    val endpointById: Map<String, PluginEndpointDefinition<*, *>>,
    val topology: Set<String>,
    val sseByPath: Map<String, PluginSseDefinition>,
    val applicationInstallers: List<ApplicationKtorInstaller>,
    val serviceInstallers: List<ServiceKtorInstaller>
)

internal data class EntrySnapshot(
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

internal data class NewGeneration(
    val plugin: KeelPlugin,
    val classLoader: URLClassLoader,
    val routeDefinitions: List<PluginRouteDefinition>,
    val endpointById: Map<String, PluginEndpointDefinition<*, *>>,
    val topology: Set<String>,
    val sseByPath: Map<String, PluginSseDefinition>,
    val pluginApplicationInstallers: List<ApplicationKtorInstaller>,
    val pluginServiceRouteInstallers: List<ServiceKtorInstaller>
)

internal data class LoadResult(
    val generation: NewGeneration? = null,
    val error: String? = null
)

internal data class ManagedPlugin(
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

internal data class BasicPluginInitContext(
    override val pluginId: String,
    override val descriptor: PluginDescriptor,
    override val kernelKoin: Koin
) : PluginInitContext

internal data class BasicPluginRuntimeContext(
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

internal object PluginReloadCompatibility {
    data class KtorScopeSignature(
        val applicationPluginKeys: List<String>,
        val servicePluginKeys: List<String>
    )

    data class ReloadCompatibilityDecision(
        val outcome: DevReloadOutcome,
        val message: String
    )

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
