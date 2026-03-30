package com.keel.test.kernel

import com.keel.kernel.observability.FlowEvent
import com.keel.kernel.observability.JvmNode
import com.keel.kernel.observability.KeelObservability
import com.keel.kernel.observability.KernelProcessMetrics
import com.keel.kernel.observability.LatencyHistogram
import com.keel.kernel.observability.LatencyHistogramBucket
import com.keel.kernel.observability.LatencyMetrics
import com.keel.kernel.observability.LogExplorerSnapshot
import com.keel.kernel.observability.LogExplorerSummary
import com.keel.kernel.observability.LogHistogramBucket
import com.keel.kernel.observability.LogHistogramSnapshot
import com.keel.kernel.observability.LogSnapshotPage
import com.keel.kernel.observability.NodeAssetMetadata
import com.keel.kernel.observability.NodeDashboardItem
import com.keel.kernel.observability.NodeDashboardSnapshot
import com.keel.kernel.observability.NodeResourceMetrics
import com.keel.kernel.observability.NodeSummary
import com.keel.kernel.observability.ObservabilityMetricsSnapshot
import com.keel.kernel.observability.ObservabilityStreamEvent
import com.keel.kernel.observability.PanelInfo
import com.keel.kernel.observability.StructuredLogRecord
import com.keel.kernel.observability.TopEdgeMetric
import com.keel.kernel.observability.TraceDashboardSnapshot
import com.keel.kernel.observability.TraceDashboardSummary
import com.keel.kernel.observability.TraceFilterOptions
import com.keel.kernel.observability.TraceListItem
import com.keel.kernel.observability.TraceListSnapshot
import com.keel.kernel.observability.TraceSpanDetail
import com.keel.kernel.observability.TraceSpanDetailSnapshot
import com.keel.kernel.observability.TraceSpanEvent
import com.keel.kernel.observability.TraceTimeline
import com.keel.kernel.observability.TraceTimelineMark
import com.keel.kernel.observability.TraceTimelineSpan
import com.keel.kernel.observability.TraceTimelineSnapshot
import com.keel.kernel.observability.TraceSummarySnapshot
import com.keel.kernel.observability.TrafficMetrics
import com.keel.kernel.routing.docRoutes
import com.keel.samples.observability.ObservabilityPlugin
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    fun `six observability tab endpoints stream system and snapshot events`() = testApplication {
        val fake = FakeKeelObservability()
        val koin = startKoin {
            modules(module {
                single<KeelObservability> { fake }
            })
        }.also { koinStarted = true }.koin

        val manager = com.keel.kernel.plugin.UnifiedPluginManager(koin)
        manager.registerPlugin(ObservabilityPlugin())

        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing {
                docRoutes()
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("observability")
            }
        }

        val (topologySystem, topologySnapshot) = readSseSnapshot(
            client,
            "/api/plugins/observability/topology?intervalMs=100"
        )
        assertEquals("topology", topologySystem["tab"]!!.jsonPrimitive.content)
        assertEquals(1000, topologySystem["intervalMs"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, topologySnapshot["nodes"]!!.jsonArray.size)
        assertEquals(1, topologySnapshot["flows"]!!.jsonArray.size)

        val (tracesSystem, tracesSnapshot) = readSseSnapshot(
            client,
            "/api/plugins/observability/traces?intervalMs=999999&window=1h&status=error&traceId=trace-7&spanId=span-2"
        )
        assertEquals("traces", tracesSystem["tab"]!!.jsonPrimitive.content)
        assertEquals(300000, tracesSystem["intervalMs"]!!.jsonPrimitive.content.toInt())
        assertEquals("trace-7", tracesSnapshot["selectedTraceId"]!!.jsonPrimitive.content)
        assertEquals("span-2", tracesSnapshot["selectedSpanId"]!!.jsonPrimitive.content)

        val (logsSystem, logsSnapshot) = readSseSnapshot(
            client,
            "/api/plugins/observability/logs?intervalMs=1000&query=latency&level=ERROR&page=2&pageSize=20&window=1h"
        )
        assertEquals("logs", logsSystem["tab"]!!.jsonPrimitive.content)
        assertEquals(2, logsSnapshot["page"]!!.jsonObject["page"]!!.jsonPrimitive.content.toInt())
        assertEquals("1h", logsSnapshot["summary"]!!.jsonObject["activeWindow"]!!.jsonPrimitive.content)

        val (nodesSystem, nodesSnapshot) = readSseSnapshot(
            client,
            "/api/plugins/observability/nodes?intervalMs=1000&windowMs=60000"
        )
        assertEquals("nodes", nodesSystem["tab"]!!.jsonPrimitive.content)
        assertEquals(2, nodesSnapshot["snapshot"]!!.jsonObject["activeCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, nodesSnapshot["recentTraceCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, nodesSnapshot["snapshot"]!!.jsonObject["items"]!!.jsonArray.size)

        val (metricsSystem, metricsSnapshot) = readSseSnapshot(
            client,
            "/api/plugins/observability/metrics?intervalMs=1000&windowMs=60000"
        )
        assertEquals("metrics", metricsSystem["tab"]!!.jsonPrimitive.content)
        assertEquals(60000, metricsSnapshot["snapshot"]!!.jsonObject["windowMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, metricsSnapshot["histogram"]!!.jsonObject["totalCount"]!!.jsonPrimitive.content.toInt())

        val (openApiSystem, openApiSnapshot) = readSseSnapshot(
            client,
            "/api/plugins/observability/openapi?intervalMs=1000"
        )
        assertEquals("openapi", openApiSystem["tab"]!!.jsonPrimitive.content)
        assertEquals("/api/_system/docs/openapi.json", openApiSnapshot["source"]!!.jsonPrimitive.content)
        assertTrue(
            openApiSnapshot["spec"]!!
                .jsonObject["paths"]!!
                .jsonObject
                .containsKey("/api/plugins/observability/openapi")
        )
    }

    @Test
    fun `legacy observability endpoints are removed while ui and system docs remain`() = testApplication {
        val fake = FakeKeelObservability()
        val koin = startKoin {
            modules(module {
                single<KeelObservability> { fake }
            })
        }.also { koinStarted = true }.koin

        val manager = com.keel.kernel.plugin.UnifiedPluginManager(koin)
        manager.registerPlugin(ObservabilityPlugin())

        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing {
                docRoutes()
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("observability")
            }
        }

        val legacyPaths = listOf(
            "/api/plugins/observability/stream",
            "/api/plugins/observability/flows",
            "/api/plugins/observability/panels",
            "/api/plugins/observability/metrics/histogram",
            "/api/plugins/observability/nodes/dashboard",
            "/api/plugins/observability/logs/explorer",
            "/api/plugins/observability/traces/dashboard",
            "/api/plugins/observability/traces/summary",
            "/api/plugins/observability/traces/list",
            "/api/plugins/observability/traces/timeline",
            "/api/plugins/observability/traces/span-detail"
        )

        legacyPaths.forEach { path ->
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
        }

        val rootResponse = client.get("/api/plugins/observability")
        assertEquals(HttpStatusCode.OK, rootResponse.status)
        assertTrue(rootResponse.bodyAsText().contains("keel-observability-app"))

        val uiResponse = client.get("/api/plugins/observability/ui")
        assertEquals(HttpStatusCode.OK, uiResponse.status)
        assertTrue(uiResponse.bodyAsText().contains("keel-observability-app"))

        val docsResponse = client.get("/api/_system/docs/openapi.json")
        assertEquals(HttpStatusCode.OK, docsResponse.status)
        assertTrue(docsResponse.bodyAsText().contains("\"openapi\""))
    }

    private suspend fun readSseSnapshot(client: HttpClient, path: String): Pair<JsonObject, JsonObject> {
        return client.prepareGet(path).execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Content-Type"]?.startsWith("text/event-stream") == true)

            val channel = response.bodyAsChannel()
            val events = linkedMapOf<String, String>()
            var currentEvent: String? = null

            withTimeout(2_000) {
                while (events.size < 2) {
                    val line = channel.readUTF8Line() ?: break
                    when {
                        line.startsWith("event: ") -> currentEvent = line.removePrefix("event: ").trim()
                        line.startsWith("data: ") -> {
                            val eventName = currentEvent ?: "message"
                            events.putIfAbsent(eventName, line.removePrefix("data: ").trim())
                            currentEvent = null
                        }
                    }
                }
            }

            val system = json.parseToJsonElement(events.getValue("system")).jsonObject
            val snapshot = json.parseToJsonElement(events.getValue("snapshot")).jsonObject
            system to snapshot
        }
    }

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

        override fun traceSummarySnapshot(
            windowKey: String,
            query: String?,
            status: String?,
            service: String?
        ): TraceSummarySnapshot = TraceSummarySnapshot(
            summary = TraceDashboardSummary(
                totalTraces = 1,
                p99LatencyMs = 142,
                errorRatePercent = 100.0,
                activeSpanCount = 0
            )
        )

        override fun traceListSnapshot(
            windowKey: String,
            query: String?,
            status: String?,
            service: String?,
            limit: Int,
            selectedTraceId: String?
        ): TraceListSnapshot = throw UnsupportedOperationException("unused in regrouped SSE api")

        override fun traceTimelineSnapshot(
            windowKey: String,
            query: String?,
            status: String?,
            service: String?,
            limit: Int,
            selectedTraceId: String?
        ): TraceTimelineSnapshot = TraceTimelineSnapshot(
            selectedTraceId = selectedTraceId ?: "trace-7",
            timeline = timeline()
        )

        override fun traceSpanDetailSnapshot(
            windowKey: String,
            query: String?,
            status: String?,
            service: String?,
            limit: Int,
            selectedTraceId: String?,
            selectedSpanId: String?
        ): TraceSpanDetailSnapshot = TraceSpanDetailSnapshot(
            selectedTraceId = selectedTraceId ?: "trace-7",
            selectedSpanId = selectedSpanId ?: "span-2",
            spanDetail = spanDetail()
        )

        override fun traceDashboardSnapshot(
            windowKey: String,
            query: String?,
            status: String?,
            service: String?,
            limit: Int,
            selectedTraceId: String?,
            selectedSpanId: String?
        ): TraceDashboardSnapshot = TraceDashboardSnapshot(
            summary = TraceDashboardSummary(
                totalTraces = 1,
                p99LatencyMs = 142,
                errorRatePercent = 100.0,
                activeSpanCount = 0
            ),
            filters = TraceFilterOptions(
                window = windowKey,
                query = query.orEmpty(),
                status = status ?: "all",
                service = service.orEmpty(),
                limit = limit,
                availableServices = listOf("auth-plugin")
            ),
            traces = listOf(
                TraceListItem(
                    traceId = "trace-7",
                    operation = "POST /session/login",
                    service = "auth-plugin",
                    startEpochMs = 1_710_000_000_000,
                    durationMs = 142,
                    spanCount = 1,
                    status = "ERROR",
                    badgeLabel = "500 ERR",
                    httpStatusCode = 500,
                    errorCount = 1,
                    activeSpanCount = 0,
                    slow = false
                )
            ),
            selectedTraceId = selectedTraceId ?: "trace-7",
            selectedSpanId = selectedSpanId ?: "span-2",
            timeline = timeline(),
            spanDetail = spanDetail()
        )

        override fun flowSnapshot(limit: Int): List<FlowEvent> = flows.takeLast(limit)

        override fun metricsSnapshot(windowMs: Long): ObservabilityMetricsSnapshot {
            val histogram = LatencyHistogram(
                buckets = listOf(
                    LatencyHistogramBucket(label = "<=250ms", maxValueMs = 250, count = 1, percentOfTotal = 100.0)
                ),
                totalCount = 1,
                avgMs = 142.0,
                p50Ms = 142,
                p95Ms = 142,
                p99Ms = 142
            )

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
                    completedSpanCount = 1,
                    histogram = histogram
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

        override fun nodeSummarySnapshot(windowMs: Long): List<NodeSummary> = listOf(
            NodeSummary(node = nodes[0], recentFlowCount = 1, recentTraceCount = 1, errorCount = 0),
            NodeSummary(node = nodes[1], recentFlowCount = 1, recentTraceCount = 1, errorCount = 1)
        )

        override fun nodeDashboardSnapshot(windowMs: Long): NodeDashboardSnapshot = NodeDashboardSnapshot(
            windowMs = windowMs,
            activeCount = 2,
            degradedCount = 0,
            pageTotal = 2,
            featuredNodeId = "kernel",
            throughputPerSecond = 1.0,
            networkErrorRatePercent = 0.0,
            uptimeScore = 100.0,
            items = listOf(
                NodeDashboardItem(
                    node = nodes[0],
                    summary = nodeSummarySnapshot(windowMs)[0],
                    asset = NodeAssetMetadata(assetId = "kernel", role = "kernel", roleDescription = "Kernel JVM", featured = true),
                    resource = NodeResourceMetrics(cpuPercent = 12.0, memoryUsedBytes = 4_000_000, memoryMaxBytes = 16_000_000, memoryPercent = 25.0),
                    primaryAction = "inspect"
                ),
                NodeDashboardItem(
                    node = nodes[1],
                    summary = nodeSummarySnapshot(windowMs)[1],
                    asset = NodeAssetMetadata(assetId = "auth-plugin", role = "auth", roleDescription = "Auth Gateway"),
                    resource = NodeResourceMetrics(cpuPercent = 55.0, memoryUsedBytes = 6_000_000, memoryMaxBytes = 16_000_000, memoryPercent = 37.5),
                    primaryAction = "inspect"
                )
            )
        )

        override fun logSnapshot(
            limit: Int,
            query: String?,
            level: String?,
            source: String?,
            sinceEpochMs: Long?,
            page: Int,
            pageSize: Int,
            windowKey: String
        ): LogSnapshotPage = LogSnapshotPage(
            items = listOf(logRecord()),
            total = 1,
            limit = limit,
            page = page,
            pageSize = pageSize,
            totalPages = 1,
            hasPrev = false,
            hasNext = false
        )

        override fun logExplorerSnapshot(
            query: String?,
            level: String?,
            source: String?,
            sinceEpochMs: Long?,
            page: Int,
            pageSize: Int,
            windowKey: String
        ): LogExplorerSnapshot = LogExplorerSnapshot(
            page = LogSnapshotPage(
                items = listOf(logRecord()),
                total = 21,
                limit = pageSize,
                page = page,
                pageSize = pageSize,
                totalPages = 2,
                hasPrev = page > 1,
                hasNext = page < 2
            ),
            histogram = LogHistogramSnapshot(
                windowStartEpochMs = 1_710_000_000_000,
                windowEndEpochMs = 1_710_000_003_600,
                bucketSizeMs = 1_000,
                buckets = listOf(
                    LogHistogramBucket(
                        startEpochMs = 1_710_000_000_000,
                        endEpochMs = 1_710_000_001_000,
                        totalCount = 1,
                        levelCounts = mapOf("ERROR" to 1)
                    )
                )
            ),
            summary = LogExplorerSummary(
                totalMatched = 21,
                showingCount = 1,
                availableLevels = listOf("ERROR"),
                activeWindow = windowKey
            ),
            availableLevels = listOf("ERROR")
        )

        override fun events(): Flow<ObservabilityStreamEvent> = emptyFlow()

        private fun timeline(): TraceTimeline = TraceTimeline(
            traceId = "trace-7",
            durationMs = 142,
            spanCount = 1,
            startedAtEpochMs = 1_710_000_000_000,
            marks = listOf(
                TraceTimelineMark("0ms", 0.0),
                TraceTimelineMark("142ms", 100.0)
            ),
            spans = listOf(
                TraceTimelineSpan(
                    traceId = "trace-7",
                    spanId = "span-2",
                    service = "auth-plugin",
                    operation = "POST /session/login",
                    startEpochMs = 1_710_000_000_000,
                    endEpochMs = 1_710_000_000_142,
                    durationMs = 142,
                    status = "ERROR",
                    widthPercent = 100.0,
                    host = "10.0.0.11",
                    protocol = "HTTP",
                    component = "io.opentelemetry.java"
                )
            )
        )

        private fun spanDetail(): TraceSpanDetail = TraceSpanDetail(
            traceId = "trace-7",
            spanId = "span-2",
            service = "auth-plugin",
            operation = "POST /session/login",
            status = "ERROR",
            startEpochMs = 1_710_000_000_000,
            endEpochMs = 1_710_000_000_142,
            durationMs = 142,
            host = "10.0.0.11",
            protocol = "HTTP",
            component = "io.opentelemetry.java",
            edgeFrom = "kernel",
            edgeTo = "auth-plugin"
        )

        private fun logRecord(): StructuredLogRecord = StructuredLogRecord(
            timestamp = 1_710_000_000_142,
            level = "ERROR",
            source = "auth-plugin.http",
            message = "Latency threshold exceeded",
            traceId = "trace-7",
            spanId = "span-2",
            pluginId = "auth-plugin",
            attributes = mapOf("cluster" to "prod")
        )
    }
}
