package com.keel.kernel.observability

import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogEntry
import com.keel.kernel.plugin.PluginRuntimeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.lang.management.ManagementFactory
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class ObservabilityHub(
    private val config: ObservabilityConfig = ObservabilityConfig.fromSystem()
) : KeelObservability {
    private val json = Json { encodeDefaults = true }
    private val panels = ConcurrentHashMap<String, PanelInfo>()
    private val traceBuffer = ArrayDeque<TraceSpanEvent>()
    private val flowBuffer = ArrayDeque<FlowEvent>()
    private val logBuffer = ArrayDeque<StructuredLogRecord>()
    private val eventFlow = MutableSharedFlow<ObservabilityStreamEvent>(extraBufferCapacity = 512)
    private val processMxBean = ManagementFactory.getOperatingSystemMXBean()
    private val memoryMxBean = ManagementFactory.getMemoryMXBean()
    private val threadMxBean = ManagementFactory.getThreadMXBean()

    private var pollJob: Job? = null
    private var logJob: Job? = null
    private var pluginSnapshotProvider: (() -> List<PluginRuntimeSnapshot>)? = null

    override fun events(): SharedFlow<ObservabilityStreamEvent> = eventFlow

    fun setPluginSnapshotProvider(provider: () -> List<PluginRuntimeSnapshot>) {
        pluginSnapshotProvider = provider
    }

    fun start(scope: CoroutineScope) {
        pollJob?.cancel()
        logJob?.cancel()

        pollJob = scope.launch(Dispatchers.Default) {
            while (true) {
                emitSnapshots()
                delay(config.statusPollIntervalMs)
            }
        }

        logJob = scope.launch(Dispatchers.Default) {
            val logger = KeelLoggerService.getInstance()
            logger.logStream().collect { entry ->
                val record = enrichLog(entry)
                synchronized(logBuffer) {
                    logBuffer.addLast(record)
                    while (logBuffer.size > 1000) {
                        logBuffer.removeFirst()
                    }
                }
                val event = record.toLogEvent()
                emitEvent("log_event", json.encodeToString(LogEvent.serializer(), event))
            }
        }
    }

    fun shutdown() {
        pollJob?.cancel()
        logJob?.cancel()
    }

    fun recordSpan(event: TraceSpanEvent) {
        synchronized(traceBuffer) {
            traceBuffer.addLast(event)
            while (traceBuffer.size > config.traceBufferSize) {
                traceBuffer.removeFirst()
            }
        }
        emitEvent("trace_event", json.encodeToString(TraceSpanEvent.serializer(), event))
        if (event.edgeFrom != null && event.edgeTo != null) {
            val flow = FlowEvent(
                traceId = event.traceId,
                spanId = event.spanId,
                parentSpanId = event.parentSpanId,
                service = event.service,
                operation = event.operation,
                startEpochMs = event.startEpochMs,
                endEpochMs = event.endEpochMs,
                status = event.status,
                attributes = event.attributes,
                edgeFrom = event.edgeFrom,
                edgeTo = event.edgeTo
            )
            recordFlow(flow)
        }
    }

    fun recordFlow(event: FlowEvent) {
        synchronized(flowBuffer) {
            flowBuffer.addLast(event)
            while (flowBuffer.size > config.flowBufferSize) {
                flowBuffer.removeFirst()
            }
        }
        emitEvent("flow_event", json.encodeToString(FlowEvent.serializer(), event))
    }

    override fun traceSnapshot(limit: Int, sinceEpochMs: Long?): List<TraceSpanEvent> {
        val items = synchronized(traceBuffer) { traceBuffer.toList() }
        val filtered = sinceEpochMs?.let { epoch ->
            items.filter { it.startEpochMs >= epoch }
        } ?: items
        return filtered.takeLast(limit)
    }

    override fun flowSnapshot(limit: Int): List<FlowEvent> {
        return synchronized(flowBuffer) { flowBuffer.toList().takeLast(limit) }
    }

    override fun metricsSnapshot(windowMs: Long): ObservabilityMetricsSnapshot {
        val nodes = jvmSnapshot()
        val windowStart = System.currentTimeMillis() - windowMs
        val recentTraces = traceSnapshot(limit = config.traceBufferSize, sinceEpochMs = windowStart)
        val recentFlows = synchronized(flowBuffer) { flowBuffer.filter { it.startEpochMs >= windowStart } }
        val completedDurations = recentTraces.mapNotNull { it.durationMs }.sorted()
        val errorCount = recentTraces.count { it.status.equals("ERROR", ignoreCase = true) }
        val totalInflight = nodes.sumOf { it.inflightInvocations }
        val totalQueueDepth = nodes.sumOf { it.eventQueueDepth }
        val droppedLogCount = nodes.sumOf { it.droppedLogCount }
        val topEdges = recentFlows
            .groupBy { "${it.edgeFrom}->${it.edgeTo}" }
            .values
            .map { grouped ->
                val head = grouped.first()
                TopEdgeMetric(
                    edgeFrom = head.edgeFrom,
                    edgeTo = head.edgeTo,
                    count = grouped.size,
                    errorCount = grouped.count { it.status.equals("ERROR", ignoreCase = true) }
                )
            }
            .sortedWith(compareByDescending<TopEdgeMetric> { it.count }.thenBy { it.edgeFrom }.thenBy { it.edgeTo })
            .take(6)
        val heap = memoryMxBean.heapMemoryUsage
        val heapMax = heap.max.takeIf { it > 0 } ?: heap.committed
        val sys = systemMetrics()
        return ObservabilityMetricsSnapshot(
            windowMs = windowMs,
            runtimeNodeCount = nodes.size,
            externalNodeCount = nodes.count { it.runtimeMode.equals("EXTERNAL_JVM", ignoreCase = true) },
            totalInflight = totalInflight,
            totalQueueDepth = totalQueueDepth,
            droppedLogCount = droppedLogCount,
            kernel = KernelProcessMetrics(
                processCpuLoad = sys.processCpuLoad,
                systemLoadAverage = sys.systemLoadAverage,
                availableProcessors = sys.availableProcessors,
                heapUsedBytes = heap.used,
                heapMaxBytes = heapMax,
                heapUsedPercent = if (heapMax > 0) (heap.used.toDouble() / heapMax.toDouble()) * 100.0 else 0.0,
                threadCount = threadMxBean.threadCount
            ),
            latency = LatencyMetrics(
                avgMs = if (completedDurations.isEmpty()) 0.0 else completedDurations.average(),
                p95Ms = percentile(completedDurations, 0.95),
                p99Ms = percentile(completedDurations, 0.99),
                errorRate = if (recentTraces.isEmpty()) 0.0 else (errorCount.toDouble() / recentTraces.size.toDouble()) * 100.0,
                completedSpanCount = completedDurations.size
            ),
            traffic = TrafficMetrics(
                recentFlowCount = recentFlows.size,
                recentTraceCount = recentTraces.size,
                topEdges = topEdges
            ),
            nodes = nodeSummarySnapshot(windowMs)
        )
    }

    override fun nodeSummarySnapshot(windowMs: Long): List<NodeSummary> {
        val nodes = jvmSnapshot()
        val windowStart = System.currentTimeMillis() - windowMs
        val recentTraces = traceSnapshot(limit = config.traceBufferSize, sinceEpochMs = windowStart)
        val recentFlows = synchronized(flowBuffer) { flowBuffer.filter { it.startEpochMs >= windowStart } }
        return nodes.map { node ->
            val traceCount = recentTraces.count { it.service == node.id || it.edgeFrom == node.id || it.edgeTo == node.id }
            val flowCount = recentFlows.count { it.edgeFrom == node.id || it.edgeTo == node.id }
            val errorCount = recentTraces.count {
                it.status.equals("ERROR", ignoreCase = true) &&
                    (it.service == node.id || it.edgeFrom == node.id || it.edgeTo == node.id)
            }
            NodeSummary(
                node = node,
                recentFlowCount = flowCount,
                recentTraceCount = traceCount,
                errorCount = errorCount
            )
        }.sortedBy { it.node.label }
    }

    override fun logSnapshot(
        limit: Int,
        query: String?,
        level: String?,
        source: String?,
        sinceEpochMs: Long?
    ): LogSnapshotPage {
        val queryLower = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val sourceLower = source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val levelUpper = level?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val items = recentStructuredLogs().filter { record ->
            val matchesLevel = levelUpper == null || record.level.uppercase() == levelUpper
            val matchesSource = sourceLower == null || record.source.lowercase().contains(sourceLower)
            val matchesSince = sinceEpochMs == null || record.timestamp >= sinceEpochMs
            val haystack = buildString {
                append(record.source)
                append('\n')
                append(record.message)
                append('\n')
                append(record.throwable.orEmpty())
            }.lowercase()
            val matchesQuery = queryLower == null || haystack.contains(queryLower)
            matchesLevel && matchesSource && matchesSince && matchesQuery
        }
        return LogSnapshotPage(
            items = items.takeLast(limit),
            total = items.size,
            limit = limit
        )
    }

    override fun jvmSnapshot(): List<JvmNode> {
        val currentPid = ProcessHandle.current().pid()
        val nodes = mutableListOf(
            JvmNode(
                id = "kernel",
                kind = "kernel",
                label = "Kernel JVM",
                pid = currentPid,
                runtimeMode = "IN_PROCESS",
                healthState = "HEALTHY",
                lifecycleState = "RUNNING"
            )
        )
        val snapshots = pluginSnapshotProvider?.invoke().orEmpty()
        for (snapshot in snapshots) {
            nodes += JvmNode(
                id = snapshot.pluginId,
                kind = "plugin",
                label = snapshot.displayName,
                pluginId = snapshot.pluginId,
                pid = snapshot.processId,
                runtimeMode = snapshot.runtimeMode.name,
                healthState = snapshot.healthState.name,
                lifecycleState = snapshot.lifecycleState.name,
                inflightInvocations = snapshot.diagnostics.inflightInvocations,
                eventQueueDepth = snapshot.diagnostics.eventQueueDepth,
                droppedLogCount = snapshot.diagnostics.droppedLogCount
            )
        }
        return nodes
    }

    fun panelInfo(): List<PanelInfo> = panels.values.sortedBy { it.id }

    override fun emitCustomEvent(type: String, payload: JsonElement) {
        val event = CustomEvent(type = type, payload = payload)
        emitEvent("custom_event", json.encodeToString(CustomEvent.serializer(), event))
    }

    override fun tagCurrentSpan(key: String, value: String) {
        ObservabilityTracing.tagCurrentSpan(key, value)
    }

    override fun registerPanel(id: String, title: String, dataEndpoint: String) {
        panels[id] = PanelInfo(id = id, title = title, dataEndpoint = dataEndpoint)
        emitEvent(
            "panel_update",
            json.encodeToString(PanelInfo.serializer(), panels[id]!!)
        )
    }

    override fun panels(): List<PanelInfo> = panelInfo()

    private fun emitSnapshots() {
        val nodes = jvmSnapshot()
        for (node in nodes) {
            emitEvent("jvm_status", json.encodeToString(JvmNode.serializer(), node))
            if (node.kind == "plugin") {
                emitEvent("plugin_status", json.encodeToString(JvmNode.serializer(), node))
            }
        }
    }

    private fun emitEvent(type: String, dataJson: String) {
        eventFlow.tryEmit(ObservabilityStreamEvent(type = type, dataJson = dataJson))
    }

    private fun systemMetrics(): SystemMetrics {
        val bean = processMxBean
        return SystemMetrics(
            processCpuLoad = bean.readDoubleMetric("getProcessCpuLoad")?.takeIf { it >= 0.0 }?.times(100.0),
            systemLoadAverage = bean.readDoubleMetric("getSystemLoadAverage")?.takeIf { it >= 0.0 },
            availableProcessors = bean.readIntMetric("getAvailableProcessors")
                ?: Runtime.getRuntime().availableProcessors()
        )
    }

    private fun Any.readDoubleMetric(methodName: String): Double? {
        val value = runCatching {
            javaClass.getMethod(methodName).invoke(this)
        }.getOrNull()
        return when (value) {
            is Double -> value
            is Number -> value.toDouble()
            else -> null
        }
    }

    private fun Any.readIntMetric(methodName: String): Int? {
        val value = runCatching {
            javaClass.getMethod(methodName).invoke(this)
        }.getOrNull()
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> null
        }
    }

    private fun percentile(sortedValues: List<Long>, fraction: Double): Long {
        if (sortedValues.isEmpty()) return 0
        val index = ceil(sortedValues.lastIndex * fraction).toInt().coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    private fun enrichLog(entry: LogEntry): StructuredLogRecord {
        val pluginId = inferPluginId(entry.source, entry.message)
        val traceId = extractToken(entry.message, "trace")
        val spanId = extractToken(entry.message, "span")
        return StructuredLogRecord(
            timestamp = entry.timestamp,
            level = entry.level,
            source = entry.source,
            message = entry.message,
            throwable = entry.throwable,
            traceId = traceId,
            spanId = spanId,
            pluginId = pluginId,
            attributes = buildMap {
                pluginId?.let { put("pluginId", it) }
                traceId?.let { put("traceId", it) }
                spanId?.let { put("spanId", it) }
            }
        )
    }

    private fun inferPluginId(source: String, message: String): String? {
        val sourceLower = source.lowercase()
        val messageLower = message.lowercase()
        return pluginSnapshotProvider?.invoke()
            ?.firstNotNullOfOrNull { snapshot ->
                val pluginId = snapshot.pluginId.lowercase()
                if (sourceLower.contains(pluginId) || messageLower.contains(pluginId)) snapshot.pluginId else null
            }
    }

    private fun extractToken(text: String, label: String): String? {
        val pattern = Regex("""$label(?:Id)?[=: ]+([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
        return pattern.find(text)?.groupValues?.getOrNull(1)
    }

    private fun recentStructuredLogs(): List<StructuredLogRecord> {
        val merged = linkedMapOf<String, StructuredLogRecord>()

        KeelLoggerService.getInstance()
            .getRecentLogs(limit = 1000)
            .map(::enrichLog)
            .forEach { record ->
                merged[record.snapshotKey()] = record
            }

        synchronized(logBuffer) {
            logBuffer.forEach { record ->
                merged[record.snapshotKey()] = record
            }
        }

        return merged.values.sortedBy { it.timestamp }
    }

    private fun StructuredLogRecord.snapshotKey(): String {
        return listOf(timestamp, level, source, message, throwable.orEmpty()).joinToString("|")
    }

    private fun StructuredLogRecord.toLogEvent(): LogEvent = LogEvent(
        timestamp = timestamp,
        level = level,
        source = source,
        message = message,
        throwable = throwable,
        traceId = traceId,
        spanId = spanId,
        pluginId = pluginId,
        attributes = attributes
    )
}
