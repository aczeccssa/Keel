package com.keel.kernel.di

import org.koin.core.Koin
import org.koin.core.scope.Scope
import org.koin.core.qualifier.named
import com.keel.kernel.logging.KeelLoggerService
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Koin scopes for plugin isolation.
 * Each plugin gets its own private scope that can be destroyed when the plugin is disabled.
 */
class PluginScopeManager(
    private val koin: Koin
) {
    private val logger = KeelLoggerService.getLogger("PluginScopeManager")
    private val activeScopes = ConcurrentHashMap<String, Scope>()

    /**
     * Create a new scope for a plugin.
     * Uses getOrCreateScope which safely creates or retrieves an existing scope.
     */
    fun createScope(pluginId: String): Scope {
        val scope = koin.getOrCreateScope(pluginId, named(pluginId))
        activeScopes[pluginId] = scope
        logger.info("Created Koin scope for plugin: $pluginId")
        return scope
    }

    /**
     * Close and remove a plugin's scope.
     */
    fun closeScope(pluginId: String) {
        try {
            val scope = activeScopes.remove(pluginId)
            scope?.close()
            logger.info("Closed Koin scope for plugin: $pluginId")
        } catch (e: Exception) {
            logger.warn("Failed to close scope for plugin $pluginId: ${e.message}")
        }
    }
}
