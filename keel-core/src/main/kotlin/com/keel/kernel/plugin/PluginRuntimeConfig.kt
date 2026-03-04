package com.keel.kernel.plugin

import com.keel.kernel.config.KeelConstants
import com.keel.kernel.logging.KeelLoggerService
import kotlinx.serialization.json.Json
import java.io.File

object PluginConfigLoader {
    private val logger = KeelLoggerService.getLogger("PluginConfigLoader")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(descriptor: PluginDescriptor, configRoot: String = KeelConstants.CONFIG_DIR): PluginConfig {
        val pluginConfigDir = if (configRoot == KeelConstants.CONFIG_DIR) {
            KeelConstants.CONFIG_PLUGINS_DIR
        } else {
            "$configRoot/plugins"
        }
        return load(descriptor, File(pluginConfigDir, "${descriptor.pluginId}.json"))
    }

    fun load(descriptor: PluginDescriptor, configFile: File): PluginConfig {
        val config = if (!configFile.exists()) {
            PluginConfig(
                pluginId = descriptor.pluginId,
                runtimeMode = descriptor.defaultRuntimeMode
            )
        } else {
            runCatching {
                json.decodeFromString<PluginConfig>(configFile.readText())
            }.getOrElse { error ->
                logger.warn("Failed to load config for ${descriptor.pluginId}: ${error.message}")
                throw IllegalArgumentException("Invalid config for ${descriptor.pluginId}", error)
            }
        }

        return config
            .copy(pluginId = config.pluginId.ifBlank { descriptor.pluginId })
            .also { validatePluginConfig(descriptor, it) }
    }
}

fun validatePluginConfig(descriptor: PluginDescriptor, config: PluginConfig) {
    require(config.pluginId == descriptor.pluginId) {
        "Config pluginId ${config.pluginId} does not match descriptor pluginId ${descriptor.pluginId}"
    }
    require(config.runtimeMode in descriptor.supportedRuntimeModes) {
        "Plugin ${descriptor.pluginId} does not support runtime mode ${config.runtimeMode}"
    }
    require(config.startupTimeoutMs > 0) { "startupTimeoutMs must be > 0" }
    require(config.callTimeoutMs > 0) { "callTimeoutMs must be > 0" }
    require(config.stopTimeoutMs > 0) { "stopTimeoutMs must be > 0" }
    require(config.healthCheckIntervalMs > 0) { "healthCheckIntervalMs must be > 0" }
    require(config.maxConcurrentCalls > 0) { "maxConcurrentCalls must be > 0" }
    require(config.eventLogRingBufferSize > 0) { "eventLogRingBufferSize must be > 0" }
    require(config.criticalEventQueueSize > 0) { "criticalEventQueueSize must be > 0" }
    require(config.reload.debounceMs >= 0) { "reload.debounceMs must be >= 0" }
}
