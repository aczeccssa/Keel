package com.keel.samples.observability

import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.observability.KeelObservability
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginRequestContext
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.PluginSseSession
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiAggregator
import com.keel.openapi.runtime.OpenApiDoc
import io.ktor.http.HttpHeaders
import io.ktor.sse.ServerSentEvent
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
            com.keel.kernel.plugin.PluginResult(
                status = 302,
                headers = mapOf(HttpHeaders.Location to listOf("/api/plugins/observability/ui/index.html")),
                body = RedirectMessage("Open the UI page")
            )
        }

        sse("/topology", doc = OpenApiDoc(summary = "Subscribe to topology tab snapshots", tags = listOf("observability", "topology"))) {
            streamTab("topology", request.streamIntervalMs()) {
                ObservabilityTopologyTabSnapshot(
                    nodes = observability.jvmSnapshot(),
                    nodeSummaries = observability.nodeSummarySnapshot(OBSERVABILITY_WINDOW_MS),
                    traces = observability.traceSnapshot(limit = TOPOLOGY_TRACE_LIMIT),
                    flows = observability.flowSnapshot(limit = TOPOLOGY_FLOW_LIMIT),
                    panels = observability.panels()
                )
            }
        }

        sse("/traces", doc = OpenApiDoc(summary = "Subscribe to traces tab snapshots", tags = listOf("observability", "traces"))) {
            streamTab("traces", request.streamIntervalMs()) {
                observability.traceDashboardSnapshot(
                    windowKey = request.queryParam("window") ?: "1h",
                    query = request.queryParam("query"),
                    status = request.queryParam("status"),
                    service = request.queryParam("service"),
                    limit = request.queryParam("limit")?.toIntOrNull() ?: TRACE_LIMIT,
                    selectedTraceId = request.queryParam("traceId"),
                    selectedSpanId = request.queryParam("spanId")
                )
            }
        }

        sse("/logs", doc = OpenApiDoc(summary = "Subscribe to logs tab snapshots", tags = listOf("observability", "logs"))) {
            streamTab("logs", request.streamIntervalMs()) {
                val window = request.queryParam("window") ?: "1h"
                observability.logExplorerSnapshot(
                    query = request.queryParam("query"),
                    level = request.queryParam("level"),
                    source = request.queryParam("source"),
                    sinceEpochMs = request.queryParam("since")?.toLongOrNull() ?: sinceFromWindow(window),
                    page = request.queryParam("page")?.toIntOrNull() ?: 1,
                    pageSize = request.queryParam("pageSize")?.toIntOrNull() ?: LOG_PAGE_SIZE,
                    windowKey = window
                )
            }
        }

        sse("/nodes", doc = OpenApiDoc(summary = "Subscribe to nodes tab snapshots", tags = listOf("observability", "nodes"))) {
            streamTab("nodes", request.streamIntervalMs()) {
                val windowMs = request.queryParam("windowMs")?.toLongOrNull() ?: OBSERVABILITY_WINDOW_MS
                val metrics = observability.metricsSnapshot(windowMs)
                ObservabilityNodesTabSnapshot(
                    snapshot = observability.nodeDashboardSnapshot(windowMs),
                    recentTraceCount = metrics.traffic.recentTraceCount,
                    recentFlowCount = metrics.traffic.recentFlowCount,
                    droppedLogCount = metrics.droppedLogCount
                )
            }
        }

        sse("/metrics", doc = OpenApiDoc(summary = "Subscribe to metrics tab snapshots", tags = listOf("observability", "metrics"))) {
            streamTab("metrics", request.streamIntervalMs()) {
                val windowMs = request.queryParam("windowMs")?.toLongOrNull() ?: OBSERVABILITY_WINDOW_MS
                val metrics = observability.metricsSnapshot(windowMs)
                ObservabilityMetricsTabSnapshot(
                    snapshot = metrics,
                    histogram = metrics.latency.histogram
                )
            }
        }

        sse("/openapi", doc = OpenApiDoc(summary = "Subscribe to OpenAPI tab snapshots", tags = listOf("observability", "openapi"))) {
            streamTab("openapi", request.streamIntervalMs()) {
                ObservabilityOpenApiTabSnapshot(
                    spec = json.parseToJsonElement(OpenApiAggregator.buildSpec(request.serverUrl())),
                    generatedAtEpochMs = System.currentTimeMillis(),
                    source = "/api/_system/docs/openapi.json"
                )
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

    private suspend inline fun <reified T> PluginSseSession.streamTab(
        tab: String,
        intervalMs: Long,
        crossinline snapshotProvider: suspend PluginSseSession.() -> T
    ) {
        send(
            ServerSentEvent(
                data = json.encodeToString(ObservabilityStreamSystemData(tab = tab, intervalMs = intervalMs)),
                event = "system"
            )
        )

        try {
            sendSnapshot(snapshotProvider())
            while (currentCoroutineContext().isActive) {
                delay(intervalMs)
                sendSnapshot(snapshotProvider())
            }
        } catch (_: ChannelWriteException) {
            // Client disconnected.
        } catch (_: ClosedWriteChannelException) {
            // Client disconnected.
        } catch (_: IOException) {
            // Connection reset or broken pipe.
        }
    }

    private suspend inline fun <reified T> PluginSseSession.sendSnapshot(payload: T) {
        send(ServerSentEvent(data = json.encodeToString(payload), event = "snapshot"))
    }

    private fun PluginRequestContext.queryParam(name: String): String? =
        queryParameters[name]?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun PluginRequestContext.streamIntervalMs(): Long =
        queryParam("intervalMs")?.toLongOrNull()?.coerceIn(1_000L, 300_000L) ?: 5_000L

    private fun PluginRequestContext.serverUrl(): String {
        val scheme = requestHeaders["X-Forwarded-Proto"]?.firstOrNull()?.ifBlank { null } ?: "http"
        val host = requestHeaders["Host"]?.firstOrNull()?.ifBlank { null } ?: "localhost:8080"
        return "$scheme://$host"
    }

    private fun sinceFromWindow(windowKey: String): Long {
        val now = System.currentTimeMillis()
        val durationMs = when (windowKey) {
            "15m" -> 15 * 60 * 1000L
            "6h" -> 6 * 60 * 60 * 1000L
            "24h" -> 24 * 60 * 60 * 1000L
            else -> 60 * 60 * 1000L
        }
        return now - durationMs
    }

    private companion object {
        val json = Json {
            encodeDefaults = true
            prettyPrint = false
        }

        const val OBSERVABILITY_WINDOW_MS = 60 * 60 * 1000L
        const val TOPOLOGY_TRACE_LIMIT = 120
        const val TOPOLOGY_FLOW_LIMIT = 120
        const val TRACE_LIMIT = 40
        const val LOG_PAGE_SIZE = 20
    }
}
