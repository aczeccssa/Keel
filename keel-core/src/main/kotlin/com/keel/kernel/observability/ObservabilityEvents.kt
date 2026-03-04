package com.keel.kernel.observability

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JvmNode(
    val id: String,
    val kind: String,
    val label: String,
    val pluginId: String? = null,
    val pid: Long? = null,
    val runtimeMode: String? = null,
    val healthState: String? = null,
    val lifecycleState: String? = null,
    val startedAtEpochMs: Long? = null,
    val inflightInvocations: Int = 0,
    val eventQueueDepth: Int = 0,
    val droppedLogCount: Long = 0,
    val labels: Map<String, String> = emptyMap()
)

@Serializable
data class FlowEvent(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val service: String,
    val operation: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val status: String,
    val attributes: Map<String, String> = emptyMap(),
    val edgeFrom: String,
    val edgeTo: String
)

@Serializable
data class TraceSpanEvent(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val service: String,
    val operation: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val durationMs: Long? = null,
    val status: String,
    val attributes: Map<String, String> = emptyMap(),
    val edgeFrom: String? = null,
    val edgeTo: String? = null
)

@Serializable
data class PanelInfo(
    val id: String,
    val title: String,
    val dataEndpoint: String
)

@Serializable
data class CustomEvent(
    val type: String,
    val payload: JsonElement
)

@Serializable
data class LogEvent(
    val timestamp: Long,
    val level: String,
    val source: String,
    val message: String
)

data class ObservabilityStreamEvent(
    val type: String,
    val dataJson: String
)
