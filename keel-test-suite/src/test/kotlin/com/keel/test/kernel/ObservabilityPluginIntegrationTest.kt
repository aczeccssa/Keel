package com.keel.test.kernel

import com.keel.kernel.observability.FlowEvent
import com.keel.kernel.observability.JvmNode
import com.keel.kernel.observability.KeelObservability
import com.keel.kernel.observability.KernelProcessMetrics
import com.keel.kernel.observability.LatencyMetrics
import com.keel.kernel.observability.LogSnapshotPage
import com.keel.kernel.observability.NodeSummary
import com.keel.kernel.observability.ObservabilityMetricsSnapshot
import com.keel.kernel.observability.ObservabilityStreamEvent
import com.keel.kernel.observability.PanelInfo
import com.keel.kernel.observability.StructuredLogRecord
import com.keel.kernel.observability.TopEdgeMetric
import com.keel.kernel.observability.TraceSpanEvent
import com.keel.kernel.observability.TrafficMetrics
import com.keel.samples.observability.ObservabilityPlugin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservabilityPluginIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private var koinStarted = false

    @AfterTest
    fun tearDown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun `metrics nodes and logs endpoints expose observability snapshots`() = testApplication {
        val fake = FakeKeelObservability()
        val koin = startKoin {
            modules(module {
                single<KeelObservability> { fake }
            })
        }.also { koinStarted = true }.koin

        val manager = com.keel.kernel.plugin.UnifiedPluginManager(koin)
        manager.registerPlugin(ObservabilityPlugin())

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            routing {
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("observability")
            }
        }

        val metricsResponse = client.get("/api/plugins/observability/metrics?windowMs=60000")
        assertEquals(HttpStatusCode.OK, metricsResponse.status)
        val metrics = payload(metricsResponse.bodyAsText()).jsonObject["snapshot"]!!.jsonObject
        assertEquals(2, metrics["runtimeNodeCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, metrics["externalNodeCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, metrics["totalInflight"]!!.jsonPrimitive.content.toInt())

        val nodesResponse = client.get("/api/plugins/observability/nodes?windowMs=60000")
        assertEquals(HttpStatusCode.OK, nodesResponse.status)
        val nodes = payload(nodesResponse.bodyAsText()).jsonObject["nodes"]!!.jsonArray
        assertEquals(2, nodes.size)
        assertEquals("Auth Gateway", nodes[0].jsonObject["node"]!!.jsonObject["label"]!!.jsonPrimitive.content)

        val logsResponse = client.get("/api/plugins/observability/logs?query=latency&level=ERROR&source=auth")
        assertEquals(HttpStatusCode.OK, logsResponse.status)
        val page = payload(logsResponse.bodyAsText()).jsonObject["page"]!!.jsonObject
        assertEquals(1, page["total"]!!.jsonPrimitive.content.toInt())
        val item = page["items"]!!.jsonArray.single().jsonObject
        assertEquals("auth-plugin", item["pluginId"]!!.jsonPrimitive.content)
        assertEquals("trace-7", item["traceId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `topology traces flows panels and stream remain compatible`() = testApplication {
        val fake = FakeKeelObservability()
        val koin = startKoin {
            modules(module {
                single<KeelObservability> { fake }
            })
        }.also { koinStarted = true }.koin

        val manager = com.keel.kernel.plugin.UnifiedPluginManager(koin)
        manager.registerPlugin(ObservabilityPlugin())

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            routing {
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("observability")
            }
        }

        val topologyResponse = client.get("/api/plugins/observability/topology")
        assertEquals(HttpStatusCode.OK, topologyResponse.status)
        assertEquals(2, payload(topologyResponse.bodyAsText()).jsonObject["nodes"]!!.jsonArray.size)

        val tracesResponse = client.get("/api/plugins/observability/traces?limit=10")
        assertEquals(HttpStatusCode.OK, tracesResponse.status)
        assertEquals(1, payload(tracesResponse.bodyAsText()).jsonObject["spans"]!!.jsonArray.size)

        val flowsResponse = client.get("/api/plugins/observability/flows?limit=10")
        assertEquals(HttpStatusCode.OK, flowsResponse.status)
        assertEquals(1, payload(flowsResponse.bodyAsText()).jsonObject["flows"]!!.jsonArray.size)

        val panelsResponse = client.get("/api/plugins/observability/panels")
        assertEquals(HttpStatusCode.OK, panelsResponse.status)
        val panels = payload(panelsResponse.bodyAsText()).jsonObject["panels"]!!.jsonArray
        assertTrue(panels.any { it.jsonObject["id"]!!.jsonPrimitive.content == "observability-topology" })

        val streamResponse = client.get("/api/plugins/observability/stream")
        assertEquals(HttpStatusCode.OK, streamResponse.status)
        assertTrue(streamResponse.headers["Content-Type"]?.startsWith("text/event-stream") == true)
        val streamText = streamResponse.bodyAsText()
        assertTrue(streamText.contains("event: system"))
        assertTrue(streamText.contains("event: trace_event"))
        assertTrue(streamText.contains("trace-7"))
    }

    private fun payload(body: String) = json.parseToJsonElement(body).jsonObject["data"]!!

    private class FakeKeelObservability : KeelObservability {
        private val nodes = listOf(
            JvmNode(
                id = "kernel",
                kind = "kernel",
                label = "Kernel JVM",
                runtimeMode = "IN_PROCESS",
                healthState = "HEALTHY",
                lifecycleState = "RUNNING"
            ),
            JvmNode(
                id = "auth-plugin",
                kind = "plugin",
                label = "Auth Gateway",
                pluginId = "auth-plugin",
                runtimeMode = "EXTERNAL_JVM",
                healthState = "HEALTHY",
                lifecycleState = "RUNNING",
                inflightInvocations = 7,
                eventQueueDepth = 3,
                droppedLogCount = 2
            )
        )

        private val traces = listOf(
            TraceSpanEvent(
                traceId = "trace-7",
                spanId = "span-2",
                service = "auth-plugin",
                operation = "POST /session/login",
                startEpochMs = 1_710_000_000_000,
                endEpochMs = 1_710_000_000_142,
                durationMs = 142,
                status = "ERROR",
                edgeFrom = "kernel",
                edgeTo = "auth-plugin"
            )
        )

        private val flows = listOf(
            FlowEvent(
                traceId = "trace-7",
                spanId = "span-2",
                service = "kernel",
                operation = "rpc.invoke",
                startEpochMs = 1_710_000_000_000,
                endEpochMs = 1_710_000_000_142,
                status = "ERROR",
                edgeFrom = "kernel",
                edgeTo = "auth-plugin"
            )
        )

        private val panels = mutableListOf<PanelInfo>()

        override fun emitCustomEvent(type: String, payload: kotlinx.serialization.json.JsonElement) = Unit

        override fun tagCurrentSpan(key: String, value: String) = Unit

        override fun registerPanel(id: String, title: String, dataEndpoint: String) {
            panels += PanelInfo(id = id, title = title, dataEndpoint = dataEndpoint)
        }

        override fun panels(): List<PanelInfo> = panels.toList()

        override fun jvmSnapshot(): List<JvmNode> = nodes

        override fun traceSnapshot(limit: Int, sinceEpochMs: Long?): List<TraceSpanEvent> = traces.takeLast(limit)

        override fun flowSnapshot(limit: Int): List<FlowEvent> = flows.takeLast(limit)

        override fun metricsSnapshot(windowMs: Long): ObservabilityMetricsSnapshot {
            return ObservabilityMetricsSnapshot(
                windowMs = windowMs,
                runtimeNodeCount = 2,
                externalNodeCount = 1,
                totalInflight = 7,
                totalQueueDepth = 3,
                droppedLogCount = 2,
                kernel = KernelProcessMetrics(
                    processCpuLoad = 42.8,
                    systemLoadAverage = 6.3,
                    availableProcessors = 8,
                    heapUsedBytes = 8_000_000,
                    heapMaxBytes = 16_000_000,
                    heapUsedPercent = 50.0,
                    threadCount = 24
                ),
                latency = LatencyMetrics(
                    avgMs = 142.0,
                    p95Ms = 142,
                    p99Ms = 142,
                    errorRate = 100.0,
                    completedSpanCount = 1
                ),
                traffic = TrafficMetrics(
                    recentFlowCount = 1,
                    recentTraceCount = 1,
                    topEdges = listOf(
                        TopEdgeMetric(edgeFrom = "kernel", edgeTo = "auth-plugin", count = 1, errorCount = 1)
                    )
                ),
                nodes = nodeSummarySnapshot(windowMs)
            )
        }

        override fun nodeSummarySnapshot(windowMs: Long): List<NodeSummary> {
            return listOf(
                NodeSummary(node = nodes[1], recentFlowCount = 1, recentTraceCount = 1, errorCount = 1),
                NodeSummary(node = nodes[0], recentFlowCount = 1, recentTraceCount = 1, errorCount = 1)
            )
        }

        override fun logSnapshot(
            limit: Int,
            query: String?,
            level: String?,
            source: String?,
            sinceEpochMs: Long?
        ): LogSnapshotPage {
            return LogSnapshotPage(
                items = listOf(
                    StructuredLogRecord(
                        timestamp = 1_710_000_000_142,
                        level = "ERROR",
                        source = "auth-plugin.http",
                        message = "Latency threshold exceeded",
                        traceId = "trace-7",
                        spanId = "span-2",
                        pluginId = "auth-plugin",
                        attributes = mapOf("cluster" to "prod")
                    )
                ),
                total = 1,
                limit = limit
            )
        }

        override fun events(): Flow<ObservabilityStreamEvent> {
            return flowOf(
                ObservabilityStreamEvent(type = "trace_event", dataJson = """{"traceId":"trace-7","spanId":"span-2"}""")
            )
        }
    }
}
