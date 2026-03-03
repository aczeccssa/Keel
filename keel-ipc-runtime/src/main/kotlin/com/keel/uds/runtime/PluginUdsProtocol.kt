package com.keel.uds.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PLUGIN_UDS_PROTOCOL_VERSION: Int = 1

object PluginUdsLimits {
    const val NORMAL_FRAME_BYTES: Int = 256 * 1024
    const val MAX_FRAME_BYTES: Int = 2 * 1024 * 1024
}

sealed interface PluginUdsMessage {
    val protocolVersion: Int
    val pluginId: String
    val generation: Long
    val timestamp: Long
    val messageId: String
}

sealed interface PluginUdsControlResponse : PluginUdsMessage {
    val correlationId: String
}

sealed interface PluginRuntimeEvent : PluginUdsMessage

@Serializable
data class PluginEndpointInventoryItem(
    val endpointId: String,
    val method: String,
    val path: String
)

@Serializable
data class HandshakeRequest(
    val kind: String = "handshake-request",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String,
    val runtimeMode: String
) : PluginUdsMessage

@Serializable
data class HandshakeResponse(
    val kind: String = "handshake-response",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val descriptorVersion: String,
    val runtimeMode: String,
    val endpointInventory: List<PluginEndpointInventoryItem>,
    val accepted: Boolean,
    val reason: String? = null
) : PluginUdsControlResponse

@Serializable
data class InvokeRequest(
    val kind: String = "invoke-request",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String,
    val requestId: String,
    val endpointId: String,
    val method: String,
    val rawPath: String,
    val pathParameters: Map<String, String>,
    val queryParameters: Map<String, List<String>>,
    val headers: Map<String, List<String>>,
    val bodyJson: String?,
    val maxPayloadBytes: Long?,
    val allowChunkedTransfer: Boolean
) : PluginUdsMessage

@Serializable
data class InvokeResponse(
    val kind: String = "invoke-response",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val requestId: String,
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val bodyJson: String? = null,
    val errorMessage: String? = null
) : PluginUdsControlResponse

@Serializable
data class HealthRequest(
    val kind: String = "health-request",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String
) : PluginUdsMessage

@Serializable
data class HealthResponse(
    val kind: String = "health-response",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val lifecycleState: String,
    val healthState: String,
    val startedAtEpochMs: Long,
    val eventQueueDepth: Int,
    val droppedLogCount: Long
) : PluginUdsControlResponse

@Serializable
data class ShutdownRequest(
    val kind: String = "shutdown-request",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String,
    val reason: String = "kernel-stop"
) : PluginUdsMessage

@Serializable
data class ShutdownResponse(
    val kind: String = "shutdown-response",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val accepted: Boolean,
    val inflightInvokes: Int
) : PluginUdsControlResponse

@Serializable
data class ReloadPrepareRequest(
    val kind: String = "reload-prepare-request",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String
) : PluginUdsMessage

@Serializable
data class ReloadPrepareResponse(
    val kind: String = "reload-prepare-response",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val accepted: Boolean
) : PluginUdsControlResponse

@Serializable
data class PluginReadyEvent(
    val kind: String = "plugin-ready-event",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val runtimeMode: String
) : PluginRuntimeEvent

@Serializable
data class PluginStoppingEvent(
    val kind: String = "plugin-stopping-event",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val reason: String
) : PluginRuntimeEvent

@Serializable
data class PluginDisposedEvent(
    val kind: String = "plugin-disposed-event",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String
) : PluginRuntimeEvent

@Serializable
data class PluginFailureEvent(
    val kind: String = "plugin-failure-event",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val errorType: String,
    val errorMessage: String
) : PluginRuntimeEvent

@Serializable
data class PluginLogEvent(
    val kind: String = "plugin-log-event",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val level: String,
    val message: String,
    val droppedCount: Long = 0
) : PluginRuntimeEvent

@Serializable
data class PluginBackpressureEvent(
    val kind: String = "plugin-backpressure-event",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val eventQueueDepth: Int
) : PluginRuntimeEvent

@Serializable
data class PluginDrainCompleteEvent(
    val kind: String = "plugin-drain-complete-event",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val remainingInvokes: Int
) : PluginRuntimeEvent

@Serializable
data class PluginProcessExitedEvent(
    val kind: String = "plugin-process-exited-event",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val exitCode: Int
) : PluginRuntimeEvent

@Serializable
data class PluginEventQueueOverflowEvent(
    val kind: String = "plugin-event-queue-overflow-event",
    override val protocolVersion: Int = PLUGIN_UDS_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val queueType: String,
    val capacity: Int
) : PluginRuntimeEvent

object PluginUdsJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }
}
