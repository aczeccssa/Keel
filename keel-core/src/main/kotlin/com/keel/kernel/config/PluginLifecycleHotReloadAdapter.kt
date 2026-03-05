package com.keel.kernel.config

import com.keel.kernel.hotreload.DevHotReloadEngine
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.plugin.UnifiedPluginManager

class PluginLifecycleHotReloadAdapter(
    private val pluginManager: UnifiedPluginManager,
    private val devHotReloadEngine: DevHotReloadEngine? = null
) {
    private val logger = KeelLoggerService.getLogger("PluginLifecycleHotReloadAdapter")

    suspend fun handleConfigChange(event: ConfigChangeEvent) {
        val pluginId = event.fileName.substringBeforeLast(".")
        val config = pluginManager.getRuntimeConfig(pluginId) ?: return
        if (!config.reload.reloadOnConfigChange) {
            return
        }
        logger.info("Config-triggered reload pluginId=$pluginId changeType=${event.type}")
        if (devHotReloadEngine != null && pluginManager.hasPluginSource(pluginId)) {
            devHotReloadEngine.reloadPlugin(pluginId, reason = "config-change:${event.fileName}")
        } else {
            pluginManager.reloadPlugin(pluginId)
        }
    }

    suspend fun handlePluginChange(event: PluginChangeEvent) {
        val config = pluginManager.getRuntimeConfig(event.pluginId) ?: return
        when (event.type) {
            PluginChangeType.DELETED -> {
                logger.info("Artifact-triggered dispose pluginId=${event.pluginId}")
                pluginManager.disposePlugin(event.pluginId)
            }
            PluginChangeType.CREATED,
            PluginChangeType.MODIFIED,
            PluginChangeType.RELOADED -> {
                if (!config.reload.replaceOnArtifactChange) {
                    return
                }
                logger.info("Artifact-triggered replace pluginId=${event.pluginId} changeType=${event.type}")
                pluginManager.replacePlugin(event.pluginId)
            }
        }
    }
}
