package com.keel.kernel.loader

import com.keel.kernel.plugin.KPlugin

/**
 * Discovers and loads plugins dynamically at runtime.
 * Supports directory-based discovery and SPI-based loading.
 */
interface PluginLoader {

    /**
     * Discover plugins in the specified directory.
     * @param pluginDir Directory containing plugin JARs
     * @return List of discovered plugin metadata
     */
    suspend fun discoverPlugins(pluginDir: String): List<DiscoveredPlugin>

    /**
     * Load a discovered plugin into the runtime.
     * @param discovered Plugin metadata to load
     * @return Loaded KPlugin instance
     */
    suspend fun loadPlugin(discovered: DiscoveredPlugin): KPlugin

    /**
     * Unload a plugin from the runtime.
     * @param pluginId ID of the plugin to unload
     */
    suspend fun unloadPlugin(pluginId: String)

    /**
     * Reload a plugin (unload and load again).
     * @param pluginId ID of the plugin to reload
     * @return Reloaded KPlugin instance
     */
    suspend fun reloadPlugin(pluginId: String): KPlugin?
}

/**
 * Represents a discovered plugin from the filesystem.
 */
data class DiscoveredPlugin(
    val pluginId: String,
    val version: String,
    val mainClass: String,
    val jarPath: String,
    val dependencies: List<String> = emptyList(),
    val pluginClassLoader: ClassLoader? = null
)

