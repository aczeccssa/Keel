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
data class TopEdgeMetric(
    val edgeFrom: String,
    val edgeTo: String,
    val count: Int,
    val errorCount: Int = 0
)

@Serializable
data class KernelProcessMetrics(
    val processCpuLoad: Double? = null,
    val systemLoadAverage: Double? = null,
    val availableProcessors: Int = 1,
    val heapUsedBytes: Long = 0,
    val heapMaxBytes: Long = 0,
    val heapUsedPercent: Double = 0.0,
    val threadCount: Int = 0
)

internal data class SystemMetrics(
    val processCpuLoad: Double?,
    val systemLoadAverage: Double?,
    val availableProcessors: Int
)

@Serializable
data class LatencyMetrics(
    val avgMs: Double = 0.0,
    val p95Ms: Long = 0,
    val p99Ms: Long = 0,
    val errorRate: Double = 0.0,
    val completedSpanCount: Int = 0
)

@Serializable
data class TrafficMetrics(
    val recentFlowCount: Int = 0,
    val recentTraceCount: Int = 0,
    val topEdges: List<TopEdgeMetric> = emptyList()
)

@Serializable
data class NodeSummary(
    val node: JvmNode,
    val recentFlowCount: Int = 0,
    val recentTraceCount: Int = 0,
    val errorCount: Int = 0
)

@Serializable
data class StructuredLogRecord(
    val timestamp: Long,
    val level: String,
    val source: String,
    val message: String,
    val throwable: String? = null,
    val traceId: String? = null,
    val spanId: String? = null,
    val pluginId: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class LogSnapshotPage(
    val items: List<StructuredLogRecord>,
    val total: Int,
    val limit: Int
)

@Serializable
data class ObservabilityMetricsSnapshot(
    val windowMs: Long,
    val runtimeNodeCount: Int,
    val externalNodeCount: Int,
    val totalInflight: Int,
    val totalQueueDepth: Int,
    val droppedLogCount: Long,
    val kernel: KernelProcessMetrics,
    val latency: LatencyMetrics,
    val traffic: TrafficMetrics,
    val nodes: List<NodeSummary>
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
    val message: String,
    val throwable: String? = null,
    val traceId: String? = null,
    val spanId: String? = null,
    val pluginId: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

data class ObservabilityStreamEvent(
    val type: String,
    val dataJson: String
)
