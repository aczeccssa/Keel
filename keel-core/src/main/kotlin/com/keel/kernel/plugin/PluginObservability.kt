package com.keel.kernel.plugin

import kotlinx.serialization.Serializable

enum class PluginChannelHealth {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNREACHABLE
}

@Serializable
data class PluginNodeAssetMetadata(
    val assetId: String? = null,
    val address: String? = null,
    val zone: String? = null,
    val region: String? = null,
    val role: String? = null,
    val roleDescription: String? = null,
    val featured: Boolean = false
)

data class PluginFailureRecord(
    val timestamp: Long,
    val source: String,
    val message: String
)

data class PluginRuntimeDiagnostics(
    val processAlive: Boolean? = null,
    val adminChannelHealth: PluginChannelHealth = PluginChannelHealth.UNKNOWN,
    val eventChannelHealth: PluginChannelHealth = PluginChannelHealth.UNKNOWN,
    val activeCommunicationMode: JvmCommunicationMode? = null,
    val fallbackActivated: Boolean = false,
    val lastFallbackReason: String? = null,
    val droppedLogCount: Long = 0,
    val eventQueueDepth: Int = 0,
    val eventOverflowed: Boolean = false,
    val lastHealthLatencyMs: Long? = null,
    val lastAdminLatencyMs: Long? = null,
    val lastEventAtEpochMs: Long? = null,
    val inflightInvocations: Int = 0,
    val processCpuLoadPercent: Double? = null,
    val heapUsedBytes: Long? = null,
    val heapMaxBytes: Long? = null,
    val heapUsedPercent: Double? = null,
    val assetMetadata: PluginNodeAssetMetadata? = null
)

data class PluginRuntimeSnapshot(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val runtimeMode: PluginRuntimeMode,
    val lifecycleState: PluginLifecycleState,
    val healthState: PluginHealthState,
    val generation: PluginGeneration,
    val processState: PluginProcessState?,
    val processId: Long?,
    val processHandleAlive: Boolean? = null,
    val diagnostics: PluginRuntimeDiagnostics,
    val lastFailure: PluginFailureRecord? = null
)
