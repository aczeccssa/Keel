package com.keel.kernel.hotreload

import com.keel.kernel.plugin.UnifiedPluginManager

class ManagerBackedGenerationLoader(
    private val pluginManager: UnifiedPluginManager
) : DevPluginGenerationLoader {
    override suspend fun reload(source: PluginDevelopmentSource, classpathModulePaths: Set<String>, reason: String): ReloadAttemptResult {
        return pluginManager.reloadPluginFromSource(source, classpathModulePaths, reason)
    }
}
