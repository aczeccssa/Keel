package com.keel.kernel.observability

import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogLevel
import com.keel.kernel.plugin.PluginGeneration
import com.keel.kernel.plugin.PluginHealthState
import com.keel.kernel.plugin.PluginLifecycleState
import com.keel.kernel.plugin.PluginRuntimeDiagnostics
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.PluginRuntimeSnapshot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun pluginSnapshot(
        pluginId: String,
        displayName: String,
        runtimeMode: PluginRuntimeMode = PluginRuntimeMode.IN_PROCESS,
        inflight: Int = 0,
        queueDepth: Int = 0,
        droppedLogs: Long = 0
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
                droppedLogCount = droppedLogs
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
        edgeFrom: String? = null,
        edgeTo: String? = null
    ): TraceSpanEvent {
        return TraceSpanEvent(
            traceId = traceId,
            spanId = spanId,
            service = service,
            operation = "call/$service",
            startEpochMs = startEpochMs,
            endEpochMs = startEpochMs + durationMs,
            durationMs = durationMs,
            status = status,
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
