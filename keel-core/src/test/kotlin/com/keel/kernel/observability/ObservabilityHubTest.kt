package com.keel.kernel.observability

import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogLevel
import com.keel.kernel.plugin.PluginGeneration
import com.keel.kernel.plugin.PluginHealthState
import com.keel.kernel.plugin.PluginLifecycleState
import com.keel.kernel.plugin.PluginNodeAssetMetadata
import com.keel.kernel.plugin.PluginRuntimeDiagnostics
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.PluginRuntimeSnapshot
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObservabilityHubTest {

    private val logger = KeelLoggerService.getInstance()

    @BeforeTest
    fun setUp() {
        logger.clear()
        logger.setLevel(LogLevel.DEBUG)
    }

    @AfterTest
    fun tearDown() {
        logger.clear()
        logger.setLevel(LogLevel.INFO)
    }

    @Test
    fun metricsSnapshotAggregatesRuntimeLatencyAndTraffic() {
        val hub = ObservabilityHub(
            ObservabilityConfig(
                traceBufferSize = 32,
                flowBufferSize = 32,
                statusPollIntervalMs = 10_000
            )
        )
        hub.setPluginSnapshotProvider {
            listOf(
                pluginSnapshot(
                    pluginId = "auth-plugin",
                    displayName = "Auth Gateway",
                    runtimeMode = PluginRuntimeMode.EXTERNAL_JVM,
                    inflight = 4,
                    queueDepth = 2,
                    droppedLogs = 7
                ),
                pluginSnapshot(
                    pluginId = "inventory-plugin",
                    displayName = "Inventory Service",
                    runtimeMode = PluginRuntimeMode.IN_PROCESS,
                    inflight = 2,
                    queueDepth = 3,
                    droppedLogs = 5
                )
            )
        }

        val now = System.currentTimeMillis()
        hub.recordSpan(
            trace(
                traceId = "trace-1",
                spanId = "span-1",
                service = "auth-plugin",
                startEpochMs = now - 4_000,
                durationMs = 10,
                status = "OK",
                edgeFrom = "kernel",
                edgeTo = "auth-plugin"
            )
        )
        hub.recordSpan(
            trace(
                traceId = "trace-2",
                spanId = "span-2",
                service = "inventory-plugin",
                startEpochMs = now - 3_000,
                durationMs = 20,
                status = "ERROR",
                edgeFrom = "auth-plugin",
                edgeTo = "inventory-plugin"
            )
        )
        hub.recordSpan(
            trace(
                traceId = "trace-3",
                spanId = "span-3",
                service = "auth-plugin",
                startEpochMs = now - 2_000,
                durationMs = 300,
                status = "OK",
                edgeFrom = "kernel",
                edgeTo = "auth-plugin"
            )
        )
        hub.recordFlow(
            flow(
                traceId = "trace-4",
                spanId = "span-4",
                startEpochMs = now - 1_500,
                edgeFrom = "kernel",
                edgeTo = "auth-plugin",
                status = "ERROR"
            )
        )

        val snapshot = hub.metricsSnapshot(windowMs = 60_000)

        assertEquals(3, snapshot.runtimeNodeCount)
        assertEquals(1, snapshot.externalNodeCount)
        assertEquals(6, snapshot.totalInflight)
        assertEquals(5, snapshot.totalQueueDepth)
        assertEquals(12, snapshot.droppedLogCount)
        assertEquals(3, snapshot.latency.completedSpanCount)
        assertEquals(110.0, snapshot.latency.avgMs)
        assertEquals(300L, snapshot.latency.p95Ms)
        assertEquals(300L, snapshot.latency.p99Ms)
        assertTrue(snapshot.latency.errorRate in 33.0..34.0)
        assertEquals(4, snapshot.traffic.recentFlowCount)
        assertEquals(3, snapshot.traffic.recentTraceCount)
        assertEquals("kernel", snapshot.traffic.topEdges.first().edgeFrom)
        assertEquals("auth-plugin", snapshot.traffic.topEdges.first().edgeTo)
        assertEquals(3, snapshot.traffic.topEdges.first().count)
        assertEquals(1, snapshot.traffic.topEdges.first().errorCount)
        assertEquals(3, snapshot.nodes.size)
        assertTrue(snapshot.kernel.threadCount > 0)
        assertTrue(snapshot.kernel.heapUsedBytes >= 0)
    }

    @Test
    fun nodeSummarySnapshotMergesNodeTrafficAndErrors() {
        val hub = ObservabilityHub(ObservabilityConfig(traceBufferSize = 32, flowBufferSize = 32))
        hub.setPluginSnapshotProvider {
            listOf(
                pluginSnapshot(pluginId = "auth-plugin", displayName = "Auth Gateway"),
                pluginSnapshot(pluginId = "inventory-plugin", displayName = "Inventory Service")
            )
        }

        val now = System.currentTimeMillis()
        hub.recordSpan(
            trace(
                traceId = "trace-a",
                spanId = "span-a",
                service = "auth-plugin",
                startEpochMs = now - 5_000,
                durationMs = 18,
                status = "ERROR",
                edgeFrom = "kernel",
                edgeTo = "auth-plugin"
            )
        )
        hub.recordSpan(
            trace(
                traceId = "trace-b",
                spanId = "span-b",
                service = "inventory-plugin",
                startEpochMs = now - 4_000,
                durationMs = 22,
                status = "OK",
                edgeFrom = "auth-plugin",
                edgeTo = "inventory-plugin"
            )
        )
        hub.recordFlow(
            flow(
                traceId = "trace-c",
                spanId = "span-c",
                startEpochMs = now - 3_000,
                edgeFrom = "inventory-plugin",
                edgeTo = "kernel"
            )
        )

        val summaries = hub.nodeSummarySnapshot(windowMs = 60_000)
        val auth = summaries.first { it.node.id == "auth-plugin" }
        val inventory = summaries.first { it.node.id == "inventory-plugin" }
        val kernel = summaries.first { it.node.id == "kernel" }

        assertEquals(2, auth.recentTraceCount)
        assertEquals(2, auth.recentFlowCount)
        assertEquals(1, auth.errorCount)

        assertEquals(1, inventory.recentTraceCount)
        assertEquals(2, inventory.recentFlowCount)
        assertEquals(0, inventory.errorCount)

        assertEquals(1, kernel.recentTraceCount)
        assertEquals(2, kernel.recentFlowCount)
        assertEquals(1, kernel.errorCount)
    }

    @Test
    fun logSnapshotUsesLoggerBufferAndAppliesFilters() {
        val hub = ObservabilityHub()
        hub.setPluginSnapshotProvider {
            listOf(pluginSnapshot(pluginId = "auth-plugin", displayName = "Auth Gateway"))
        }

        logger.log(LogLevel.INFO, "kernel", "Boot complete")
        logger.log(LogLevel.WARN, "auth-plugin.http", "Latency threshold exceeded traceId=trace-123 spanId=span-7")
        logger.log(LogLevel.ERROR, "inventory-plugin.worker", "Inventory write failed")

        val filtered = hub.logSnapshot(
            limit = 10,
            query = "latency",
            level = "warn",
            source = "auth",
            sinceEpochMs = System.currentTimeMillis() - 60_000
        )

        assertEquals(1, filtered.total)
        assertEquals(1, filtered.items.size)
        val item = filtered.items.single()
        assertEquals("WARN", item.level)
        assertEquals("auth-plugin", item.pluginId)
        assertEquals("trace-123", item.traceId)
        assertEquals("span-7", item.spanId)
        assertTrue(item.message.contains("Latency threshold exceeded"))
        assertEquals("auth-plugin", item.attributes["pluginId"])

        val unfiltered = hub.logSnapshot(limit = 2)
        assertEquals(3, unfiltered.total)
        assertEquals(2, unfiltered.items.size)
        val recentSources = unfiltered.items.map { it.source }.toSet()
        assertEquals(setOf("auth-plugin.http", "inventory-plugin.worker"), recentSources)
        assertTrue(unfiltered.items.any { it.pluginId == "auth-plugin" })
    }

    @Test
    fun logExplorerSnapshotBuildsPaginationHistogramAndStructuredFields() {
        val hub = ObservabilityHub()
        hub.setPluginSnapshotProvider {
            listOf(pluginSnapshot(pluginId = "auth-plugin", displayName = "Auth Gateway"))
        }

        logger.log(LogLevel.INFO, "kernel", "Boot complete cluster=prod-a instance=i-kernel")
        logger.log(LogLevel.WARN, "auth-plugin.http", "Latency threshold exceeded traceId=trace-123 spanId=span-7 cluster=prod-a instance=i-auth-1")
        logger.log(LogLevel.ERROR, "auth-plugin.worker", "Retry failed traceId=trace-124 spanId=span-8 cluster=prod-a instance=i-auth-2")

        val snapshot = hub.logExplorerSnapshot(
            query = "trace",
            page = 2,
            pageSize = 1,
            windowKey = "1h",
            sinceEpochMs = System.currentTimeMillis() - 60_000
        )

        assertEquals(2, snapshot.page.total)
        assertEquals(2, snapshot.page.totalPages)
        assertEquals(2, snapshot.page.page)
        assertEquals(1, snapshot.page.pageSize)
        assertTrue(snapshot.page.hasPrev)
        assertEquals("1h", snapshot.summary.activeWindow)
        assertTrue(snapshot.histogram.buckets.isNotEmpty())
        assertEquals(snapshot.page.items.size, snapshot.summary.showingCount)

        val item = snapshot.page.items.single()
        assertEquals("auth-plugin", item.service)
        assertEquals("prod-a", item.cluster)
        assertTrue(item.instance?.startsWith("i-auth") == true)
        assertNotNull(item.payload)
        assertNotNull(item.meta)
        assertTrue(item.payload.jsonObject["traceId"]!!.jsonPrimitive.content.startsWith("trace-"))
        assertEquals("auth-plugin", item.meta.jsonObject["service"]!!.jsonPrimitive.content)
    }

    @Test
    fun nodeDashboardSnapshotBuildsDashboardItemsAndDerivedMetrics() {
        val hub = ObservabilityHub(ObservabilityConfig(traceBufferSize = 32, flowBufferSize = 32))
        hub.setPluginSnapshotProvider {
            listOf(
                pluginSnapshot(
                    pluginId = "auth-plugin",
                    displayName = "Auth Gateway",
                    runtimeMode = PluginRuntimeMode.EXTERNAL_JVM,
                    cpuPercent = 42.0,
                    heapUsedBytes = 256L * 1024 * 1024,
                    heapMaxBytes = 512L * 1024 * 1024,
                    heapUsedPercent = 50.0,
                    assetMetadata = PluginNodeAssetMetadata(
                        assetId = "KEEL-US-01-A",
                        address = "192.168.1.104",
                        zone = "us-east-1a",
                        region = "us-east",
                        role = "auth-gateway",
                        roleDescription = "Primary Compute Node",
                        featured = true
                    )
                ),
                pluginSnapshot(
                    pluginId = "inventory-plugin",
                    displayName = "Inventory Service",
                    runtimeMode = PluginRuntimeMode.IN_PROCESS,
                    inflight = 4,
                    queueDepth = 8,
                    assetMetadata = PluginNodeAssetMetadata(
                        assetId = "KEEL-EU-04-B",
                        address = "10.0.4.12",
                        zone = "eu-west-1b",
                        region = "eu-west",
                        role = "catalog-edge",
                        roleDescription = "Edge Ingress Node"
                    )
                )
            )
        }

        val snapshot = hub.nodeDashboardSnapshot(windowMs = 60_000)

        assertEquals(3, snapshot.pageTotal)
        assertEquals(3, snapshot.activeCount)
        assertEquals(1, snapshot.degradedCount)
        assertEquals("kernel", snapshot.featuredNodeId)
        assertEquals(null, snapshot.networkThroughputBytesPerSecond)

        val auth = snapshot.items.first { it.node.id == "auth-plugin" }
        assertEquals("KEEL-US-01-A", auth.asset.assetId)
        assertEquals(42.0, auth.resource.cpuPercent)
        assertEquals(50.0, auth.resource.memoryPercent)
        assertEquals(false, auth.resource.metricsDerived)
        assertEquals("inspect", auth.primaryAction)

        val inventory = snapshot.items.first { it.node.id == "inventory-plugin" }
        assertEquals(false, inventory.resource.metricsDerived)
        assertEquals(null, inventory.resource.cpuPercent)
        assertEquals(null, inventory.resource.memoryPercent)
        assertTrue(inventory.degradationReason != null)
        assertEquals("inspect", inventory.primaryAction)
        assertEquals("reload", inventory.secondaryAction)
    }

    @Test
    fun traceDashboardSnapshotBuildsSummaryFiltersTimelineAndDetail() {
        val hub = ObservabilityHub(ObservabilityConfig(traceBufferSize = 32, flowBufferSize = 32))
        hub.setPluginSnapshotProvider {
            listOf(
                pluginSnapshot(
                    pluginId = "auth-plugin",
                    displayName = "Auth Gateway",
                    assetMetadata = PluginNodeAssetMetadata(address = "10.0.0.11")
                ),
                pluginSnapshot(
                    pluginId = "inventory-plugin",
                    displayName = "Inventory Service",
                    assetMetadata = PluginNodeAssetMetadata(address = "10.0.0.22")
                )
            )
        }

        val now = System.currentTimeMillis()
        hub.recordSpan(
            trace(
                traceId = "trace-ok",
                spanId = "root-ok",
                service = "auth-plugin",
                startEpochMs = now - 5_000,
                durationMs = 120,
                status = "OK",
                attributes = mapOf(
                    "http.method" to "GET",
                    "http.route" to "/inventory",
                    "http.status_code" to "200",
                    "keel.protocol" to "HTTP / 1.1",
                    "keel.component" to "io.opentelemetry.java"
                ),
                edgeFrom = "kernel",
                edgeTo = "auth-plugin"
            )
        )
        hub.recordSpan(
            trace(
                traceId = "trace-ok",
                spanId = "child-ok",
                service = "inventory-plugin",
                startEpochMs = now - 4_960,
                durationMs = 60,
                status = "OK",
                parentSpanId = "root-ok",
                attributes = mapOf("db.system" to "postgres"),
                edgeFrom = "auth-plugin",
                edgeTo = "inventory-plugin"
            )
        )
        hub.recordSpan(
            trace(
                traceId = "trace-error",
                spanId = "root-error",
                service = "auth-plugin",
                startEpochMs = now - 3_000,
                durationMs = 380,
                status = "ERROR",
                attributes = mapOf(
                    "http.method" to "POST",
                    "http.route" to "/checkout",
                    "http.status_code" to "500"
                ),
                edgeFrom = "kernel",
                edgeTo = "auth-plugin"
            )
        )

        val snapshot = hub.traceDashboardSnapshot(
            windowKey = "1h",
            status = "error",
            limit = 10,
            selectedTraceId = "trace-error"
        )
        val summarySnapshot = hub.traceSummarySnapshot(
            windowKey = "1h",
            status = "error"
        )
        val listSnapshot = hub.traceListSnapshot(
            windowKey = "1h",
            status = "error",
            limit = 10,
            selectedTraceId = "missing-trace"
        )
        val timelineSnapshot = hub.traceTimelineSnapshot(
            windowKey = "1h",
            status = "error",
            limit = 10,
            selectedTraceId = "missing-trace"
        )
        val detailSnapshot = hub.traceSpanDetailSnapshot(
            windowKey = "1h",
            status = "error",
            limit = 10,
            selectedTraceId = "trace-error",
            selectedSpanId = "missing-span"
        )

        assertEquals(1, snapshot.summary.totalTraces)
        assertEquals(380L, snapshot.summary.p99LatencyMs)
        assertEquals(100.0, snapshot.summary.errorRatePercent)
        assertEquals(0, snapshot.summary.activeSpanCount)
        assertEquals(listOf("auth-plugin", "inventory-plugin"), snapshot.filters.availableServices)
        assertEquals(1, snapshot.traces.size)
        assertEquals("trace-error", snapshot.traces.single().traceId)
        assertEquals("500 ERR", snapshot.traces.single().badgeLabel)
        assertEquals("trace-error", snapshot.timeline?.traceId)
        assertEquals(1, snapshot.timeline?.spans?.size)
        assertEquals("POST /checkout", snapshot.timeline?.spans?.single()?.operation)
        assertEquals("10.0.0.11", snapshot.spanDetail?.host)
        assertEquals("HTTP", snapshot.spanDetail?.protocol)
        assertEquals(1, summarySnapshot.summary.totalTraces)
        assertEquals("trace-error", listSnapshot.selectedTraceId)
        assertEquals(1, listSnapshot.traces.size)
        assertEquals("trace-error", timelineSnapshot.selectedTraceId)
        assertEquals("trace-error", timelineSnapshot.timeline?.traceId)
        assertEquals("root-error", detailSnapshot.selectedSpanId)
        assertEquals("POST /checkout", detailSnapshot.spanDetail?.operation)
    }

    private fun pluginSnapshot(
        pluginId: String,
        displayName: String,
        runtimeMode: PluginRuntimeMode = PluginRuntimeMode.IN_PROCESS,
        inflight: Int = 0,
        queueDepth: Int = 0,
        droppedLogs: Long = 0,
        cpuPercent: Double? = null,
        heapUsedBytes: Long? = null,
        heapMaxBytes: Long? = null,
        heapUsedPercent: Double? = null,
        assetMetadata: PluginNodeAssetMetadata? = null
    ): PluginRuntimeSnapshot {
        return PluginRuntimeSnapshot(
            pluginId = pluginId,
            displayName = displayName,
            version = "1.0.0",
            runtimeMode = runtimeMode,
            lifecycleState = PluginLifecycleState.RUNNING,
            healthState = PluginHealthState.HEALTHY,
            generation = PluginGeneration.INITIAL,
            processState = null,
            processId = 4242L,
            diagnostics = PluginRuntimeDiagnostics(
                inflightInvocations = inflight,
                eventQueueDepth = queueDepth,
                droppedLogCount = droppedLogs,
                processCpuLoadPercent = cpuPercent,
                heapUsedBytes = heapUsedBytes,
                heapMaxBytes = heapMaxBytes,
                heapUsedPercent = heapUsedPercent,
                assetMetadata = assetMetadata
            )
        )
    }

    private fun trace(
        traceId: String,
        spanId: String,
        service: String,
        startEpochMs: Long,
        durationMs: Long,
        status: String,
        parentSpanId: String? = null,
        attributes: Map<String, String> = emptyMap(),
        edgeFrom: String? = null,
        edgeTo: String? = null
    ): TraceSpanEvent {
        return TraceSpanEvent(
            traceId = traceId,
            spanId = spanId,
            parentSpanId = parentSpanId,
            service = service,
            operation = "call/$service",
            startEpochMs = startEpochMs,
            endEpochMs = startEpochMs + durationMs,
            durationMs = durationMs,
            status = status,
            attributes = attributes,
            edgeFrom = edgeFrom,
            edgeTo = edgeTo
        )
    }

    private fun flow(
        traceId: String,
        spanId: String,
        startEpochMs: Long,
        edgeFrom: String,
        edgeTo: String,
        status: String = "OK"
    ): FlowEvent {
        return FlowEvent(
            traceId = traceId,
            spanId = spanId,
            service = edgeFrom,
            operation = "flow/$edgeFrom/$edgeTo",
            startEpochMs = startEpochMs,
            endEpochMs = startEpochMs + 10,
            status = status,
            edgeFrom = edgeFrom,
            edgeTo = edgeTo
        )
    }
}
