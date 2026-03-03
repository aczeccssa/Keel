package com.keel.kernel.di

import com.keel.kernel.plugin.PluginConfig
import com.keel.kernel.plugin.PluginTeardownRegistry
import com.keel.kernel.logging.KeelLoggerService
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.core.qualifier.named
import java.util.concurrent.ConcurrentHashMap
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Manages Koin scopes for plugin isolation.
 * Each plugin gets its own private scope that can be destroyed when the plugin is disabled.
 */
class PluginScopeManager(
    private val koin: Koin
) {
    private val logger = KeelLoggerService.getLogger("PluginScopeManager")
    private val activeScopes = ConcurrentHashMap<String, PluginPrivateScopeHandle>()

    /**
     * Create a new isolated Koin application and private scope for a plugin generation.
     */
    fun createScope(
        pluginId: String,
        config: PluginConfig,
        modules: List<Module>
    ): PluginPrivateScopeHandle {
        closeScope(pluginId)
        val pluginApplication = koinApplication {
            modules(
                module {
                    single { config }
                    single(named("kernelKoin")) { koin }
                },
                *modules.toTypedArray()
            )
        }
        val scope = pluginApplication.koin.getOrCreateScope(pluginId, named(pluginId))
        val handle = PluginPrivateScopeHandle(
            pluginId = pluginId,
            koinApplication = pluginApplication,
            privateScope = scope,
            teardownRegistry = PluginTeardownRegistry()
        )
        activeScopes[pluginId] = handle
        logger.info("Created Koin scope for plugin: $pluginId")
        return handle
    }

    /**
     * Close and remove a plugin's scope.
     */
    fun closeScope(pluginId: String) {
        try {
            val handle = activeScopes.remove(pluginId)
            handle?.teardownRegistry?.runAll()
            handle?.privateScope?.close()
            handle?.koinApplication?.close()
            if (handle != null) {
                logger.info("Closed Koin scope for plugin: $pluginId")
            }
        } catch (e: Exception) {
            logger.warn("Failed to close scope for plugin $pluginId: ${e.message}")
        }
    }

    fun getScope(pluginId: String): PluginPrivateScopeHandle? = activeScopes[pluginId]
}

data class PluginPrivateScopeHandle(
    val pluginId: String,
    val koinApplication: KoinApplication,
    val privateScope: Scope,
    val teardownRegistry: PluginTeardownRegistry
)
