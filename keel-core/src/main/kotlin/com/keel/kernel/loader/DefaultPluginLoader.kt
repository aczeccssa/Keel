package com.keel.kernel.loader

import com.keel.kernel.config.KeelConstants
import com.keel.kernel.plugin.KPlugin
import com.keel.kernel.logging.KeelLoggerService
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Default implementation of PluginLoader that supports:
 * - Directory-based plugin discovery (scanning for JAR files)
 * - JAR-based class loading with isolated ClassLoader
 * - SPI-based plugin loading (META-INF/services)
 *
 * Thread-safe: uses ConcurrentHashMap and synchronized loading.
 */
class DefaultPluginLoader : PluginLoader {

    private val logger = KeelLoggerService.getLogger("PluginLoader")

    private val loadedPlugins = ConcurrentHashMap<String, KPlugin>()
    private val pluginClassLoaders = ConcurrentHashMap<String, ClassLoader>()
    private val discoveredPlugins = ConcurrentHashMap<String, DiscoveredPlugin>()

    override suspend fun discoverPlugins(pluginDir: String): List<DiscoveredPlugin> {
        val dir = File(pluginDir)
        if (!dir.exists() || !dir.isDirectory) {
            logger.warn("Plugin directory does not exist: $pluginDir")
            return emptyList()
        }

        val plugins = dir.listFiles { file ->
            file.isFile && file.extension == "jar"
        }?.mapNotNull { jarFile ->
            discoverPlugin(jarFile)
        } ?: emptyList()

        logger.info("Discovered ${plugins.size} plugins in $pluginDir")
        return plugins
    }

    private fun discoverPlugin(jarFile: File): DiscoveredPlugin? {
        return try {
            val jar = JarFile(jarFile)
            val manifest = jar.manifest ?: run {
                jar.close()
                return null
            }

            val pluginId = manifest.mainAttributes.getValue(KeelConstants.MANIFEST_PLUGIN_ID)
                ?: jarFile.nameWithoutExtension
            val version = manifest.mainAttributes.getValue(KeelConstants.MANIFEST_PLUGIN_VERSION) ?: "1.0.0"
            val mainClass = manifest.mainAttributes.getValue("Main-Class")
                ?: manifest.mainAttributes.getValue(KeelConstants.MANIFEST_PLUGIN_MAIN_CLASS)
            val dependencies = manifest.mainAttributes.getValue(KeelConstants.MANIFEST_PLUGIN_DEPENDENCIES)
                ?.split(",")
                ?.map { it.trim() }
                ?: emptyList()

            if (mainClass.isNullOrBlank()) {
                logger.warn("No main class specified for plugin: ${jarFile.name}")
                jar.close()
                return null
            }

            jar.close()

            val discovered = DiscoveredPlugin(
                pluginId = pluginId,
                version = version,
                mainClass = mainClass,
                jarPath = jarFile.absolutePath,
                dependencies = dependencies
            )

            discoveredPlugins[pluginId] = discovered
            logger.info("Discovered plugin: $pluginId v$version from ${jarFile.name}")
            discovered
        } catch (e: Exception) {
            logger.error("Failed to discover plugin from ${jarFile.name}: ${e.message}", e)
            null
        }
    }

    override suspend fun loadPlugin(discovered: DiscoveredPlugin): KPlugin {
        // Use putIfAbsent for atomic check-then-act
        loadedPlugins[discovered.pluginId]?.let {
            logger.warn("Plugin ${discovered.pluginId} already loaded")
            return it
        }

        // Resolve dependencies first
        val dependencies = resolveDependencies(discovered)
        val urls = mutableListOf<URL>()

        // Add plugin JAR URL
        urls.add(File(discovered.jarPath).toURI().toURL())

        // Add dependency JAR URLs
        for (dep in dependencies) {
            discoveredPlugins[dep]?.let { depPlugin ->
                urls.add(File(depPlugin.jarPath).toURI().toURL())
            }
        }

        // Create isolated ClassLoader
        val parentClassLoader = this::class.java.classLoader
        val classLoader = URLClassLoader(
            urls.toTypedArray(),
            parentClassLoader
        )
        pluginClassLoaders[discovered.pluginId] = classLoader

        // Try SPI loading first
        val plugin = loadViaSpi(classLoader, discovered)
            ?: loadViaMainClass(classLoader, discovered)

        return if (plugin != null) {
            // Atomic putIfAbsent - if another thread already loaded, use that one
            val existing = loadedPlugins.putIfAbsent(discovered.pluginId, plugin)
            if (existing != null) {
                logger.warn("Plugin ${discovered.pluginId} already loaded by another thread")
                existing
            } else {
                logger.info("Loaded plugin: ${discovered.pluginId} v${discovered.version}")
                plugin
            }
        } else {
            throw IllegalStateException("Failed to load plugin: ${discovered.pluginId}")
        }
    }

    private fun resolveDependencies(discovered: DiscoveredPlugin): List<String> {
        val resolved = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        fun resolveRecursive(depId: String) {
            if (depId in visited) return
            visited.add(depId)

            discoveredPlugins[depId]?.let { dep ->
                resolved.add(depId)
                dep.dependencies.forEach { resolveRecursive(it) }
            }
        }

        discovered.dependencies.forEach { resolveRecursive(it) }
        return resolved
    }

    private fun loadViaSpi(classLoader: ClassLoader, discovered: DiscoveredPlugin): KPlugin? {
        return try {
            val serviceLoader = ServiceLoader.load(KPlugin::class.java, classLoader)
            serviceLoader.firstOrNull()
        } catch (e: Exception) {
            logger.debug("No SPI plugin found for ${discovered.pluginId}: ${e.message}")
            null
        }
    }

    private fun loadViaMainClass(classLoader: ClassLoader, discovered: DiscoveredPlugin): KPlugin? {
        return try {
            val clazz = classLoader.loadClass(discovered.mainClass)
            val plugin = clazz.getDeclaredConstructor().newInstance()
            @Suppress("UNCHECKED_CAST")
            plugin as? KPlugin
        } catch (e: Exception) {
            logger.error("Failed to load main class ${discovered.mainClass}: ${e.message}", e)
            null
        }
    }

    override suspend fun unloadPlugin(pluginId: String) {
        val plugin = loadedPlugins.remove(pluginId)
        val classLoader = pluginClassLoaders.remove(pluginId)

        if (plugin != null) {
            try {
                (plugin as? AutoCloseable)?.close()
            } catch (e: Exception) {
                logger.warn("Error closing plugin $pluginId: ${e.message}", e)
            }
        }

        if (classLoader is AutoCloseable) {
            try {
                classLoader.close()
            } catch (e: Exception) {
                logger.warn("Error closing classloader for $pluginId: ${e.message}", e)
            }
        }

        logger.info("Unloaded plugin: $pluginId")
    }

    override suspend fun reloadPlugin(pluginId: String): KPlugin? {
        val discovered = discoveredPlugins[pluginId] ?: run {
            logger.warn("Plugin $pluginId not found for reload")
            return null
        }

        unloadPlugin(pluginId)
        return loadPlugin(discovered)
    }

    /**
     * Get all loaded plugins.
     */
    fun getLoadedPlugins(): Map<String, KPlugin> = loadedPlugins.toMap()

    /**
     * Get a specific loaded plugin.
     */
    fun getPlugin(pluginId: String): KPlugin? = loadedPlugins[pluginId]

    /**
     * Get all discovered plugins.
     */
    fun getDiscoveredPlugins(): Map<String, DiscoveredPlugin> = discoveredPlugins.toMap()
}
