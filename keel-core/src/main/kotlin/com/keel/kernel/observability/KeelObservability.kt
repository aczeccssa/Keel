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
    fun traceSummarySnapshot(
        windowKey: String = "1h",
        query: String? = null,
        status: String? = null,
        service: String? = null
    ): TraceSummarySnapshot
    fun traceListSnapshot(
        windowKey: String = "1h",
        query: String? = null,
        status: String? = null,
        service: String? = null,
        limit: Int = 40,
        selectedTraceId: String? = null
    ): TraceListSnapshot
    fun traceTimelineSnapshot(
        windowKey: String = "1h",
        query: String? = null,
        status: String? = null,
        service: String? = null,
        limit: Int = 40,
        selectedTraceId: String? = null
    ): TraceTimelineSnapshot
    fun traceSpanDetailSnapshot(
        windowKey: String = "1h",
        query: String? = null,
        status: String? = null,
        service: String? = null,
        limit: Int = 40,
        selectedTraceId: String? = null,
        selectedSpanId: String? = null
    ): TraceSpanDetailSnapshot
    fun traceDashboardSnapshot(
        windowKey: String = "1h",
        query: String? = null,
        status: String? = null,
        service: String? = null,
        limit: Int = 40,
        selectedTraceId: String? = null,
        selectedSpanId: String? = null
    ): TraceDashboardSnapshot
    fun flowSnapshot(limit: Int = 100): List<FlowEvent>
    fun metricsSnapshot(windowMs: Long = 15 * 60 * 1000L): ObservabilityMetricsSnapshot
    fun nodeSummarySnapshot(windowMs: Long = 15 * 60 * 1000L): List<NodeSummary>
    fun nodeDashboardSnapshot(windowMs: Long = 15 * 60 * 1000L): NodeDashboardSnapshot
    fun logSnapshot(
        limit: Int = 100,
        query: String? = null,
        level: String? = null,
        source: String? = null,
        sinceEpochMs: Long? = null,
        page: Int = 1,
        pageSize: Int = limit,
        windowKey: String = "1h"
    ): LogSnapshotPage
    fun logExplorerSnapshot(
        query: String? = null,
        level: String? = null,
        source: String? = null,
        sinceEpochMs: Long? = null,
        page: Int = 1,
        pageSize: Int = 100,
        windowKey: String = "1h"
    ): LogExplorerSnapshot
    fun events(): Flow<ObservabilityStreamEvent>
}
