package com.keel.kernel.hotreload

import com.keel.kernel.config.ModuleChangeEvent
import com.keel.kernel.logging.KeelLoggerService
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultModuleChangeClassifier : ModuleChangeClassifier {
    override fun classify(event: ModuleChangeEvent): ClassifiedModuleChange {
        val normalized = event.relativePath.replace('\\', '/')
        if (normalized == "build.gradle.kts" || normalized == "settings.gradle.kts" || normalized == "gradle.properties") {
            return ClassifiedModuleChange(
                scope = ChangeScope.BUILD_LOGIC,
                modulePath = event.moduleRoot,
                restartRequiredReason = "Build logic changed"
            )
        }
        if (event.moduleRoot.replace('\\', '/').contains("/keel-core")) {
            return ClassifiedModuleChange(
                scope = ChangeScope.KERNEL_SOURCE,
                modulePath = event.moduleRoot,
                restartRequiredReason = "Kernel module source changed"
            )
        }
        if (normalized.startsWith("build/")) {
            return ClassifiedModuleChange(ChangeScope.UNKNOWN, event.moduleRoot)
        }
        return when {
            normalized.startsWith("src/main/resources") || normalized.startsWith("src/commonMain/resources") -> {
                ClassifiedModuleChange(ChangeScope.PLUGIN_RESOURCE, event.moduleRoot)
            }
            normalized.startsWith("src/main/") || normalized.startsWith("src/commonMain") -> {
                ClassifiedModuleChange(ChangeScope.PLUGIN_SOURCE, event.moduleRoot)
            }
            else -> ClassifiedModuleChange(ChangeScope.UNKNOWN, event.moduleRoot)
        }
    }
}

class DefaultPluginImpactAnalyzer(
    private val repoRoot: File
) : PluginImpactAnalyzer {
    private val ownershipByPlugin = ConcurrentHashMap<String, PluginOwnership>()
    private val pluginsByModule = ConcurrentHashMap<String, MutableSet<String>>()

    fun registerOwnership(source: PluginDevelopmentSource) {
        val ownership = resolveOwnership(repoRoot, source)
        ownershipByPlugin[source.pluginId] = ownership
        ownership.dependentModulePaths.forEach { modulePath ->
            pluginsByModule.computeIfAbsent(modulePath) { linkedSetOf() }.add(source.pluginId)
        }
    }

    override fun ownershipOf(pluginId: String): PluginOwnership? = ownershipByPlugin[pluginId]

    override fun affectedPlugins(modulePath: String): Set<String> {
        val normalized = normalizeModulePath(repoRoot, modulePath)
        val direct = pluginsByModule[normalized]?.toSet().orEmpty()
        if (direct.isNotEmpty()) {
            return direct
        }
        return ownershipByPlugin.values
            .filter { ownership ->
                ownership.dependentModulePaths.any { candidate ->
                    candidate == normalized ||
                        normalized.startsWith("$candidate/") ||
                        candidate.startsWith("$normalized/")
                }
            }
            .mapTo(linkedSetOf()) { it.pluginId }
    }

    companion object {
        private val dependencyRegex = Regex("""project\(\s*(?:path\s*=\s*)?["'](:[^"']+)["']\s*\)""")

        private fun resolveOwnership(repoRoot: File, source: PluginDevelopmentSource): PluginOwnership {
            val owningDir = resolveModuleDir(repoRoot, source.owningModulePath)
            val owningProjectPath = projectPathForDir(repoRoot, owningDir)
            val visited = linkedSetOf<String>()
            val modules = linkedSetOf<String>()
            collectModules(repoRoot, owningProjectPath, visited, modules)
            return PluginOwnership(
                pluginId = source.pluginId,
                owningModulePath = normalizeModulePath(repoRoot, owningDir.absolutePath),
                dependentModulePaths = modules
            )
        }

        private fun collectModules(
            repoRoot: File,
            projectPath: String,
            visited: MutableSet<String>,
            modules: MutableSet<String>
        ) {
            if (!visited.add(projectPath)) return
            val moduleDir = moduleDirForProjectPath(repoRoot, projectPath)
            if (!moduleDir.exists()) return
            modules += normalizeModulePath(repoRoot, moduleDir.absolutePath)
            val buildFile = File(moduleDir, "build.gradle.kts")
            if (!buildFile.exists()) return
            val script = buildFile.readText()
            dependencyRegex.findAll(script)
                .map { it.groupValues[1] }
                .forEach { collectModules(repoRoot, it, visited, modules) }
        }

        private fun resolveModuleDir(repoRoot: File, owningModulePath: String): File {
            return if (owningModulePath.startsWith(":")) {
                moduleDirForProjectPath(repoRoot, owningModulePath)
            } else {
                val dir = File(owningModulePath)
                if (dir.isAbsolute) dir else File(repoRoot, owningModulePath)
            }.absoluteFile.normalize()
        }

        private fun projectPathForDir(repoRoot: File, moduleDir: File): String {
            val relativePath = moduleDir.relativeTo(repoRoot).invariantSeparatorsPath
            if (relativePath.isBlank()) return ":"
            return ":" + relativePath.split('/').joinToString(":")
        }

        private fun moduleDirForProjectPath(repoRoot: File, projectPath: String): File {
            if (projectPath == ":") return repoRoot
            val relativePath = projectPath.removePrefix(":").replace(':', File.separatorChar)
            return File(repoRoot, relativePath)
        }
    }
}

class GradleDevBuildExecutor(
    private val repoRoot: File,
    private val gradleCommand: List<String> = if (System.getProperty("os.name").lowercase().contains("win")) listOf("gradlew.bat") else listOf("./gradlew")
) : DevBuildExecutor {
    override suspend fun buildModules(moduleProjectPaths: Set<String>): DevBuildResult {
        if (moduleProjectPaths.isEmpty()) {
            return DevBuildResult(success = true, summary = "No modules to build", output = "")
        }
        val tasks = moduleProjectPaths.map { if (it == ":") ":classes" else "$it:classes" }
        val command = gradleCommand + tasks + "--console=plain"
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(command)
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
        }
        val (output, exit) = coroutineScope {
            val outputDeferred = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val exit = awaitProcessExit(process)
            outputDeferred.await() to exit
        }
        val summary = output.lineSequence().firstOrNull { line -> line.contains("error", ignoreCase = true) }
            ?: if (exit == 0) "Build succeeded" else "Build failed"
        return DevBuildResult(success = exit == 0, summary = summary, output = output)
    }
}

private suspend fun awaitProcessExit(process: Process): Int = suspendCancellableCoroutine { cont ->
    val future = process.onExit()
    future.whenComplete { completed, error ->
        if (error != null) {
            cont.resumeWithException(error)
        } else {
            cont.resume(completed.exitValue())
        }
    }
    cont.invokeOnCancellation {
        future.cancel(true)
    }
}

class DefaultDevHotReloadEngine(
    private val repoRoot: File,
    private val impactAnalyzer: PluginImpactAnalyzer,
    private val classifier: ModuleChangeClassifier,
    private val buildExecutor: DevBuildExecutor,
    private val generationLoader: DevPluginGenerationLoader
) : DevHotReloadEngine {
    private companion object {
        private const val RELOAD_DEBOUNCE_MS = 500L
        private const val FAILURE_WINDOW_MS = 60_000L
        private const val FAILURE_THRESHOLD = 8
        private const val COOLDOWN_MS = 30_000L
    }

    private val logger = KeelLoggerService.getLogger("DevHotReloadEngine")
    private val _events = MutableSharedFlow<DevReloadEvent>(extraBufferCapacity = 256)
    private val _status = MutableStateFlow(DevHotReloadStatus())
    private val sources = ConcurrentHashMap<String, PluginDevelopmentSource>()
    private val failureWindow = ConcurrentHashMap<String, MutableList<Long>>()
    private val lastAttemptByPlugin = ConcurrentHashMap<String, Long>()

    override val events: SharedFlow<DevReloadEvent> = _events.asSharedFlow()

    val statusFlow = _status.asStateFlow()

    override fun registerSource(source: PluginDevelopmentSource) {
        sources[source.pluginId] = source
        (impactAnalyzer as? DefaultPluginImpactAnalyzer)?.registerOwnership(source)
    }

    override fun status(): DevHotReloadStatus = _status.value

    override suspend fun handleModuleChange(event: ModuleChangeEvent) {
        val classified = classifier.classify(event)
        if (classified.scope == ChangeScope.UNKNOWN) {
            return
        }
        emit(DevReloadStage.CHANGE_DETECTED, modulePath = classified.modulePath, message = "Module change: ${event.relativePath}")
        if (classified.scope == ChangeScope.BUILD_LOGIC || classified.scope == ChangeScope.KERNEL_SOURCE) {
            val reason = classified.restartRequiredReason ?: "Restart required"
            emit(
                DevReloadStage.RESTART_REQUIRED,
                modulePath = classified.modulePath,
                outcome = DevReloadOutcome.RESTART_REQUIRED,
                message = reason
            )
            return
        }

        val affected = impactAnalyzer.affectedPlugins(classified.modulePath).filter { sources.containsKey(it) }
        val fallback = if (affected.isEmpty()) {
            val changed = normalizeModulePath(repoRoot, classified.modulePath)
            sources.values
                .filter { source ->
                    val owning = normalizeModulePath(repoRoot, source.owningModulePath)
                    changed == owning || changed.startsWith("$owning/") || owning.startsWith("$changed/")
                }
                .map { it.pluginId }
        } else {
            emptyList()
        }
        val effectiveAffected = (affected + fallback).distinct()
        emit(
            DevReloadStage.AFFECTED_PLUGINS_RESOLVED,
            modulePath = classified.modulePath,
            message = "Affected plugins: ${effectiveAffected.joinToString(",")}".ifBlank { "No affected plugins" }
        )
        effectiveAffected.forEach { pluginId ->
            reloadPlugin(pluginId, "module-change:${event.relativePath}")
        }
    }

    override suspend fun reloadPlugin(pluginId: String, reason: String): ReloadAttemptResult {
        val source = sources[pluginId]
            ?: return ReloadAttemptResult(pluginId, DevReloadOutcome.RELOAD_FAILED, "Plugin source not registered")

        val now = System.currentTimeMillis()
        val lastAttempt = lastAttemptByPlugin[pluginId]
        if (lastAttempt != null && now - lastAttempt < RELOAD_DEBOUNCE_MS) {
            return ReloadAttemptResult(pluginId, DevReloadOutcome.RELOAD_FAILED, "Debounced")
        }
        if (isCoolingDown(pluginId, now)) {
            return ReloadAttemptResult(pluginId, DevReloadOutcome.RELOAD_FAILED, "Cooling down after repeated failures")
        }
        lastAttemptByPlugin[pluginId] = now

        val ownership = impactAnalyzer.ownershipOf(pluginId)
            ?: return ReloadAttemptResult(pluginId, DevReloadOutcome.RELOAD_FAILED, "Ownership not resolved")

        val start = Instant.now()
        emit(DevReloadStage.BUILD_STARTED, pluginId = pluginId, modulePath = ownership.owningModulePath, message = "Building affected modules")
        _status.value = _status.value.copy(inProgress = true)

        val projectPaths = ownership.dependentModulePaths.map(::toProjectPath).toSet()
        val buildResult = buildExecutor.buildModules(projectPaths)
        if (!buildResult.success) {
            val result = ReloadAttemptResult(
                pluginId = pluginId,
                outcome = DevReloadOutcome.RELOAD_FAILED,
                message = "Build failed",
                modulePath = ownership.owningModulePath,
                buildSummary = buildResult.summary,
                durationMs = Duration.between(start, Instant.now()).toMillis()
            )
            markFailure(pluginId, now)
            emit(
                DevReloadStage.BUILD_FAILED,
                pluginId = pluginId,
                modulePath = ownership.owningModulePath,
                outcome = DevReloadOutcome.RELOAD_FAILED,
                message = buildResult.summary
            )
            _status.value = DevHotReloadStatus(inProgress = false, lastEvent = _status.value.lastEvent, lastFailureSummary = buildResult.summary)
            return result
        }

        emit(DevReloadStage.BUILD_SUCCEEDED, pluginId = pluginId, modulePath = ownership.owningModulePath, message = "Build succeeded")
        emit(DevReloadStage.GENERATION_LOAD_STARTED, pluginId = pluginId, modulePath = ownership.owningModulePath, message = "Loading new generation")

        val result = generationLoader.reload(source, ownership.dependentModulePaths, reason)
        val durationMs = Duration.between(start, Instant.now()).toMillis()
        val finalResult = result.copy(durationMs = durationMs)
        if (finalResult.outcome == DevReloadOutcome.RELOADED) {
            clearFailures(pluginId)
            emit(DevReloadStage.RELOADED, pluginId = pluginId, modulePath = ownership.owningModulePath, outcome = finalResult.outcome, message = finalResult.message)
        } else {
            markFailure(pluginId, now)
            emit(
                if (finalResult.outcome == DevReloadOutcome.RESTART_REQUIRED) DevReloadStage.RESTART_REQUIRED else DevReloadStage.RELOAD_FAILED,
                pluginId = pluginId,
                modulePath = ownership.owningModulePath,
                outcome = finalResult.outcome,
                message = finalResult.message
            )
        }
        _status.value = DevHotReloadStatus(
            inProgress = false,
            lastEvent = _status.value.lastEvent,
            lastFailureSummary = if (finalResult.outcome == DevReloadOutcome.RELOADED) null else finalResult.message
        )
        return finalResult
    }

    private fun toProjectPath(modulePath: String): String {
        val normalized = normalizeModulePath(repoRoot, modulePath)
        if (normalized.isBlank()) return ":"
        val root = repoRoot.absoluteFile.normalize().invariantSeparatorsPath
        return if (normalized == root) ":" else ":" + normalized.removePrefix("$root/").split('/').joinToString(":")
    }

    private fun emit(
        stage: DevReloadStage,
        pluginId: String? = null,
        modulePath: String? = null,
        outcome: DevReloadOutcome? = null,
        message: String
    ) {
        val event = DevReloadEvent(
            stage = stage,
            pluginId = pluginId,
            modulePath = modulePath?.let(::sanitizeModulePath),
            outcome = outcome,
            message = message
        )
        _events.tryEmit(event)
        _status.value = _status.value.copy(lastEvent = event)
        logger.info("hotreload stage=${stage.name} pluginId=${pluginId ?: "-"} message=$message")
    }

    private fun sanitizeModulePath(path: String): String {
        val normalized = normalizeModulePath(repoRoot, path)
        val root = repoRoot.absoluteFile.normalize().invariantSeparatorsPath
        return when {
            normalized == root -> ":"
            normalized.startsWith("$root/") -> normalized.removePrefix("$root/")
            else -> normalized
        }
    }

    private fun markFailure(pluginId: String, now: Long) {
        val window = failureWindow.computeIfAbsent(pluginId) { mutableListOf() }
        window += now
        window.removeAll { now - it > FAILURE_WINDOW_MS }
    }

    private fun clearFailures(pluginId: String) {
        failureWindow.remove(pluginId)
    }

    private fun isCoolingDown(pluginId: String, now: Long): Boolean {
        val window = failureWindow[pluginId] ?: return false
        window.removeAll { now - it > FAILURE_WINDOW_MS }
        if (window.size < FAILURE_THRESHOLD) return false
        return now - window.last() < COOLDOWN_MS
    }
}

internal fun normalizeModulePath(repoRoot: File, path: String): String {
    val raw = File(path)
    val resolved = if (raw.isAbsolute) raw else File(repoRoot, path)
    return resolved.absoluteFile.normalize().invariantSeparatorsPath
}
