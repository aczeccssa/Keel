package com.keel.kernel.config

import com.keel.kernel.hotreload.DevHotReloadEngine
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.plugin.UnifiedPluginManager

/**
 * Adapter that bridges plugin config changes to the appropriate reload path.
 *
 * When a plugin's configuration changes, this adapter decides whether to use:
 * - The dev hot-reload engine (if the plugin has a registered development source), or
 * - The legacy manager-based reload (simple stop → re-init → start).
 */
class PluginLifecycleHotReloadAdapter(
    private val pluginManager: UnifiedPluginManager,
    private val devHotReloadEngine: DevHotReloadEngine? = null
) {
    private val logger = KeelLoggerService.getLogger("PluginLifecycleHotReloadAdapter")

    suspend fun handleConfigChange(pluginId: String) {
        val config = pluginManager.getRuntimeConfig(pluginId) ?: return
        if (!config.reload.reloadOnConfigChange) {
            return
        }
        logger.info("Config-triggered reload pluginId=$pluginId")
        if (devHotReloadEngine != null && pluginManager.hasPluginSource(pluginId)) {
            devHotReloadEngine.reloadPlugin(pluginId, reason = "config-change")
        } else {
            pluginManager.reloadPlugin(pluginId)
        }
    }
}
