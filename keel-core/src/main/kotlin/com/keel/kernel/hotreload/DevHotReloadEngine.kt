package com.keel.kernel.hotreload

import com.keel.kernel.config.ModuleChangeEvent
import kotlinx.coroutines.flow.SharedFlow

interface DevBuildExecutor {
    suspend fun buildModules(moduleProjectPaths: Set<String>): DevBuildResult
}

interface DevPluginGenerationLoader {
    suspend fun reload(source: PluginDevelopmentSource, classpathModulePaths: Set<String>, reason: String): ReloadAttemptResult
}

interface ModuleChangeClassifier {
    fun classify(event: ModuleChangeEvent): ClassifiedModuleChange
}

interface PluginImpactAnalyzer {
    fun ownershipOf(pluginId: String): PluginOwnership?
    fun affectedPlugins(modulePath: String): Set<String>
}

interface DevHotReloadEngine {
    val events: SharedFlow<DevReloadEvent>

    fun registerSource(source: PluginDevelopmentSource)

    fun status(): DevHotReloadStatus

    suspend fun handleModuleChange(event: ModuleChangeEvent)

    suspend fun reloadPlugin(pluginId: String, reason: String = "manual"): ReloadAttemptResult
}
