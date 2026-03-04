package com.keel.samples.observability

import com.keel.kernel.observability.FlowEvent
import com.keel.kernel.observability.JvmNode
import com.keel.kernel.observability.PanelInfo
import com.keel.kernel.observability.TraceSpanEvent
import com.keel.openapi.annotations.KeelApiField
import com.keel.openapi.annotations.KeelApiSchema
import kotlinx.serialization.Serializable

@KeelApiSchema
@Serializable
data class ObservabilityTopologyData(
    @KeelApiField("Visible JVM nodes for the current kernel and its plugins", "[]")
    val nodes: List<JvmNode>
)

@KeelApiSchema
@Serializable
data class ObservabilityTraceData(
    @KeelApiField("Recent trace spans captured in memory", "[]")
    val spans: List<TraceSpanEvent>
)

@KeelApiSchema
@Serializable
data class ObservabilityFlowData(
    @KeelApiField("Recent cross-JVM flow edges captured in memory", "[]")
    val flows: List<FlowEvent>
)

@KeelApiSchema
@Serializable
data class ObservabilityPanelData(
    @KeelApiField("Registered custom observability panels", "[]")
    val panels: List<PanelInfo>
)

@KeelApiSchema
@Serializable
data class RedirectMessage(
    @KeelApiField("Redirect message", "Open the UI page")
    val message: String
)
