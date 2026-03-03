package com.keel.kernel.plugin

import com.keel.kernel.config.KeelConstants
import com.keel.kernel.logging.KeelLoggerService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PluginRuntimeConfig(
    val pluginId: String,
    val enabled: Boolean = true,
    val executionMode: PluginExecutionMode = PluginExecutionMode.IN_PROCESS,
    val startupTimeoutMs: Long = 5000,
    val callTimeoutMs: Long = 3000,
    val healthCheckIntervalMs: Long = 10000
)

object PluginRuntimeConfigLoader {
    private val logger = KeelLoggerService.getLogger("PluginRuntimeConfigLoader")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(descriptor: PluginDescriptor, configRoot: String = KeelConstants.CONFIG_DIR): PluginRuntimeConfig {
        val configFile = File(configRoot, "plugins/${descriptor.pluginId}.json")
        if (!configFile.exists()) {
            return PluginRuntimeConfig(
                pluginId = descriptor.pluginId,
                executionMode = descriptor.defaultExecutionMode
            )
        }

        return runCatching {
            json.decodeFromString<PluginRuntimeConfig>(configFile.readText())
        }.getOrElse { error ->
            logger.warn("Failed to load runtime config for ${descriptor.pluginId}: ${error.message}")
            PluginRuntimeConfig(
                pluginId = descriptor.pluginId,
                executionMode = descriptor.defaultExecutionMode
            )
        }
    }
}
