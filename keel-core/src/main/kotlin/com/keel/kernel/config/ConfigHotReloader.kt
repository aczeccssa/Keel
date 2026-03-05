package com.keel.kernel.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import com.keel.kernel.logging.KeelLoggerService
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced Configuration and Plugin Hot-Reloader.
 *
 * Features:
 * - Monitors configuration files (YAML/JSON) for changes
 * - Monitors plugins directory for JAR file changes
 * - Supports multiple watch directories
 * - Configurable file type filtering
 * - Plugin reload callbacks
 *
 * Usage:
 * ```kotlin
 * val reloader = ConfigHotReloader.Builder()
 *     .watchConfigDir("config")
 *     .watchPluginDir("plugins")
 *     .onConfigChange { event -> ... }
 *     .onPluginChange { pluginId -> ... }
 *     .build()
 * reloader.startWatching()
 * ```
 */
class ConfigHotReloader private constructor(
    private val configDir: String?,
    private val pluginDir: String?,
    private val additionalWatchDirs: List<String>,
    private val fileFilters: List<(String) -> Boolean>,
    private val onConfigChange: (ConfigChangeEvent) -> Unit,
    private val onPluginChange: (PluginChangeEvent) -> Unit
) {
    private val logger = KeelLoggerService.getLogger("ConfigHotReloader")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val configDirPath: Path? = configDir?.let { File(it).toPath().toAbsolutePath().normalize() }
    private val pluginDirPath: Path? = pluginDir?.let { File(it).toPath().toAbsolutePath().normalize() }

    private val _isWatching = MutableStateFlow(false)
    val isWatching: StateFlow<Boolean> = _isWatching.asStateFlow()

    private val _lastReloadTime = MutableStateFlow(0L)
    val lastReloadTime: StateFlow<Long> = _lastReloadTime.asStateFlow()

    private val _configChanges = MutableSharedFlow<ConfigChangeEvent>()
    val configChanges: SharedFlow<ConfigChangeEvent> = _configChanges.asSharedFlow()

    private val _pluginChanges = MutableSharedFlow<PluginChangeEvent>()
    val pluginChanges: SharedFlow<PluginChangeEvent> = _pluginChanges.asSharedFlow()

    private val watchers = mutableListOf<WatchService>()
    private val watchJobs = mutableListOf<kotlinx.coroutines.Job>()
    private val watchedPaths = mutableMapOf<WatchKey, Path>()
    private val configFiles = ConcurrentHashMap<String, Long>()
    private val pluginFiles = ConcurrentHashMap<String, Long>()

    /**
     * Start watching all configured directories.
     */
    fun startWatching() {
        if (_isWatching.value) return

        scope.launch {
            try {
                // Watch config directory
                configDir?.let { dir ->
                    if (File(dir).exists()) {
                        startWatchingDirectory(dir, WatchType.CONFIG)
                    } else {
                        logger.warn("Config directory does not exist: $dir")
                    }
                }

                // Watch plugin directory
                pluginDir?.let { dir ->
                    if (File(dir).exists()) {
                        startWatchingDirectory(dir, WatchType.PLUGIN)
                    } else {
                        logger.warn("Plugin directory does not exist: $dir")
                    }
                }

                // Watch additional directories
                additionalWatchDirs.forEach { dir ->
                    if (File(dir).exists()) {
                        startWatchingDirectory(dir, WatchType.CUSTOM)
                    } else {
                        logger.warn("Additional watch directory does not exist: $dir")
                    }
                }

                if (watchers.isEmpty()) {
                    logger.warn("No directories to watch")
                    return@launch
                }

                _isWatching.value = true
                logger.info("Started watching ${watchers.size} directories")

                // Start one watch loop per WatchService so all directories are serviced.
                watchers.forEach { watcher ->
                    watchJobs.add(
                        scope.launch {
                            watchLoop(watcher)
                        }
                    )
                }
            } catch (e: Exception) {
                logger.error("Error starting hot reloader: ${e.message}", e)
                _isWatching.value = false
            }
        }
    }

    /**
     * Stop watching all directories.
     */
    fun stopWatching() {
        _isWatching.value = false
        watchJobs.forEach { it.cancel() }
        watchJobs.clear()
        watchers.forEach { watcher ->
            try {
                watcher.close()
            } catch (e: Exception) {
                logger.warn("Error closing watcher", e)
            }
        }
        watchers.clear()
        watchedPaths.clear()
        logger.info("Stopped watching all directories")
    }

    /**
     * Manually trigger a configuration reload.
     */
    fun reload() {
        scope.launch {
            try {
                _lastReloadTime.value = Clock.System.now().toEpochMilliseconds()
                onConfigChange(ConfigChangeEvent(ConfigChangeType.RELOADED.name, "manual"))
                _configChanges.emit(ConfigChangeEvent(ConfigChangeType.RELOADED.name, "manual"))
                logger.info("Configuration reloaded manually")
            } catch (e: Exception) {
                logger.error("Error reloading configuration: ${e.message}", e)
            }
        }
    }

    /**
     * Reload a specific plugin by ID.
     */
    fun reloadPlugin(pluginId: String) {
        scope.launch {
            _pluginChanges.emit(PluginChangeEvent(PluginChangeType.RELOADED, pluginId, ""))
            onPluginChange(PluginChangeEvent(PluginChangeType.RELOADED, pluginId, ""))
            logger.info("Plugin reload requested: $pluginId")
        }
    }

    private fun startWatchingDirectory(dir: String, type: WatchType) {
        val path = File(dir).toPath().toAbsolutePath().normalize()
        val watchService = FileSystems.getDefault().newWatchService()

        val events = mutableListOf<WatchEvent.Kind<*>>(
            java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
            java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
            java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
        )

        val key = path.register(watchService, *events.toTypedArray())

        watchers.add(watchService)
        watchedPaths[key] = path

        logger.info("Watching $type directory: $dir")
    }

    private suspend fun watchLoop(watcher: WatchService) {
        while (_isWatching.value) {
            try {
                val key = watcher.take()
                val path = watchedPaths[key] ?: continue

                key.pollEvents().forEach { event ->
                    val fileName = event.context()?.toString() ?: return@forEach

                    // Determine the full path
                    val fullPath = path.resolve(fileName).normalize()

                    // Check which type of directory we're watching
                    val isConfigDir = configDirPath?.let { fullPath.startsWith(it) } ?: false
                    val isPluginDir = pluginDirPath?.let { fullPath.startsWith(it) } ?: false

                    when {
                        isConfigDir && shouldWatchFile(fileName) -> {
                            handleConfigChange(fileName, fullPath.toString())
                        }
                        isPluginDir && isPluginFile(fileName) -> {
                            handlePluginChange(fileName, fullPath.toString())
                        }
                    }
                }
                key.reset()
            } catch (e: InterruptedException) {
                // Normal when stopping
                break
            } catch (e: ClosedWatchServiceException) {
                // Watcher closed during shutdown
                break
            } catch (e: Exception) {
                logger.error("Error in watch loop: ${e.message}", e)
            }
        }
    }

    private fun shouldWatchFile(fileName: String): Boolean {
        return fileFilters.isEmpty() || fileFilters.any { it(fileName) }
    }

    private fun isPluginFile(fileName: String): Boolean {
        return fileName.endsWith(".jar")
    }

    private suspend fun handleConfigChange(fileName: String, fullPath: String) {
        delay(200) // Debounce

        val file = File(fullPath)
        if (!file.exists()) {
            val event = ConfigChangeEvent(ConfigChangeType.DELETED.name, fileName)
            _configChanges.emit(event)
            onConfigChange(event)
            configFiles.remove(fileName)
            logger.info("Config file deleted: $fileName")
            return
        }

        val lastModified = file.lastModified()
        val previousModified = configFiles[fileName]

        if (previousModified == null || lastModified > previousModified) {
            configFiles[fileName] = lastModified
            val changeType = if (previousModified == null) ConfigChangeType.CREATED else ConfigChangeType.MODIFIED
            val event = ConfigChangeEvent(changeType.name, fileName)
            _configChanges.emit(event)
            onConfigChange(event)
            _lastReloadTime.value = Clock.System.now().toEpochMilliseconds()
            logger.info("Config file changed: $fileName (${changeType.name})")
        }
    }

    private suspend fun handlePluginChange(fileName: String, fullPath: String) {
        val file = File(fullPath)
        val isDeleted = !file.exists()
        val lastModified = if (!isDeleted) file.lastModified() else 0L
        val previousModified = pluginFiles[fileName]

        // Determine change type
        val changeType = when {
            isDeleted -> {
                if (previousModified != null) {
                    pluginFiles.remove(fileName)
                    PluginChangeType.DELETED
                } else {
                    null
                }
            }
            previousModified == null -> {
                pluginFiles[fileName] = lastModified
                PluginChangeType.CREATED
            }
            lastModified > previousModified -> {
                pluginFiles[fileName] = lastModified
                PluginChangeType.MODIFIED
            }
            else -> null
        }

        if (changeType != null) {
            // Extract plugin ID from filename (e.g., "myplugin.jar" -> "myplugin")
            val pluginId = fileName.removeSuffix(".jar")

            val event = PluginChangeEvent(changeType, pluginId, fullPath)
            _pluginChanges.emit(event)
            onPluginChange(event)
            logger.info("Plugin file changed: $fileName (${changeType.name})")
        }
    }

    /**
     * Check if running in development mode.
     */
    companion object {
        fun isDevelopmentMode(): Boolean {
            val sysProp = System.getProperty(KeelConstants.ENV_SYSTEM_PROPERTY)
            val envVar = System.getenv(KeelConstants.ENV_ENV_VARIABLE)
            return (sysProp ?: envVar ?: KeelConstants.ENV_PRODUCTION) == KeelConstants.ENV_DEVELOPMENT
        }

        fun getServerPort(): Int {
            val sysProp = System.getProperty(KeelConstants.PORT_SYSTEM_PROPERTY)?.toIntOrNull()
            val envProp = System.getenv(KeelConstants.PORT_ENV_VARIABLE)?.toIntOrNull()
            return sysProp ?: envProp ?: KeelConstants.DEFAULT_SERVER_PORT
        }
    }

    /**
     * Builder for creating ConfigHotReloader instances.
     */
    class Builder {
        private var configDir: String? = null
        private var pluginDir: String? = null
        private val additionalWatchDirs = mutableListOf<String>()
        private val fileFilters = mutableListOf<(String) -> Boolean>()
        private var onConfigChange: (ConfigChangeEvent) -> Unit = {}
        private var onPluginChange: (PluginChangeEvent) -> Unit = {}

        fun watchConfigDir(dir: String) = apply { this.configDir = dir }

        fun watchPluginDir(dir: String) = apply { this.pluginDir = dir }

        fun watchAdditionalDir(dir: String) = apply { this.additionalWatchDirs.add(dir) }

        fun addFileFilter(filter: (String) -> Boolean) = apply { this.fileFilters.add(filter) }

        fun onConfigChange(callback: (ConfigChangeEvent) -> Unit) = apply { this.onConfigChange = callback }

        fun onPluginChange(callback: (PluginChangeEvent) -> Unit) = apply { this.onPluginChange = callback }

        fun build(): ConfigHotReloader {
            return ConfigHotReloader(
                configDir = configDir ?: KeelConstants.CONFIG_DIR,
                pluginDir = pluginDir,
                additionalWatchDirs = additionalWatchDirs.toList(),
                fileFilters = fileFilters.toList(),
                onConfigChange = onConfigChange,
                onPluginChange = onPluginChange
            )
        }
    }
}

/**
 * Type of directory being watched.
 */
private enum class WatchType {
    CONFIG,
    PLUGIN,
    CUSTOM
}

/**
 * Configuration change event.
 */
data class ConfigChangeEvent(
    val type: String,
    val fileName: String,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

/**
 * Plugin change event.
 */
data class PluginChangeEvent(
    val type: PluginChangeType,
    val pluginId: String,
    val filePath: String,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

/**
 * Type of plugin change.
 */
enum class PluginChangeType {
    CREATED,
    MODIFIED,
    DELETED,
    RELOADED
}

/**
 * Type of configuration change.
 */
enum class ConfigChangeType {
    CREATED,
    MODIFIED,
    DELETED,
    RELOADED
}
