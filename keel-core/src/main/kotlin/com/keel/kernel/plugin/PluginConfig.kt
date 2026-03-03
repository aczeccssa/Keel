package com.keel.kernel.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class PluginConfig(
    val pluginId: String,
    val enabled: Boolean = true,
    val runtimeMode: PluginRuntimeMode = PluginRuntimeMode.IN_PROCESS,
    val startupTimeoutMs: Long = 5000,
    val callTimeoutMs: Long = 3000,
    val stopTimeoutMs: Long = 3000,
    val healthCheckIntervalMs: Long = 10000,
    val maxConcurrentCalls: Int = 128,
    val eventLogRingBufferSize: Int = 4096,
    val criticalEventQueueSize: Int = 256,
    val reload: ReloadConfig = ReloadConfig(),
    val settings: JsonObject = buildJsonObject {}
)

@Serializable
data class ReloadConfig(
    val watchEnabled: Boolean = false,
    val debounceMs: Long = 500,
    val replaceOnArtifactChange: Boolean = true,
    val reloadOnConfigChange: Boolean = true
)
