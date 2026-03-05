package com.keel.kernel.config

import java.io.File

internal data class WatchedModule(
    val projectPath: String,
    val directory: File
)

internal data class DefaultWatchDirectories(
    val callerModule: WatchedModule,
    val modules: List<WatchedModule>
) {
    val directories: List<String> = modules.map { it.directory.absolutePath }
}

internal object DefaultWatchDirectoriesResolver {
    private val projectDependencyRegex = Regex("""project\("(:.+?)"\)""")

    fun resolve(): DefaultWatchDirectories? {
        val callerModuleDir = resolveCallerModuleDir() ?: return null
        val repoRoot = locateRepoRoot(callerModuleDir) ?: return null
        return resolve(repoRoot, callerModuleDir)
    }

    fun resolveForCallingModule(): List<String> = resolve()?.directories ?: emptyList()

    internal fun resolve(repoRoot: File, callerModuleDir: File): DefaultWatchDirectories? {
        val normalizedRepoRoot = repoRoot.absoluteFile.normalize()
        val normalizedCallerDir = callerModuleDir.absoluteFile.normalize()
        val callerProjectPath = projectPathForDir(normalizedRepoRoot, normalizedCallerDir) ?: return null
        val visited = linkedSetOf<String>()
        val modules = mutableListOf<WatchedModule>()
        collectModules(
            repoRoot = normalizedRepoRoot,
            projectPath = callerProjectPath,
            visited = visited,
            modules = modules
        )
        val callerModule = modules.firstOrNull { it.projectPath == callerProjectPath } ?: return null
        return DefaultWatchDirectories(
            callerModule = callerModule,
            modules = modules
        )
    }

    private fun collectModules(
        repoRoot: File,
        projectPath: String,
        visited: MutableSet<String>,
        modules: MutableList<WatchedModule>
    ) {
        if (!visited.add(projectPath)) {
            return
        }
        val moduleDir = moduleDirForProjectPath(repoRoot, projectPath)
        if (!moduleDir.exists()) {
            return
        }
        modules += WatchedModule(projectPath = projectPath, directory = moduleDir)
        parseProjectDependencies(File(moduleDir, "build.gradle.kts")).forEach { dependencyPath ->
            collectModules(
                repoRoot = repoRoot,
                projectPath = dependencyPath,
                visited = visited,
                modules = modules
            )
        }
    }

    private fun resolveCallerModuleDir(): File? {
        val callerClass = resolveCallerClass() ?: return null
        val codeSource = callerClass.protectionDomain?.codeSource?.location ?: return null
        val classOutput = runCatching { File(codeSource.toURI()) }.getOrNull() ?: return null

        return generateSequence(classOutput.absoluteFile.normalize()) { it.parentFile }
            .firstOrNull { File(it, "build.gradle.kts").exists() }
    }

    private fun resolveCallerClass(): Class<*>? {
        val stackTrace = Throwable().stackTrace
        for (frame in stackTrace) {
            val className = frame.className
            if (
                className.startsWith("com.keel.kernel.") ||
                className.startsWith("java.") ||
                className.startsWith("jdk.") ||
                className.startsWith("kotlin.") ||
                className.startsWith("sun.")
            ) {
                continue
            }
            val clazz = runCatching { Class.forName(className) }.getOrNull() ?: continue
            return clazz
        }
        return null
    }

    private fun locateRepoRoot(start: File): File? {
        return generateSequence(start.absoluteFile.normalize()) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
    }

    private fun parseProjectDependencies(buildFile: File): List<String> {
        if (!buildFile.exists()) {
            return emptyList()
        }
        return projectDependencyRegex.findAll(buildFile.readText())
            .map { it.groupValues[1] }
            .toList()
    }

    private fun projectPathForDir(repoRoot: File, moduleDir: File): String? {
        val relativePath = runCatching {
            moduleDir.relativeTo(repoRoot).invariantSeparatorsPath
        }.getOrNull() ?: return null
        if (relativePath.isBlank()) {
            return ":"
        }
        return ":" + relativePath.split('/').joinToString(":")
    }

    private fun moduleDirForProjectPath(repoRoot: File, projectPath: String): File {
        if (projectPath == ":") {
            return repoRoot
        }
        val relativePath = projectPath.removePrefix(":").replace(':', File.separatorChar)
        return File(repoRoot, relativePath).absoluteFile.normalize()
    }
}
