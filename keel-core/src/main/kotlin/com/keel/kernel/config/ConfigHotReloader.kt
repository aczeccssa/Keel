package com.keel.kernel.config

import com.keel.kernel.hotreload.PluginDevelopmentSource
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Development-time file watcher for module directories.
 *
 * Watches module source directories so the kernel can decide whether a safe
 * hot update is possible or a full restart is required.
 *
 * Legacy CONFIG and PLUGIN_ARTIFACT watching has been removed — the modern
 * hot-reload architecture uses [PluginDevelopmentSource] and Gradle-based
 * incremental builds instead of file-system polling for config/ and plugins/.
 */
class ConfigHotReloader private constructor(
    private val watchDirectories: List<WatchDirectoryRegistration>,
    private val fileFilters: List<(String) -> Boolean>,
    private val onModuleChange: (ModuleChangeEvent) -> Unit
) {
    private val logger = KeelLoggerService.getLogger("ConfigHotReloader")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isWatching = MutableStateFlow(false)
    @Suppress("unused")
    val isWatching: StateFlow<Boolean> = _isWatching.asStateFlow()

    private val _lastReloadTime = MutableStateFlow(0L)
    @Suppress("unused")
    val lastReloadTime: StateFlow<Long> = _lastReloadTime.asStateFlow()

    private val _moduleChanges = MutableSharedFlow<ModuleChangeEvent>()
    @Suppress("unused")
    val moduleChanges: SharedFlow<ModuleChangeEvent> = _moduleChanges.asSharedFlow()

    private val watchers = mutableListOf<WatchService>()
    private val watchJobs = mutableListOf<kotlinx.coroutines.Job>()
    private val watchedPaths = mutableMapOf<WatchKey, WatchedDirectory>()

    private val moduleFiles = ConcurrentHashMap<String, Long>()

    fun startWatching() {
        if (_isWatching.value) return

        scope.launch {
            try {
                watchDirectories.forEach { registration ->
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
        // Do NOT auto-create directories — just skip if missing.
        logger.debug("Watch directory does not exist, skipping: $path")
        return false
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
                val key = takeWatchKey(watcher)
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

                    if (!Files.isDirectory(fullPath) && !shouldWatchFile(fileName)) {
                        return@forEach
                    }

                    // Only MODULE and MANUAL kinds remain
                    handleModuleChange(
                        fullPath = fullPath,
                        registration = watchedDirectory.registration
                    )
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

    private suspend fun takeWatchKey(watcher: WatchService): WatchKey = suspendCancellableCoroutine { cont ->
        val job = scope.launch(Dispatchers.IO) {
            try {
                val key = watcher.take()
                if (cont.isActive) {
                    cont.resume(key)
                }
            } catch (error: Exception) {
                if (cont.isActive) {
                    cont.resumeWithException(error)
                }
            }
        }
        cont.invokeOnCancellation {
            watcher.close()
            job.cancel()
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
            lastModified != previousModified -> {
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
        withContext(Dispatchers.IO) {
            Files.walk(root)
        }.use { paths ->
            paths.filter(Files::isRegularFile).forEach { file ->
                if (!shouldWatchFile(file.fileName.toString())) {
                    return@forEach
                }
                scope.launch {
                    handleModuleChange(file, registration)
                }
            }
        }
    }

    private fun shouldWatchFile(fileName: String): Boolean {
        return fileFilters.isEmpty() || fileFilters.any { it(fileName) }
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
        private var onModuleChange: (ModuleChangeEvent) -> Unit = {}

        fun watchModuleDir(dir: String): Builder = addWatchDirectory(dir, WatchDirectoryKind.MODULE)

        fun watchModuleDirectories(dirs: Iterable<String>): Builder = apply {
            dirs.forEach(::watchModuleDir)
        }

        fun watchDirectory(dir: String): Builder = addWatchDirectory(dir, WatchDirectoryKind.MANUAL)

        fun watchDirectories(dirs: Iterable<String>): Builder = apply {
            dirs.forEach(::watchDirectory)
        }

        @Suppress("unused")
        fun watchDirectories(vararg dirs: String): Builder = watchDirectories(dirs.asIterable())

        @Suppress("unused")
        fun addFileFilter(filter: (String) -> Boolean): Builder = apply { fileFilters += filter }

        fun onModuleChange(callback: (ModuleChangeEvent) -> Unit): Builder = apply { onModuleChange = callback }

        fun build(): ConfigHotReloader {
            return ConfigHotReloader(
                watchDirectories = watchDirectories.values.toList(),
                fileFilters = fileFilters.toList(),
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
    MODULE,
    MANUAL
}

data class ModuleChangeEvent(
    val type: ModuleChangeType,
    val fileName: String,
    val fullPath: String,
    val moduleRoot: String,
    val relativePath: String,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

enum class ModuleChangeType {
    CREATED,
    MODIFIED,
    DELETED
}
