package com.keel.kernel.plugin

enum class PluginChannelHealth {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNREACHABLE
}

data class PluginFailureRecord(
    val timestamp: Long,
    val source: String,
    val message: String
)

data class PluginRuntimeDiagnostics(
    val processAlive: Boolean? = null,
    val adminChannelHealth: PluginChannelHealth = PluginChannelHealth.UNKNOWN,
    val eventChannelHealth: PluginChannelHealth = PluginChannelHealth.UNKNOWN,
    val droppedLogCount: Long = 0,
    val eventQueueDepth: Int = 0,
    val eventOverflowed: Boolean = false,
    val lastHealthLatencyMs: Long? = null,
    val lastAdminLatencyMs: Long? = null,
    val lastEventAtEpochMs: Long? = null,
    val inflightInvocations: Int = 0
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
