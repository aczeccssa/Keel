package com.keel.kernel.hotreload

import com.keel.kernel.config.DefaultWatchDirectoriesResolver
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.plugin.KeelPlugin
import java.io.File

internal object PluginSourceInference {
    private val logger = KeelLoggerService.getLogger("PluginSourceInference")

    fun infer(plugin: KeelPlugin, repoRoot: File): PluginDevelopmentSource? {
        val owningModulePath = inferOwningModulePath(plugin.javaClass, repoRoot) ?: run {
            val fallback = inferOwningModulePathFromCaller()
            if (fallback != null) {
                logger.warn(
                    "Falling back to caller module for plugin ${plugin.descriptor.pluginId}; class location inference failed"
                )
            }
            fallback
        } ?: return null

        return PluginDevelopmentSource(
            pluginId = plugin.descriptor.pluginId,
            owningModulePath = owningModulePath,
            implementationClassName = plugin.javaClass.name,
            runtimeMode = plugin.descriptor.defaultRuntimeMode
        )
    }

    private fun inferOwningModulePath(clazz: Class<*>, repoRoot: File): String? {
        val codeSource = clazz.protectionDomain?.codeSource?.location ?: return null
        val classRoot = runCatching { File(codeSource.toURI()) }.getOrNull() ?: return null
        val moduleDir = generateSequence(classRoot.absoluteFile.normalize()) { it.parentFile }
            .firstOrNull { File(it, "build.gradle.kts").exists() }
            ?: return null
        return projectPathForDir(repoRoot.absoluteFile.normalize(), moduleDir)
    }

    private fun inferOwningModulePathFromCaller(): String? {
        val resolved = DefaultWatchDirectoriesResolver.resolve() ?: return null
        return resolved.callerModule.projectPath
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
}
