package com.keel.jvm.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PLUGIN_JVM_PROTOCOL_VERSION: Int = 2

object PluginJvmLimits {
    const val NORMAL_FRAME_BYTES: Int = 256 * 1024
    const val MAX_FRAME_BYTES: Int = 2 * 1024 * 1024
}

sealed interface PluginJvmMessage {
    val protocolVersion: Int
    val pluginId: String
    val generation: Long
    val timestamp: Long
    val messageId: String
}

sealed interface PluginJvmControlResponse : PluginJvmMessage {
    val correlationId: String
}

sealed interface PluginRuntimeEvent : PluginJvmMessage {
    val authToken: String
}

@Serializable
data class PluginEndpointInventoryItem(
    val endpointId: String,
    val method: String,
    val path: String
)

@Serializable
data class PluginRouteInventoryItem(
    val routeType: String,
    val path: String,
    val method: String? = null,
    val endpointId: String? = null
)

@Serializable
data class HandshakeRequest(
    val kind: String = "handshake-request",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String,
    val runtimeMode: String
) : PluginJvmMessage

@Serializable
data class HandshakeResponse(
    val kind: String = "handshake-response",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val descriptorVersion: String,
    val runtimeMode: String,
    val supportedServices: List<String> = emptyList(),
    val endpointInventory: List<PluginEndpointInventoryItem>,
    val routeInventory: List<PluginRouteInventoryItem> = emptyList(),
    val accepted: Boolean,
    val reason: String? = null
) : PluginJvmControlResponse

@Serializable
data class InvokeRequest(
    val kind: String = "invoke-request",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
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
    val traceparent: String? = null,
    val tracestate: String? = null,
    val bodyJson: String?,
    val maxPayloadBytes: Long?,
    val allowChunkedTransfer: Boolean
) : PluginJvmMessage

@Serializable
data class InvokeResponse(
    val kind: String = "invoke-response",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
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
) : PluginJvmControlResponse

@Serializable
data class HealthRequest(
    val kind: String = "health-request",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String
) : PluginJvmMessage

@Serializable
data class HealthResponse(
    val kind: String = "health-response",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
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
) : PluginJvmControlResponse

@Serializable
data class ShutdownRequest(
    val kind: String = "shutdown-request",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String,
    val reason: String = "kernel-stop"
) : PluginJvmMessage

@Serializable
data class ShutdownResponse(
    val kind: String = "shutdown-response",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val accepted: Boolean,
    val inflightInvokes: Int
) : PluginJvmControlResponse

@Serializable
data class ReloadPrepareRequest(
    val kind: String = "reload-prepare-request",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String
) : PluginJvmMessage

@Serializable
data class ReloadPrepareResponse(
    val kind: String = "reload-prepare-response",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val accepted: Boolean
) : PluginJvmControlResponse

@Serializable
data class StaticFetchRequest(
    val kind: String = "static-fetch-request",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String,
    val routePath: String,
    val resourcePath: String,
    val requestHeaders: Map<String, List<String>> = emptyMap()
) : PluginJvmMessage

@Serializable
data class StaticFetchResponse(
    val kind: String = "static-fetch-response",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val bodyBase64: String? = null,
    val errorMessage: String? = null
) : PluginJvmControlResponse

@Serializable
data class SseOpenRequest(
    val kind: String = "sse-open-request",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String,
    val streamId: String,
    val routePath: String,
    val requestId: String,
    val rawPath: String,
    val pathParameters: Map<String, String>,
    val queryParameters: Map<String, List<String>>,
    val headers: Map<String, List<String>>
) : PluginJvmMessage

@Serializable
data class SseOpenResponse(
    val kind: String = "sse-open-response",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val accepted: Boolean,
    val errorMessage: String? = null
) : PluginJvmControlResponse

@Serializable
data class SseCloseRequest(
    val kind: String = "sse-close-request",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val authToken: String,
    val streamId: String
) : PluginJvmMessage

@Serializable
data class SseCloseResponse(
    val kind: String = "sse-close-response",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val correlationId: String,
    val accepted: Boolean
) : PluginJvmControlResponse

@Serializable
data class PluginReadyEvent(
    val kind: String = "plugin-ready-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val runtimeMode: String
) : PluginRuntimeEvent

@Serializable
data class PluginStoppingEvent(
    val kind: String = "plugin-stopping-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val reason: String
) : PluginRuntimeEvent

@Serializable
data class PluginDisposedEvent(
    val kind: String = "plugin-disposed-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String
) : PluginRuntimeEvent

@Serializable
data class PluginFailureEvent(
    val kind: String = "plugin-failure-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val errorType: String,
    val errorMessage: String
) : PluginRuntimeEvent

@Serializable
data class PluginLogEvent(
    val kind: String = "plugin-log-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val level: String,
    val message: String,
    val droppedCount: Long = 0
) : PluginRuntimeEvent

@Serializable
data class PluginTraceEvent(
    val kind: String = "plugin-trace-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val service: String,
    val name: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val status: String,
    val attributes: Map<String, String> = emptyMap(),
    val edgeFrom: String? = null,
    val edgeTo: String? = null
) : PluginRuntimeEvent

@Serializable
data class PluginBackpressureEvent(
    val kind: String = "plugin-backpressure-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val eventQueueDepth: Int
) : PluginRuntimeEvent

@Serializable
data class PluginDrainCompleteEvent(
    val kind: String = "plugin-drain-complete-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val remainingInvokes: Int
) : PluginRuntimeEvent

@Serializable
data class PluginProcessExitedEvent(
    val kind: String = "plugin-process-exited-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    val exitCode: Int
) : PluginJvmMessage

@Serializable
data class PluginEventQueueOverflowEvent(
    val kind: String = "plugin-event-queue-overflow-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val queueType: String,
    val capacity: Int
) : PluginRuntimeEvent

@Serializable
data class PluginSseDataEvent(
    val kind: String = "plugin-sse-data-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val streamId: String,
    val event: String? = null,
    val data: String? = null,
    val id: String? = null,
    val retry: Long? = null
) : PluginRuntimeEvent

@Serializable
data class PluginSseClosedEvent(
    val kind: String = "plugin-sse-closed-event",
    override val protocolVersion: Int = PLUGIN_JVM_PROTOCOL_VERSION,
    override val pluginId: String,
    override val generation: Long,
    override val timestamp: Long,
    override val messageId: String,
    override val authToken: String,
    val streamId: String
) : PluginRuntimeEvent

object PluginJvmJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }
}
