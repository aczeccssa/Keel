package com.keel.kernel.config

import com.keel.kernel.logging.KeelLoggerService
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
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

/**
 * Development-time file watcher for config files, plugin artifacts, and module directories.
 *
 * Hot-update guarantees are intentionally scoped:
 * - Config changes under `config/` and `config/plugins/` can reload plugin runtime config.
 * - Plugin artifact changes under `plugins/` can replace/dispose legacy jar-based plugins.
 * - Source/resource changes under watched module roots emit module change events so the kernel can
 *   decide whether a safe hot update is possible or a full restart is required.
 */
class ConfigHotReloader private constructor(
    private val watchDirectories: List<WatchDirectoryRegistration>,
    private val fileFilters: List<(String) -> Boolean>,
    private val onConfigChange: (ConfigChangeEvent) -> Unit,
    private val onPluginChange: (PluginChangeEvent) -> Unit,
    private val onModuleChange: (ModuleChangeEvent) -> Unit
) {
    private val logger = KeelLoggerService.getLogger("ConfigHotReloader")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isWatching = MutableStateFlow(false)
    val isWatching: StateFlow<Boolean> = _isWatching.asStateFlow()

    private val _lastReloadTime = MutableStateFlow(0L)
    val lastReloadTime: StateFlow<Long> = _lastReloadTime.asStateFlow()

    private val _configChanges = MutableSharedFlow<ConfigChangeEvent>()
    val configChanges: SharedFlow<ConfigChangeEvent> = _configChanges.asSharedFlow()

    private val _pluginChanges = MutableSharedFlow<PluginChangeEvent>()
    val pluginChanges: SharedFlow<PluginChangeEvent> = _pluginChanges.asSharedFlow()

    private val _moduleChanges = MutableSharedFlow<ModuleChangeEvent>()
    val moduleChanges: SharedFlow<ModuleChangeEvent> = _moduleChanges.asSharedFlow()

    private val watchers = mutableListOf<WatchService>()
    private val watchJobs = mutableListOf<kotlinx.coroutines.Job>()
    private val watchedPaths = mutableMapOf<WatchKey, WatchedDirectory>()

    private val configFiles = ConcurrentHashMap<String, Long>()
    private val pluginFiles = ConcurrentHashMap<String, Long>()
    private val moduleFiles = ConcurrentHashMap<String, Long>()

    fun startWatching() {
        if (_isWatching.value) return

        scope.launch {
            try {
                watchDirectories.forEach { registration ->
                    val path = registration.root
                    if (ensureWatchRootExists(registration)) {
                        startWatchingDirectory(registration)
                    } else {
                        logger.warn("Watch directory does not exist: ${registration.root}")
                    }
                }

                if (watchers.isEmpty()) {
                    logger.warn("No directories to watch")
                    return@launch
                }

                _isWatching.value = true
                logger.info("Started watching ${watchers.size} directories")

                watchers.forEach { watcher ->
                    watchJobs += scope.launch {
                        watchLoop(watcher)
                    }
                }
            } catch (error: Exception) {
                logger.error("Error starting hot reloader: ${error.message}", error)
                _isWatching.value = false
            }
        }
    }

    private fun ensureWatchRootExists(registration: WatchDirectoryRegistration): Boolean {
        val path = registration.root
        if (Files.exists(path)) {
            return true
        }
        return when (registration.kind) {
            WatchDirectoryKind.CONFIG,
            WatchDirectoryKind.PLUGIN_ARTIFACT -> {
                runCatching {
                    Files.createDirectories(path)
                }.onFailure { error ->
                    logger.warn("Failed to create watch directory ${registration.root}: ${error.message}")
                }.isSuccess
            }
            WatchDirectoryKind.MODULE,
            WatchDirectoryKind.MANUAL -> false
        }
    }

    fun stopWatching() {
        _isWatching.value = false
        watchJobs.forEach { it.cancel() }
        watchJobs.clear()
        watchers.forEach { watcher ->
            try {
                watcher.close()
            } catch (error: Exception) {
                logger.warn("Error closing watcher", error)
            }
        }
        watchers.clear()
        watchedPaths.clear()
        logger.info("Stopped watching all directories")
    }

    fun reload() {
        scope.launch {
            try {
                val event = ConfigChangeEvent(
                    type = ConfigChangeType.RELOADED.name,
                    fileName = "manual",
                    filePath = "manual"
                )
                _lastReloadTime.value = Clock.System.now().toEpochMilliseconds()
                onConfigChange(event)
                _configChanges.emit(event)
                logger.info("Configuration reloaded manually")
            } catch (error: Exception) {
                logger.error("Error reloading configuration: ${error.message}", error)
            }
        }
    }

    fun reloadPlugin(pluginId: String) {
        scope.launch {
            val event = PluginChangeEvent(
                type = PluginChangeType.RELOADED,
                pluginId = pluginId,
                filePath = ""
            )
            _pluginChanges.emit(event)
            onPluginChange(event)
            logger.info("Plugin reload requested: $pluginId")
        }
    }

    private fun startWatchingDirectory(registration: WatchDirectoryRegistration) {
        val watchService = FileSystems.getDefault().newWatchService()
        watchers += watchService
        registerRecursively(registration.root, watchService, registration)
        logger.info("Watching ${registration.kind.name.lowercase()} directory: ${registration.root}")
    }

    private fun registerRecursively(
        root: Path,
        watchService: WatchService,
        registration: WatchDirectoryRegistration
    ) {
        if (!Files.isDirectory(root)) {
            return
        }
        Files.walk(root).use { paths ->
            paths.filter(Files::isDirectory).forEach { directory ->
                val key = directory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE
                )
                watchedPaths[key] = WatchedDirectory(directory = directory, registration = registration)
            }
        }
    }

    private suspend fun watchLoop(watcher: WatchService) {
        while (_isWatching.value) {
            try {
                val key = watcher.take()
                val watchedDirectory = watchedPaths[key] ?: run {
                    key.reset()
                    continue
                }

                key.pollEvents().forEach { event ->
                    val fileName = event.context()?.toString() ?: return@forEach
                    val fullPath = watchedDirectory.directory.resolve(fileName).normalize()

                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
                        registerRecursively(fullPath, watcher, watchedDirectory.registration)
                        processExistingDirectoryContents(fullPath, watchedDirectory.registration)
                    }

                    when (watchedDirectory.registration.kind) {
                        WatchDirectoryKind.CONFIG -> {
                            if (shouldWatchFile(fileName)) {
                                handleConfigChange(
                                    fullPath = fullPath,
                                    registration = watchedDirectory.registration
                                )
                            }
                        }
                        WatchDirectoryKind.PLUGIN_ARTIFACT -> {
                            if (isPluginFile(fileName)) {
                                handlePluginChange(
                                    fullPath = fullPath,
                                    registration = watchedDirectory.registration
                                )
                            }
                        }
                        WatchDirectoryKind.MODULE,
                        WatchDirectoryKind.MANUAL -> {
                            handleModuleChange(
                                fullPath = fullPath,
                                registration = watchedDirectory.registration
                            )
                        }
                    }
                }
                key.reset()
            } catch (_: InterruptedException) {
                break
            } catch (_: ClosedWatchServiceException) {
                break
            } catch (error: Exception) {
                logger.error("Error in watch loop: ${error.message}", error)
            }
        }
    }

    private fun shouldWatchFile(fileName: String): Boolean {
        return fileFilters.isEmpty() || fileFilters.any { it(fileName) }
    }

    private fun isPluginFile(fileName: String): Boolean = fileName.endsWith(".jar")

    private suspend fun handleConfigChange(
        fullPath: Path,
        registration: WatchDirectoryRegistration
    ) {
        delay(200)

        val absolutePath = fullPath.toString()
        val file = fullPath.toFile()
        val fileName = file.name
        if (!file.exists()) {
            val event = ConfigChangeEvent(
                type = ConfigChangeType.DELETED.name,
                fileName = fileName,
                filePath = absolutePath,
                watchRoot = registration.root.toString()
            )
            _configChanges.emit(event)
            onConfigChange(event)
            configFiles.remove(absolutePath)
            logger.info("Config file deleted: $absolutePath")
            return
        }

        val lastModified = file.lastModified()
        val previousModified = configFiles[absolutePath]
        if (previousModified == null || lastModified > previousModified) {
            configFiles[absolutePath] = lastModified
            val changeType = if (previousModified == null) ConfigChangeType.CREATED else ConfigChangeType.MODIFIED
            val event = ConfigChangeEvent(
                type = changeType.name,
                fileName = fileName,
                filePath = absolutePath,
                watchRoot = registration.root.toString()
            )
            _configChanges.emit(event)
            onConfigChange(event)
            _lastReloadTime.value = Clock.System.now().toEpochMilliseconds()
            logger.info("Config file changed: $absolutePath (${changeType.name})")
        }
    }

    private suspend fun handlePluginChange(
        fullPath: Path,
        registration: WatchDirectoryRegistration
    ) {
        val absolutePath = fullPath.toString()
        val file = fullPath.toFile()
        val fileName = file.name
        val isDeleted = !file.exists()
        val lastModified = if (!isDeleted) file.lastModified() else 0L
        val previousModified = pluginFiles[absolutePath]

        val changeType = when {
            isDeleted -> {
                if (previousModified != null) {
                    pluginFiles.remove(absolutePath)
                    PluginChangeType.DELETED
                } else {
                    null
                }
            }
            previousModified == null -> {
                pluginFiles[absolutePath] = lastModified
                PluginChangeType.CREATED
            }
            lastModified > previousModified -> {
                pluginFiles[absolutePath] = lastModified
                PluginChangeType.MODIFIED
            }
            else -> null
        }

        if (changeType != null) {
            val pluginId = fileName.removeSuffix(".jar")
            val event = PluginChangeEvent(
                type = changeType,
                pluginId = pluginId,
                filePath = absolutePath,
                watchRoot = registration.root.toString()
            )
            _pluginChanges.emit(event)
            onPluginChange(event)
            logger.info("Plugin artifact changed: $absolutePath (${changeType.name})")
        }
    }

    private suspend fun handleModuleChange(
        fullPath: Path,
        registration: WatchDirectoryRegistration
    ) {
        delay(100)

        val absolutePath = fullPath.toString()
        val file = fullPath.toFile()
        val fileName = file.name
        val isDeleted = !file.exists()
        val lastModified = if (!isDeleted) file.lastModified() else 0L
        val previousModified = moduleFiles[absolutePath]

        val changeType = when {
            isDeleted -> {
                if (previousModified != null) {
                    moduleFiles.remove(absolutePath)
                    ModuleChangeType.DELETED
                } else {
                    null
                }
            }
            previousModified == null -> {
                moduleFiles[absolutePath] = lastModified
                ModuleChangeType.CREATED
            }
            lastModified > previousModified -> {
                moduleFiles[absolutePath] = lastModified
                ModuleChangeType.MODIFIED
            }
            else -> null
        }

        if (changeType != null) {
            val relativePath = registration.root.relativize(fullPath).toString()
                .replace(File.separatorChar, '/')
            val event = ModuleChangeEvent(
                type = changeType,
                fileName = fileName,
                fullPath = absolutePath,
                moduleRoot = registration.root.toString(),
                relativePath = relativePath
            )
            _moduleChanges.emit(event)
            onModuleChange(event)
            logger.info("Module file changed: $absolutePath (${changeType.name})")
        }
    }

    private suspend fun processExistingDirectoryContents(
        root: Path,
        registration: WatchDirectoryRegistration
    ) {
        if (!Files.isDirectory(root)) {
            return
        }
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).forEach { file ->
                when (registration.kind) {
                    WatchDirectoryKind.CONFIG -> {
                        if (shouldWatchFile(file.fileName.toString())) {
                            scope.launch {
                                handleConfigChange(file, registration)
                            }
                        }
                    }
                    WatchDirectoryKind.PLUGIN_ARTIFACT -> {
                        if (isPluginFile(file.fileName.toString())) {
                            scope.launch {
                                handlePluginChange(file, registration)
                            }
                        }
                    }
                    WatchDirectoryKind.MODULE,
                    WatchDirectoryKind.MANUAL -> {
                        scope.launch {
                            handleModuleChange(file, registration)
                        }
                    }
                }
            }
        }
    }

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

    class Builder {
        private val watchDirectories = linkedMapOf<String, WatchDirectoryRegistration>()
        private val fileFilters = mutableListOf<(String) -> Boolean>()
        private var onConfigChange: (ConfigChangeEvent) -> Unit = {}
        private var onPluginChange: (PluginChangeEvent) -> Unit = {}
        private var onModuleChange: (ModuleChangeEvent) -> Unit = {}

        fun watchConfigDir(dir: String) = addWatchDirectory(dir, WatchDirectoryKind.CONFIG)

        fun watchPluginDir(dir: String) = addWatchDirectory(dir, WatchDirectoryKind.PLUGIN_ARTIFACT)

        fun watchModuleDir(dir: String) = addWatchDirectory(dir, WatchDirectoryKind.MODULE)

        fun watchModuleDirectories(dirs: Iterable<String>) = apply {
            dirs.forEach(::watchModuleDir)
        }

        fun watchDirectory(dir: String) = addWatchDirectory(dir, WatchDirectoryKind.MANUAL)

        fun watchDirectories(dirs: Iterable<String>) = apply {
            dirs.forEach(::watchDirectory)
        }

        fun watchDirectories(vararg dirs: String) = watchDirectories(dirs.asIterable())

        fun addFileFilter(filter: (String) -> Boolean) = apply { fileFilters += filter }

        fun onConfigChange(callback: (ConfigChangeEvent) -> Unit) = apply { onConfigChange = callback }

        fun onPluginChange(callback: (PluginChangeEvent) -> Unit) = apply { onPluginChange = callback }

        fun onModuleChange(callback: (ModuleChangeEvent) -> Unit) = apply { onModuleChange = callback }

        fun build(): ConfigHotReloader {
            return ConfigHotReloader(
                watchDirectories = watchDirectories.values.toList(),
                fileFilters = fileFilters.toList(),
                onConfigChange = onConfigChange,
                onPluginChange = onPluginChange,
                onModuleChange = onModuleChange
            )
        }

        private fun addWatchDirectory(dir: String, kind: WatchDirectoryKind) = apply {
            val normalized = dir.trim()
            if (normalized.isEmpty()) {
                return@apply
            }
            val root = File(normalized).toPath().toAbsolutePath().normalize()
            val key = "${kind.name}:${root}"
            watchDirectories[key] = WatchDirectoryRegistration(root = root, kind = kind)
        }
    }
}

internal data class WatchDirectoryRegistration(
    val root: Path,
    val kind: WatchDirectoryKind
)

internal data class WatchedDirectory(
    val directory: Path,
    val registration: WatchDirectoryRegistration
)

internal enum class WatchDirectoryKind {
    CONFIG,
    PLUGIN_ARTIFACT,
    MODULE,
    MANUAL
}

data class ConfigChangeEvent(
    val type: String,
    val fileName: String,
    val filePath: String = fileName,
    val watchRoot: String? = null,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

data class PluginChangeEvent(
    val type: PluginChangeType,
    val pluginId: String,
    val filePath: String,
    val watchRoot: String? = null,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

data class ModuleChangeEvent(
    val type: ModuleChangeType,
    val fileName: String,
    val fullPath: String,
    val moduleRoot: String,
    val relativePath: String,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

enum class PluginChangeType {
    CREATED,
    MODIFIED,
    DELETED,
    RELOADED
}

enum class ConfigChangeType {
    CREATED,
    MODIFIED,
    DELETED,
    RELOADED
}

enum class ModuleChangeType {
    CREATED,
    MODIFIED,
    DELETED
}
