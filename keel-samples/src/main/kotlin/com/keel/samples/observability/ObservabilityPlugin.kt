package com.keel.samples.observability

import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.observability.KeelObservability
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiDoc
import io.ktor.http.HttpHeaders
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.sse.ServerSentEvent
import java.io.IOException

@KeelApiPlugin(
    pluginId = "observability",
    title = "Observability Plugin",
    description = "Example plugin that visualizes multi-JVM topology and flow tracing",
    version = "1.0.0"
)
class ObservabilityPlugin : StandardKeelPlugin {
    private val logger = KeelLoggerService.getLogger("ObservabilityPlugin")
    private lateinit var observability: KeelObservability

    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "observability",
        version = "1.0.0",
        displayName = "Observability Plugin"
    )

    override suspend fun onInit(context: PluginInitContext) {
        observability = context.kernelKoin.get()
        observability.registerPanel(
            id = "observability-topology",
            title = "Observability Topology",
            dataEndpoint = "/api/plugins/observability/topology"
        )
        logger.info("Initialized observability plugin")
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        get<RedirectMessage>(doc = OpenApiDoc(summary = "Open the observability UI", tags = listOf("observability"))) {
            PluginResult(
                status = 302,
                headers = mapOf(HttpHeaders.Location to listOf("/api/plugins/observability/ui/index.html")),
                body = RedirectMessage("Open the UI page")
            )
        }

        get<ObservabilityTopologyData>("/topology", doc = OpenApiDoc(summary = "Get current JVM topology", tags = listOf("observability"), responseEnvelope = true)) {
            PluginResult(body = ObservabilityTopologyData(nodes = observability.jvmSnapshot()))
        }

        get<ObservabilityTraceData>("/traces", doc = OpenApiDoc(summary = "Get recent trace spans", tags = listOf("observability"), responseEnvelope = true)) {
            val limit = queryParameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
            val since = queryParameters["since"]?.firstOrNull()?.toLongOrNull()
            PluginResult(body = ObservabilityTraceData(spans = observability.traceSnapshot(limit, since)))
        }

        get<ObservabilityFlowData>("/flows", doc = OpenApiDoc(summary = "Get recent flow edges", tags = listOf("observability"), responseEnvelope = true)) {
            val limit = queryParameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
            PluginResult(body = ObservabilityFlowData(flows = observability.flowSnapshot(limit)))
        }

        get<ObservabilityMetricsData>("/metrics", doc = OpenApiDoc(summary = "Get aggregated observability metrics", tags = listOf("observability"), responseEnvelope = true)) {
            val windowMs = queryParameters["windowMs"]?.firstOrNull()?.toLongOrNull() ?: (15 * 60 * 1000L)
            PluginResult(body = ObservabilityMetricsData(snapshot = observability.metricsSnapshot(windowMs)))
        }

        get<ObservabilityNodeSummaryData>("/nodes", doc = OpenApiDoc(summary = "Get observability node summaries", tags = listOf("observability"), responseEnvelope = true)) {
            val windowMs = queryParameters["windowMs"]?.firstOrNull()?.toLongOrNull() ?: (15 * 60 * 1000L)
            PluginResult(body = ObservabilityNodeSummaryData(nodes = observability.nodeSummarySnapshot(windowMs)))
        }

        get<ObservabilityLogData>("/logs", doc = OpenApiDoc(summary = "Get structured logs for the observability explorer", tags = listOf("observability"), responseEnvelope = true)) {
            val limit = queryParameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
            val query = queryParameters["query"]?.firstOrNull()
            val level = queryParameters["level"]?.firstOrNull()
            val source = queryParameters["source"]?.firstOrNull()
            val since = queryParameters["since"]?.firstOrNull()?.toLongOrNull()
            PluginResult(
                body = ObservabilityLogData(
                    page = observability.logSnapshot(
                        limit = limit,
                        query = query,
                        level = level,
                        source = source,
                        sinceEpochMs = since
                    )
                )
            )
        }

        get<ObservabilityPanelData>("/panels", doc = OpenApiDoc(summary = "Get registered observability panels", tags = listOf("observability"), responseEnvelope = true)) {
            PluginResult(body = ObservabilityPanelData(panels = observability.panels()))
        }

        sse("/stream", doc = OpenApiDoc(summary = "Subscribe to live observability events", tags = listOf("observability"))) {
            send(ServerSentEvent(data = """{"type":"connected"}""", event = "system"))
            try {
                observability.events().collect { event ->
                    send(ServerSentEvent(data = event.dataJson, event = event.type))
                }
            } catch (_: ChannelWriteException) {
                // Client disconnected.
            } catch (_: ClosedWriteChannelException) {
                // Client disconnected.
            } catch (_: IOException) {
                // Connection reset or broken pipe.
            }
        }

        staticResources(
            path = "/ui",
            basePackage = "static",
            doc = OpenApiDoc(summary = "Open the observability static UI", tags = listOf("observability")),
            index = "index.html"
        )
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        logger.info("Observability plugin stopped")
    }
}
