package com.keel.kernel.plugin

import io.ktor.server.routing.Routing
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.config.KeelConstants
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * Manages the lifecycle of all plugins in the Keel kernel.
 * Handles plugin registration, state transitions, and routing.
 */
class PluginManager(
    private val koin: Koin
) : PluginAvailability {
    private val logger = KeelLoggerService.getLogger("PluginManager")

    private val plugins = ConcurrentHashMap<String, KPlugin>()
    private val pluginStates = ConcurrentHashMap<String, PluginState>()
    private val pluginScopes = ConcurrentHashMap<String, Scope>()

    /**
     * Register a plugin with the manager.
     * The plugin starts in INIT state.
     */
    fun registerPlugin(plugin: KPlugin) {
        if (plugins.containsKey(plugin.pluginId)) {
            logger.warn("Plugin already registered pluginId=${plugin.pluginId}")
            return
        }
        plugins[plugin.pluginId] = plugin
        pluginStates[plugin.pluginId] = PluginState.INIT
        logger.info("Registered plugin pluginId=${plugin.pluginId} version=${plugin.version}")
    }

    /**
     * Initialize a plugin (call onInit).
     * This is typically called during kernel startup.
     */
    suspend fun initPlugin(pluginId: String) {
        val plugin = plugins[pluginId] ?: throw IllegalArgumentException("Plugin $pluginId not found")
        val context = DefaultPluginInitContext(pluginId, getOrCreateConfig(pluginId), koin)
        plugin.onInit(context)
        pluginStates[pluginId] = PluginState.INSTALLED
        logger.info("Initialized plugin pluginId=$pluginId")
    }

    /**
     * Install a plugin (call onInstall).
     * This creates the plugin's private Koin scope.
     */
    suspend fun installPlugin(pluginId: String, scope: Scope) {
        val plugin = plugins[pluginId] ?: throw IllegalArgumentException("Plugin $pluginId not found")
        pluginScopes[pluginId] = scope
        plugin.onInstall(scope)
        logger.info("Installed plugin pluginId=$pluginId")
    }

    /**
     * Enable a plugin (call onEnable).
     * This mounts the plugin's routes.
     * If re-enabling a disabled plugin, recreates the Koin scope and calls onInstall first.
     */
    suspend fun enablePlugin(pluginId: String, routing: Routing) {
        val plugin = plugins[pluginId] ?: throw IllegalArgumentException("Plugin $pluginId not found")

        // If re-enabling a disabled plugin, recreate the scope and call onInstall
        if (pluginStates[pluginId] == PluginState.DISABLED) {
            val scope = koin.getOrCreateScope(pluginId, named(pluginId))
            pluginScopes[pluginId] = scope
            plugin.onInstall(scope)
            pluginStates[pluginId] = PluginState.INSTALLED
            logger.info("Re-installed plugin scope pluginId=$pluginId")
        }

        plugin.onEnable(routing)
        pluginStates[pluginId] = PluginState.ENABLED
        logger.info("Enabled plugin pluginId=$pluginId")
    }

    /**
     * Disable a plugin (call onDisable).
     * This removes the plugin's routes and releases resources.
     */
    suspend fun disablePlugin(pluginId: String) {
        val plugin = plugins[pluginId] ?: throw IllegalArgumentException("Plugin $pluginId not found")
        plugin.onDisable()
        pluginScopes[pluginId]?.close()
        pluginScopes.remove(pluginId)
        pluginStates[pluginId] = PluginState.DISABLED
        logger.info("Disabled plugin pluginId=$pluginId")
    }

    /**
     * Get the state of a specific plugin.
     */
    fun getPluginState(pluginId: String): PluginState {
        return pluginStates[pluginId] ?: PluginState.INIT
    }

    /**
     * Check if a plugin is currently enabled.
     */
    override fun isPluginEnabled(pluginId: String): Boolean {
        return pluginStates[pluginId] == PluginState.ENABLED
    }

    /**
     * Get all registered plugins.
     */
    fun getAllPlugins(): Map<String, KPlugin> = plugins.toMap()

    /**
     * Get plugin by ID.
     */
    fun getPlugin(pluginId: String): KPlugin? = plugins[pluginId]

    private fun getOrCreateConfig(pluginId: String): PluginConfig {
        val configDir = File(KeelConstants.CONFIG_DIR, "plugins")
        val configFile = File(configDir, "$pluginId.json")

        if (!configFile.exists()) {
            return PluginConfig(pluginId = pluginId, enabled = false)
        }

        return try {
            val json = Json { ignoreUnknownKeys = true }
            val parsed = json.decodeFromString<PluginConfig>(configFile.readText())
            if (parsed.pluginId.isBlank()) {
                parsed.copy(pluginId = pluginId)
            } else {
                parsed
            }
        } catch (e: Exception) {
            logger.warn("Failed to load plugin config pluginId=$pluginId error=${e.message}")
            PluginConfig(pluginId = pluginId, enabled = false)
        }
    }

    private class DefaultPluginInitContext(
        override val pluginId: String,
        override val config: PluginConfig,
        override val koin: Koin
    ) : PluginInitContext
}
