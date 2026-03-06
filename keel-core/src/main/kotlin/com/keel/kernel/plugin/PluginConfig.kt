package com.keel.kernel.plugin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Runtime configuration for a plugin instance, derived from its [PluginDescriptor].
 * This is no longer loaded from JSON files to ensure "Config-as-Code".
 */
data class PluginConfig(
    val pluginId: String,
    val enabled: Boolean = true,
    val runtimeMode: PluginRuntimeMode = PluginRuntimeMode.IN_PROCESS,
    val communicationStrategy: JvmCommunicationStrategy = JvmCommunicationStrategy.DEFAULT,
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

data class ReloadConfig(
    val watchEnabled: Boolean = false,
    val debounceMs: Long = 500,
    val replaceOnArtifactChange: Boolean = true,
    val reloadOnConfigChange: Boolean = true
)

fun PluginDescriptor.toConfig(enabled: Boolean = true): PluginConfig {
    return PluginConfig(
        pluginId = this.pluginId,
        enabled = enabled,
        runtimeMode = this.defaultRuntimeMode,
        communicationStrategy = this.communicationStrategy,
        startupTimeoutMs = this.startupTimeoutMs,
        callTimeoutMs = this.callTimeoutMs,
        stopTimeoutMs = this.stopTimeoutMs,
        healthCheckIntervalMs = this.healthCheckIntervalMs,
        maxConcurrentCalls = this.maxConcurrentCalls,
        eventLogRingBufferSize = this.eventLogRingBufferSize,
        criticalEventQueueSize = this.criticalEventQueueSize
    )
}
