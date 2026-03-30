package com.keel.samples.observability

import com.keel.kernel.observability.FlowEvent
import com.keel.kernel.observability.JvmNode
import com.keel.kernel.observability.LatencyHistogram
import com.keel.kernel.observability.LogExplorerSnapshot
import com.keel.kernel.observability.NodeDashboardSnapshot
import com.keel.kernel.observability.NodeSummary
import com.keel.kernel.observability.ObservabilityMetricsSnapshot
import com.keel.kernel.observability.PanelInfo
import com.keel.kernel.observability.TraceDashboardSnapshot
import com.keel.kernel.observability.TraceSpanEvent
import com.keel.openapi.annotations.KeelApiField
import com.keel.openapi.annotations.KeelApiSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@KeelApiSchema
@Serializable
data class ObservabilityTopologyTabSnapshot(
    @KeelApiField("Visible JVM nodes for the current kernel and its plugins", "[]")
    val nodes: List<JvmNode>,
    @KeelApiField("Per-node activity summary used by topology and shared chrome", "[]")
    val nodeSummaries: List<NodeSummary>,
    @KeelApiField("Recent trace spans used by the topology panel", "[]")
    val traces: List<TraceSpanEvent>,
    @KeelApiField("Recent flow edges used by the topology panel", "[]")
    val flows: List<FlowEvent>,
    @KeelApiField("Registered custom observability panels", "[]")
    val panels: List<PanelInfo>
)

@KeelApiSchema
@Serializable
data class ObservabilityNodesTabSnapshot(
    @KeelApiField("Dashboard-ready node snapshot for the nodes tab")
    val snapshot: NodeDashboardSnapshot,
    @KeelApiField("Recent trace count surfaced in the nodes overview", "0")
    val recentTraceCount: Int = 0,
    @KeelApiField("Recent flow count surfaced in the nodes overview", "0")
    val recentFlowCount: Int = 0,
    @KeelApiField("Dropped log count surfaced in the nodes overview", "0")
    val droppedLogCount: Long = 0
)

@KeelApiSchema
@Serializable
data class ObservabilityMetricsTabSnapshot(
    @KeelApiField("Aggregated runtime, latency, traffic and node metrics for observability views")
    val snapshot: ObservabilityMetricsSnapshot,
    @KeelApiField("Latency histogram derived from recent completed trace spans")
    val histogram: LatencyHistogram? = null
)

@KeelApiSchema
@Serializable
data class ObservabilityOpenApiTabSnapshot(
    @KeelApiField("Aggregated OpenAPI specification exposed to the observability OpenAPI tab")
    val spec: JsonElement,
    @KeelApiField("Epoch milliseconds when the spec snapshot was generated", "0")
    val generatedAtEpochMs: Long,
    @KeelApiField("Source identifier for the generated OpenAPI snapshot", "_system/docs/openapi.json")
    val source: String
)

@KeelApiSchema
@Serializable
data class ObservabilityStreamSystemData(
    @KeelApiField("Tab identifier associated with the SSE stream", "topology")
    val tab: String,
    @KeelApiField("Applied stream interval in milliseconds", "5000")
    val intervalMs: Long
)

@KeelApiSchema
@Serializable
data class RedirectMessage(
    @KeelApiField("Redirect message", "Open the UI page")
    val message: String
)
