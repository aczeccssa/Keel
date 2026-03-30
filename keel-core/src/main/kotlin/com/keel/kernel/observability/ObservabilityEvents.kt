package com.keel.kernel.observability

import com.keel.kernel.plugin.PluginNodeAssetMetadata
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
data class TraceDashboardSummary(
    val totalTraces: Int = 0,
    val p99LatencyMs: Long = 0,
    val errorRatePercent: Double = 0.0,
    val activeSpanCount: Int = 0
)

@Serializable
data class TraceFilterOptions(
    val window: String = "1h",
    val query: String = "",
    val status: String = "all",
    val service: String = "",
    val limit: Int = 40,
    val availableServices: List<String> = emptyList(),
    val availableStatuses: List<String> = listOf("all", "ok", "error", "slow", "active")
)

@Serializable
data class TraceListItem(
    val traceId: String,
    val operation: String,
    val service: String,
    val startEpochMs: Long,
    val durationMs: Long = 0,
    val spanCount: Int = 0,
    val status: String = "UNSET",
    val badgeLabel: String = "UNSET",
    val httpStatusCode: Int? = null,
    val errorCount: Int = 0,
    val activeSpanCount: Int = 0,
    val slow: Boolean = false
)

@Serializable
data class TraceSummarySnapshot(
    val summary: TraceDashboardSummary = TraceDashboardSummary()
)

@Serializable
data class TraceListSnapshot(
    val filters: TraceFilterOptions = TraceFilterOptions(),
    val traces: List<TraceListItem> = emptyList(),
    val selectedTraceId: String? = null
)

@Serializable
data class TraceTimelineMark(
    val label: String,
    val offsetPercent: Double
)

@Serializable
data class TraceTimelineSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val service: String,
    val operation: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val durationMs: Long? = null,
    val status: String,
    val depth: Int = 0,
    val leftPercent: Double = 0.0,
    val widthPercent: Double = 0.0,
    val host: String? = null,
    val protocol: String? = null,
    val component: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class TraceTimeline(
    val traceId: String,
    val durationMs: Long = 0,
    val spanCount: Int = 0,
    val startedAtEpochMs: Long = 0,
    val marks: List<TraceTimelineMark> = emptyList(),
    val spans: List<TraceTimelineSpan> = emptyList()
)

@Serializable
data class TraceTimelineSnapshot(
    val selectedTraceId: String? = null,
    val timeline: TraceTimeline? = null
)

@Serializable
data class TraceSpanDetail(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val service: String,
    val operation: String,
    val status: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val durationMs: Long? = null,
    val host: String? = null,
    val protocol: String? = null,
    val component: String? = null,
    val edgeFrom: String? = null,
    val edgeTo: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class TraceSpanDetailSnapshot(
    val selectedTraceId: String? = null,
    val selectedSpanId: String? = null,
    val spanDetail: TraceSpanDetail? = null
)

@Serializable
data class TraceDashboardSnapshot(
    val summary: TraceDashboardSummary = TraceDashboardSummary(),
    val filters: TraceFilterOptions = TraceFilterOptions(),
    val traces: List<TraceListItem> = emptyList(),
    val selectedTraceId: String? = null,
    val selectedSpanId: String? = null,
    val timeline: TraceTimeline? = null,
    val spanDetail: TraceSpanDetail? = null
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
data class LatencyHistogramBucket(
    val label: String,
    val maxValueMs: Long,
    val count: Int,
    val percentOfTotal: Double
)

@Serializable
data class LatencyHistogram(
    val buckets: List<LatencyHistogramBucket>,
    val totalCount: Int,
    val avgMs: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long
)

@Serializable
data class LatencyMetrics(
    val avgMs: Double = 0.0,
    val p95Ms: Long = 0,
    val p99Ms: Long = 0,
    val errorRate: Double = 0.0,
    val completedSpanCount: Int = 0,
    val histogram: LatencyHistogram? = null
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
    val errorCount: Int = 0,
    val loadPercent: Int = 0,
    val memoryPressurePercent: Int = 0,
    val uptimeMs: Long? = null,
    val processAlive: Boolean? = null,
    val lastHealthLatencyMs: Long? = null,
    val lastAdminLatencyMs: Long? = null,
    val lastEventAtEpochMs: Long? = null
)

@Serializable
data class NodeAssetMetadata(
    val assetId: String? = null,
    val address: String? = null,
    val zone: String? = null,
    val region: String? = null,
    val role: String? = null,
    val roleDescription: String? = null,
    val featured: Boolean = false
)

@Serializable
data class NodeResourceMetrics(
    val cpuPercent: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryMaxBytes: Long? = null,
    val memoryPercent: Double? = null,
    val metricsDerived: Boolean = false
)

@Serializable
data class NodeDashboardItem(
    val node: JvmNode,
    val summary: NodeSummary,
    val asset: NodeAssetMetadata,
    val resource: NodeResourceMetrics,
    val degradationReason: String? = null,
    val primaryAction: String,
    val secondaryAction: String? = null
)

@Serializable
data class ClusterInsightMetrics(
    val overallUtilizationPercent: Double = 0.0,
    val throughputPerSecond: Double = 0.0,
    val networkErrorRatePercent: Double = 0.0,
    val uptimeScore: Double = 0.0
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
    val service: String? = null,
    val cluster: String? = null,
    val instance: String? = null,
    val payload: JsonElement? = null,
    val meta: JsonElement? = null,
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class LogSnapshotPage(
    val items: List<StructuredLogRecord>,
    val total: Int,
    val limit: Int,
    val page: Int = 1,
    val pageSize: Int = limit,
    val totalPages: Int = if (total == 0) 0 else 1,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false
)

@Serializable
data class LogHistogramBucket(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val totalCount: Int,
    val levelCounts: Map<String, Int> = emptyMap()
)

@Serializable
data class LogHistogramSnapshot(
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val bucketSizeMs: Long,
    val buckets: List<LogHistogramBucket>
)

@Serializable
data class LogExplorerSummary(
    val totalMatched: Int,
    val showingCount: Int,
    val availableLevels: List<String> = emptyList(),
    val activeWindow: String = "1h"
)

@Serializable
data class LogExplorerSnapshot(
    val page: LogSnapshotPage,
    val histogram: LogHistogramSnapshot,
    val summary: LogExplorerSummary,
    val availableLevels: List<String> = emptyList()
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
    val cluster: ClusterInsightMetrics = ClusterInsightMetrics(),
    val nodes: List<NodeSummary>
)

@Serializable
data class NodeDashboardSnapshot(
    val windowMs: Long,
    val activeCount: Int,
    val degradedCount: Int,
    val pageTotal: Int,
    val featuredNodeId: String? = null,
    val throughputPerSecond: Double = 0.0,
    val networkThroughputBytesPerSecond: Double? = null,
    val networkErrorRatePercent: Double = 0.0,
    val uptimeScore: Double = 0.0,
    val items: List<NodeDashboardItem>
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

fun PluginNodeAssetMetadata.toNodeAssetMetadata(): NodeAssetMetadata = NodeAssetMetadata(
    assetId = assetId,
    address = address,
    zone = zone,
    region = region,
    role = role,
    roleDescription = roleDescription,
    featured = featured
)
