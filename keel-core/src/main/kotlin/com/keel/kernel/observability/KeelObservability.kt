package com.keel.kernel.observability

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

interface KeelObservability {
    fun emitCustomEvent(type: String, payload: JsonElement)
    fun tagCurrentSpan(key: String, value: String)
    fun registerPanel(id: String, title: String, dataEndpoint: String)
    fun panels(): List<PanelInfo>
    fun jvmSnapshot(): List<JvmNode>
    fun traceSnapshot(limit: Int = 100, sinceEpochMs: Long? = null): List<TraceSpanEvent>
    fun flowSnapshot(limit: Int = 100): List<FlowEvent>
    fun metricsSnapshot(windowMs: Long = 15 * 60 * 1000L): ObservabilityMetricsSnapshot
    fun nodeSummarySnapshot(windowMs: Long = 15 * 60 * 1000L): List<NodeSummary>
    fun logSnapshot(
        limit: Int = 100,
        query: String? = null,
        level: String? = null,
        source: String? = null,
        sinceEpochMs: Long? = null
    ): LogSnapshotPage
    fun events(): Flow<ObservabilityStreamEvent>
}
