package com.keel.kernel.observability

import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogEntry
import com.keel.kernel.plugin.PluginNodeAssetMetadata
import com.keel.kernel.plugin.PluginRuntimeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

class ObservabilityHub(
    private val config: ObservabilityConfig = ObservabilityConfig.fromSystem()
) : KeelObservability {
    private val json = Json { encodeDefaults = true }
    private val panels = ConcurrentHashMap<String, PanelInfo>()
    private val buffers = ObservabilityBuffers(
        traceCapacity = config.traceBufferSize,
        flowCapacity = config.flowBufferSize
    )
    private val publisher = ObservabilityEventPublisher()
    private val processMxBean = ManagementFactory.getOperatingSystemMXBean()
    private val memoryMxBean = ManagementFactory.getMemoryMXBean()
    private val threadMxBean = ManagementFactory.getThreadMXBean()
    private val runtimeMxBean = ManagementFactory.getRuntimeMXBean()

    private var pollJob: Job? = null
    private var logJob: Job? = null
    private var pluginSnapshotProvider: (() -> List<PluginRuntimeSnapshot>)? = null

    override fun events(): SharedFlow<ObservabilityStreamEvent> = publisher.events()

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
                buffers.recordLog(record)
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
        buffers.recordSpan(event)
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
        buffers.recordFlow(event)
        emitEvent("flow_event", json.encodeToString(FlowEvent.serializer(), event))
    }

    override fun traceSnapshot(limit: Int, sinceEpochMs: Long?): List<TraceSpanEvent> {
        val items = buffers.traceSnapshot()
        val filtered = sinceEpochMs?.let { epoch ->
            items.filter { it.startEpochMs >= epoch }
        } ?: items
        return filtered.takeLast(limit)
    }

    override fun traceSummarySnapshot(
        windowKey: String,
        query: String?,
        status: String?,
        service: String?
    ): TraceSummarySnapshot {
        val queryResult = traceQueryResult(
            windowKey = windowKey,
            query = query,
            status = status,
            service = service,
            limit = config.traceBufferSize,
            selectedTraceId = null,
            selectedSpanId = null
        )
        return TraceSummarySnapshot(summary = toTraceSummary(queryResult.filteredGroups))
    }

    override fun traceListSnapshot(
        windowKey: String,
        query: String?,
        status: String?,
        service: String?,
        limit: Int,
        selectedTraceId: String?
    ): TraceListSnapshot {
        val queryResult = traceQueryResult(
            windowKey = windowKey,
            query = query,
            status = status,
            service = service,
            limit = limit,
            selectedTraceId = selectedTraceId,
            selectedSpanId = null
        )
        return TraceListSnapshot(
            filters = queryResult.filters,
            traces = queryResult.visibleGroups.map(::toTraceListItem),
            selectedTraceId = queryResult.selectedGroup?.traceId
        )
    }

    override fun traceTimelineSnapshot(
        windowKey: String,
        query: String?,
        status: String?,
        service: String?,
        limit: Int,
        selectedTraceId: String?
    ): TraceTimelineSnapshot {
        val queryResult = traceQueryResult(
            windowKey = windowKey,
            query = query,
            status = status,
            service = service,
            limit = limit,
            selectedTraceId = selectedTraceId,
            selectedSpanId = null
        )
        return TraceTimelineSnapshot(
            selectedTraceId = queryResult.selectedGroup?.traceId,
            timeline = queryResult.selectedGroup?.let { buildTraceTimeline(it, queryResult.now) }
        )
    }

    override fun traceSpanDetailSnapshot(
        windowKey: String,
        query: String?,
        status: String?,
        service: String?,
        limit: Int,
        selectedTraceId: String?,
        selectedSpanId: String?
    ): TraceSpanDetailSnapshot {
        val queryResult = traceQueryResult(
            windowKey = windowKey,
            query = query,
            status = status,
            service = service,
            limit = limit,
            selectedTraceId = selectedTraceId,
            selectedSpanId = selectedSpanId
        )
        return TraceSpanDetailSnapshot(
            selectedTraceId = queryResult.selectedGroup?.traceId,
            selectedSpanId = queryResult.selectedSpan?.spanId,
            spanDetail = queryResult.selectedSpan?.let { buildTraceSpanDetail(it) }
        )
    }

    override fun traceDashboardSnapshot(
        windowKey: String,
        query: String?,
        status: String?,
        service: String?,
        limit: Int,
        selectedTraceId: String?,
        selectedSpanId: String?
    ): TraceDashboardSnapshot {
        val queryResult = traceQueryResult(
            windowKey = windowKey,
            query = query,
            status = status,
            service = service,
            limit = limit,
            selectedTraceId = selectedTraceId,
            selectedSpanId = selectedSpanId
        )

        return TraceDashboardSnapshot(
            summary = toTraceSummary(queryResult.filteredGroups),
            filters = queryResult.filters,
            traces = queryResult.visibleGroups.map(::toTraceListItem),
            selectedTraceId = queryResult.selectedGroup?.traceId,
            selectedSpanId = queryResult.selectedSpan?.spanId,
            timeline = queryResult.selectedGroup?.let { buildTraceTimeline(it, queryResult.now) },
            spanDetail = queryResult.selectedSpan?.let { buildTraceSpanDetail(it) }
        )
    }

    override fun flowSnapshot(limit: Int): List<FlowEvent> {
        return buffers.flowSnapshot().takeLast(limit)
    }

    override fun metricsSnapshot(windowMs: Long): ObservabilityMetricsSnapshot {
        val nodes = jvmSnapshot()
        val windowStart = System.currentTimeMillis() - windowMs
        val recentTraces = traceSnapshot(limit = config.traceBufferSize, sinceEpochMs = windowStart)
        val recentFlows = buffers.flowSnapshot().filter { it.startEpochMs >= windowStart }
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
        val throughputPerSecond = if (windowMs <= 0) 0.0 else (recentFlows.size.toDouble() / windowMs.toDouble()) * 1000.0
        val healthyNodes = nodes.count { it.healthState.equals("HEALTHY", ignoreCase = true) }
        val uptimeScore = if (nodes.isEmpty()) 0.0 else (healthyNodes.toDouble() / nodes.size.toDouble()) * 100.0
        return ObservabilityMetricsSnapshot(
            windowMs = windowMs,
            runtimeNodeCount = nodes.size,
            externalNodeCount = nodes.count { it.runtimeMode.equals("EXTERNAL_JVM", ignoreCase = true) },
            totalInflight = totalInflight,
            totalQueueDepth = totalQueueDepth,
            droppedLogCount = droppedLogCount,
            kernel = KernelProcessMetrics(
                processCpuLoad = sys.processCpuLoad?.coerceIn(0.0, 100.0),
                systemLoadAverage = sys.systemLoadAverage,
                availableProcessors = sys.availableProcessors,
                heapUsedBytes = heap.used,
                heapMaxBytes = heapMax,
                heapUsedPercent = if (heapMax > 0) ((heap.used.toDouble() / heapMax.toDouble()) * 100.0).coerceIn(0.0, 100.0) else 0.0,
                threadCount = threadMxBean.threadCount
            ),
            latency = LatencyMetrics(
                avgMs = if (completedDurations.isEmpty()) 0.0 else completedDurations.average(),
                p95Ms = percentile(completedDurations, 0.95),
                p99Ms = percentile(completedDurations, 0.99),
                errorRate = if (recentTraces.isEmpty()) 0.0 else (errorCount.toDouble() / recentTraces.size.toDouble()) * 100.0,
                completedSpanCount = completedDurations.size,
                histogram = if (completedDurations.isEmpty()) null else buildLatencyHistogram(completedDurations)
            ),
            traffic = TrafficMetrics(
                recentFlowCount = recentFlows.size,
                recentTraceCount = recentTraces.size,
                topEdges = topEdges
            ),
            cluster = ClusterInsightMetrics(
                overallUtilizationPercent = utilizationPercent(totalInflight, totalQueueDepth, droppedLogCount),
                throughputPerSecond = throughputPerSecond,
                networkErrorRatePercent = if (recentFlows.isEmpty()) 0.0 else (topEdges.sumOf { it.errorCount }.toDouble() / recentFlows.size.toDouble()) * 100.0,
                uptimeScore = uptimeScore
            ),
            nodes = nodeSummarySnapshot(windowMs)
        )
    }

    override fun nodeSummarySnapshot(windowMs: Long): List<NodeSummary> {
        val nodes = jvmSnapshot()
        val runtimeSnapshots = runtimeSnapshots()
        val windowStart = System.currentTimeMillis() - windowMs
        val recentTraces = traceSnapshot(limit = config.traceBufferSize, sinceEpochMs = windowStart)
        val recentFlows = buffers.flowSnapshot().filter { it.startEpochMs >= windowStart }
        val heap = memoryMxBean.heapMemoryUsage
        val heapMax = heap.max.takeIf { it > 0 } ?: heap.committed
        return nodes.map { node ->
            val traceCount = recentTraces.count { it.service == node.id || it.edgeFrom == node.id || it.edgeTo == node.id }
            val flowCount = recentFlows.count { it.edgeFrom == node.id || it.edgeTo == node.id }
            val errorCount = recentTraces.count {
                it.status.equals("ERROR", ignoreCase = true) &&
                    (it.service == node.id || it.edgeFrom == node.id || it.edgeTo == node.id)
            }
            val runtimeSnapshot = runtimeSnapshots.firstOrNull { it.pluginId == node.id }
            val diagnostics = runtimeSnapshot?.diagnostics
            val loadPercent = loadPercent(
                inflight = node.inflightInvocations,
                queueDepth = node.eventQueueDepth,
                droppedLogs = node.droppedLogCount,
                errorCount = errorCount,
                healthState = node.healthState
            )
            val memoryPressurePercent = if (node.id == "kernel") {
                if (heapMax > 0) ((heap.used.toDouble() / heapMax.toDouble()) * 100.0).roundToInt().coerceIn(0, 100) else 0
            } else {
                memoryPressurePercent(node.eventQueueDepth, node.droppedLogCount, errorCount)
            }
            NodeSummary(
                node = node,
                recentFlowCount = flowCount,
                recentTraceCount = traceCount,
                errorCount = errorCount,
                loadPercent = loadPercent,
                memoryPressurePercent = memoryPressurePercent,
                uptimeMs = if (node.id == "kernel") runtimeMxBean.uptime else null,
                processAlive = runtimeSnapshot?.processHandleAlive ?: diagnostics?.processAlive ?: (node.id == "kernel"),
                lastHealthLatencyMs = diagnostics?.lastHealthLatencyMs,
                lastAdminLatencyMs = diagnostics?.lastAdminLatencyMs,
                lastEventAtEpochMs = diagnostics?.lastEventAtEpochMs
            )
        }.sortedBy { it.node.label }
    }

    override fun nodeDashboardSnapshot(windowMs: Long): NodeDashboardSnapshot {
        val metrics = metricsSnapshot(windowMs)
        val items = metrics.nodes.map { summary ->
            val runtimeSnapshot = runtimeSnapshots().firstOrNull { it.pluginId == summary.node.id }
            val asset = when (summary.node.id) {
                "kernel" -> kernelAssetMetadata()
                else -> toNodeAssetMetadata(runtimeSnapshot?.diagnostics?.assetMetadata)
                    ?: fallbackAssetMetadata(summary.node)
            }
            val resource = resourceMetricsFor(summary, runtimeSnapshot, metrics.kernel)
            val degradationReason = degradationReason(summary, runtimeSnapshot)
            NodeDashboardItem(
                node = summary.node,
                summary = summary,
                asset = asset,
                resource = resource,
                degradationReason = degradationReason,
                primaryAction = primaryActionFor(summary, runtimeSnapshot),
                secondaryAction = secondaryActionFor(summary, runtimeSnapshot)
            )
        }.sortedWith(
            compareBy<NodeDashboardItem>(
                { if (it.node.id == "kernel") 0 else 1 },
                { it.node.label.lowercase() },
                { it.node.id }
            )
        )

        val featured = items.firstOrNull { it.asset.featured }
            ?: items.firstOrNull { it.node.id == "kernel" }
            ?: items.firstOrNull()

        return NodeDashboardSnapshot(
            windowMs = windowMs,
            activeCount = items.count { !it.node.lifecycleState.equals("STOPPED", ignoreCase = true) },
            degradedCount = items.count { it.degradationReason != null },
            pageTotal = items.size,
            featuredNodeId = featured?.node?.id,
            throughputPerSecond = metrics.cluster.throughputPerSecond,
            networkThroughputBytesPerSecond = null,
            networkErrorRatePercent = metrics.cluster.networkErrorRatePercent,
            uptimeScore = metrics.cluster.uptimeScore,
            items = items
        )
    }

    override fun logSnapshot(
        limit: Int,
        query: String?,
        level: String?,
        source: String?,
        sinceEpochMs: Long?,
        page: Int,
        pageSize: Int,
        windowKey: String
    ): LogSnapshotPage {
        return logExplorerSnapshot(
            query = query,
            level = level,
            source = source,
            sinceEpochMs = sinceEpochMs,
            page = page,
            pageSize = pageSize,
            windowKey = windowKey
        ).page
    }

    override fun logExplorerSnapshot(
        query: String?,
        level: String?,
        source: String?,
        sinceEpochMs: Long?,
        page: Int,
        pageSize: Int,
        windowKey: String
    ): LogExplorerSnapshot {
        val filtered = filterStructuredLogs(query, level, source, sinceEpochMs)
        val normalizedPage = max(page, 1)
        val normalizedPageSize = max(pageSize, 1)
        val total = filtered.size
        val totalPages = if (total == 0) 0 else ceil(total.toDouble() / normalizedPageSize.toDouble()).toInt()
        val safePage = if (totalPages == 0) 1 else normalizedPage.coerceAtMost(totalPages)
        val fromIndex = if (total == 0) 0 else ((safePage - 1) * normalizedPageSize).coerceAtMost(total)
        val toIndex = (fromIndex + normalizedPageSize).coerceAtMost(total)
        val pageItems = if (total == 0) emptyList() else filtered.subList(fromIndex, toIndex)
        val availableLevels = filtered.map { it.level.uppercase() }.distinct().sorted()

        return LogExplorerSnapshot(
            page = LogSnapshotPage(
                items = pageItems,
                total = total,
                limit = normalizedPageSize,
                page = safePage,
                pageSize = normalizedPageSize,
                totalPages = totalPages,
                hasPrev = totalPages > 0 && safePage > 1,
                hasNext = totalPages > 0 && safePage < totalPages
            ),
            histogram = buildLogHistogram(filtered, sinceEpochMs),
            summary = LogExplorerSummary(
                totalMatched = total,
                showingCount = pageItems.size,
                availableLevels = availableLevels,
                activeWindow = windowKey
            ),
            availableLevels = availableLevels
        )
    }

    override fun jvmSnapshot(): List<JvmNode> {
        val currentPid = ProcessHandle.current().pid()
        val kernelAsset = kernelAssetMetadata()
        val nodes = mutableListOf(
            JvmNode(
                id = "kernel",
                kind = "kernel",
                label = "Kernel JVM",
                pid = currentPid,
                runtimeMode = "IN_PROCESS",
                healthState = "HEALTHY",
                lifecycleState = "RUNNING",
                startedAtEpochMs = runtimeMxBean.startTime,
                labels = assetLabels(kernelAsset)
            )
        )
        val snapshots = runtimeSnapshots()
        for (snapshot in snapshots) {
            val asset = toNodeAssetMetadata(snapshot.diagnostics.assetMetadata)
            nodes += JvmNode(
                id = snapshot.pluginId,
                kind = "plugin",
                label = snapshot.displayName,
                pluginId = snapshot.pluginId,
                pid = snapshot.processId,
                runtimeMode = snapshot.runtimeMode.name,
                healthState = snapshot.healthState.name,
                lifecycleState = snapshot.lifecycleState.name,
                startedAtEpochMs = null,
                inflightInvocations = snapshot.diagnostics.inflightInvocations,
                eventQueueDepth = snapshot.diagnostics.eventQueueDepth,
                droppedLogCount = snapshot.diagnostics.droppedLogCount,
                labels = assetLabels(asset)
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
        publisher.emit(type, dataJson)
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
        val method = findPublicMethod(this.javaClass, methodName) ?: return null
        return runCatching {
            val value = method.invoke(this)
            when (value) {
                is Double -> value
                is Number -> value.toDouble()
                else -> null
            }
        }.getOrNull()
    }

    private fun Any.readIntMetric(methodName: String): Int? {
        val method = findPublicMethod(this.javaClass, methodName) ?: return null
        return runCatching {
            val value = method.invoke(this)
            when (value) {
                is Int -> value
                is Number -> value.toInt()
                else -> null
            }
        }.getOrNull()
    }

    private fun findPublicMethod(clazz: Class<*>, methodName: String): java.lang.reflect.Method? {
        // Try interfaces first (e.g., com.sun.management.OperatingSystemMXBean is where sun methods are public)
        for (iface in clazz.interfaces) {
            if (java.lang.reflect.Modifier.isPublic(iface.modifiers)) {
                try {
                    return iface.getMethod(methodName)
                } catch (e: Exception) {}
            }
            findPublicMethod(iface, methodName)?.let { return it }
        }
        // Try the class itself if it is public
        if (java.lang.reflect.Modifier.isPublic(clazz.modifiers)) {
            try {
                return clazz.getMethod(methodName)
            } catch (e: Exception) {}
        }
        // Try superclass
        clazz.superclass?.let { findPublicMethod(it, methodName) }?.let { return it }
        return null
    }

    private fun percentile(sortedValues: List<Long>, fraction: Double): Long {
        if (sortedValues.isEmpty()) return 0
        val index = ceil(sortedValues.lastIndex * fraction).toInt().coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    private fun toTraceSummary(groups: List<TraceGroup>): TraceDashboardSummary {
        return TraceDashboardSummary(
            totalTraces = groups.size,
            p99LatencyMs = percentile(groups.map { it.durationMs }.sorted(), 0.99),
            errorRatePercent = if (groups.isEmpty()) 0.0 else {
                (groups.count { it.errorCount > 0 }.toDouble() / groups.size.toDouble()) * 100.0
            },
            activeSpanCount = groups.sumOf { it.activeSpanCount }
        )
    }

    private fun traceQueryResult(
        windowKey: String,
        query: String?,
        status: String?,
        service: String?,
        limit: Int,
        selectedTraceId: String?,
        selectedSpanId: String?
    ): TraceQueryResult {
        val normalizedWindow = windowKey.takeIf { !it.isNullOrBlank() } ?: "1h"
        val windowMs = windowDurationMillis(normalizedWindow)
        val now = System.currentTimeMillis()
        val recentSpans = traceSnapshot(limit = config.traceBufferSize, sinceEpochMs = now - windowMs)
        val allGroups = buildTraceGroups(recentSpans, now)
        val availableServices = allGroups.flatMap { group -> group.spans.map { it.service } }.distinct().sorted()
        val normalizedStatus = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "all"
        val normalizedService = service?.trim().orEmpty()
        val normalizedQuery = query?.trim().orEmpty()
        val normalizedLimit = limit.coerceIn(1, config.traceBufferSize)
        val filteredGroups = allGroups.filter { group ->
            matchesStatus(group, normalizedStatus) &&
                (normalizedService.isBlank() || group.service.equals(normalizedService, ignoreCase = true)) &&
                matchesTraceQuery(group, normalizedQuery)
        }
        val visibleGroups = filteredGroups.take(normalizedLimit)
        val selectedGroup = visibleGroups.firstOrNull { it.traceId == selectedTraceId } ?: visibleGroups.firstOrNull()
        val selectedSpan = selectedGroup?.spans?.firstOrNull { it.spanId == selectedSpanId } ?: selectedGroup?.spans?.firstOrNull()
        return TraceQueryResult(
            now = now,
            filters = TraceFilterOptions(
                window = normalizedWindow,
                query = normalizedQuery,
                status = normalizedStatus,
                service = normalizedService,
                limit = normalizedLimit,
                availableServices = availableServices
            ),
            filteredGroups = filteredGroups,
            visibleGroups = visibleGroups,
            selectedGroup = selectedGroup,
            selectedSpan = selectedSpan
        )
    }

    private fun windowDurationMillis(windowKey: String): Long = when (windowKey) {
        "15m" -> 15 * 60 * 1000L
        "6h" -> 6 * 60 * 60 * 1000L
        "24h" -> 24 * 60 * 60 * 1000L
        else -> 60 * 60 * 1000L
    }

    private fun buildTraceGroups(spans: List<TraceSpanEvent>, now: Long): List<TraceGroup> {
        if (spans.isEmpty()) return emptyList()
        return spans.groupBy { it.traceId }
            .map { (traceId, grouped) ->
                val ordered = grouped.sortedWith(compareBy<TraceSpanEvent>({ it.startEpochMs }, { it.spanId }))
                val root = ordered.firstOrNull { it.parentSpanId.isNullOrBlank() } ?: ordered.first()
                val start = ordered.first().startEpochMs
                val end = ordered.maxOf { it.endEpochMs ?: now }
                val durationMs = max(end - start, 0)
                val errorCount = ordered.count { it.status.equals("ERROR", ignoreCase = true) }
                val activeCount = ordered.count { it.endEpochMs == null }
                val httpStatusCode = ordered.firstNotNullOfOrNull(::httpStatusCode)
                TraceGroup(
                    traceId = traceId,
                    startEpochMs = start,
                    durationMs = durationMs,
                    root = root,
                    spans = ordered,
                    operation = spanDisplayOperation(root),
                    service = root.service,
                    status = when {
                        errorCount > 0 -> "ERROR"
                        activeCount > 0 -> "ACTIVE"
                        else -> "OK"
                    },
                    errorCount = errorCount,
                    activeSpanCount = activeCount,
                    httpStatusCode = httpStatusCode
                )
            }
            .sortedByDescending { it.startEpochMs }
    }

    private fun matchesStatus(group: TraceGroup, status: String): Boolean = when (status) {
        "ok" -> group.errorCount == 0 && group.activeSpanCount == 0
        "error" -> group.errorCount > 0
        "slow" -> group.durationMs >= 250L
        "active" -> group.activeSpanCount > 0
        else -> true
    }

    private fun matchesTraceQuery(group: TraceGroup, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.lowercase()
        val haystack = buildString {
            append(group.traceId)
            append('\n')
            append(group.operation)
            append('\n')
            append(group.service)
            append('\n')
            group.spans.forEach { span ->
                append(span.operation)
                append('\n')
                append(span.service)
                append('\n')
            }
        }.lowercase()
        return haystack.contains(needle)
    }

    private fun toTraceListItem(group: TraceGroup): TraceListItem = TraceListItem(
        traceId = group.traceId,
        operation = group.operation,
        service = group.service,
        startEpochMs = group.startEpochMs,
        durationMs = group.durationMs,
        spanCount = group.spans.size,
        status = group.status,
        badgeLabel = traceBadgeLabel(group),
        httpStatusCode = group.httpStatusCode,
        errorCount = group.errorCount,
        activeSpanCount = group.activeSpanCount,
        slow = group.durationMs >= 250L
    )

    private fun traceBadgeLabel(group: TraceGroup): String = when {
        group.errorCount > 0 -> "${group.httpStatusCode ?: 500} ERR"
        group.activeSpanCount > 0 -> "LIVE"
        group.httpStatusCode != null -> "${group.httpStatusCode} OK"
        group.durationMs >= 250L -> "SLOW"
        else -> "OK"
    }

    private fun buildTraceTimeline(group: TraceGroup, now: Long): TraceTimeline {
        val total = max(group.durationMs, 1L)
        val marks = listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { ratio ->
            TraceTimelineMark(
                label = "${(total * ratio).toLong()}ms",
                offsetPercent = ratio * 100.0
            )
        }
        val children = group.spans.groupBy { it.parentSpanId }
        val ordered = mutableListOf<Pair<TraceSpanEvent, Int>>()

        fun visit(parentSpanId: String?, depth: Int) {
            children[parentSpanId]
                ?.sortedWith(compareBy<TraceSpanEvent>({ it.startEpochMs }, { it.spanId }))
                ?.forEach { span ->
                    ordered += span to depth
                    visit(span.spanId, depth + 1)
                }
        }

        val rootKeys = group.spans.filter { it.parentSpanId.isNullOrBlank() }.map { it.spanId }.toSet()
        visit(null, 0)
        group.spans
            .filter { it.spanId !in ordered.map { pair -> pair.first.spanId }.toSet() }
            .sortedWith(compareBy<TraceSpanEvent>({ if (it.parentSpanId in rootKeys) 1 else 2 }, { it.startEpochMs }, { it.spanId }))
            .forEach { span ->
                ordered += span to 0
            }

        return TraceTimeline(
            traceId = group.traceId,
            durationMs = group.durationMs,
            spanCount = group.spans.size,
            startedAtEpochMs = group.startEpochMs,
            marks = marks,
            spans = ordered.map { (span, depth) ->
                val effectiveEnd = span.endEpochMs ?: now
                val leftPercent = ((span.startEpochMs - group.startEpochMs).toDouble() / total.toDouble()) * 100.0
                val widthPercent = (max(effectiveEnd - span.startEpochMs, 1L).toDouble() / total.toDouble()) * 100.0
                TraceTimelineSpan(
                    traceId = span.traceId,
                    spanId = span.spanId,
                    parentSpanId = span.parentSpanId,
                    service = span.service,
                    operation = spanDisplayOperation(span),
                    startEpochMs = span.startEpochMs,
                    endEpochMs = span.endEpochMs,
                    durationMs = span.durationMs ?: max(effectiveEnd - span.startEpochMs, 0L),
                    status = span.status,
                    depth = depth,
                    leftPercent = leftPercent.coerceIn(0.0, 100.0),
                    widthPercent = widthPercent.coerceIn(2.0, 100.0),
                    host = spanHost(span),
                    protocol = spanProtocol(span),
                    component = spanComponent(span),
                    attributes = span.attributes
                )
            }
        )
    }

    private fun buildTraceSpanDetail(span: TraceSpanEvent): TraceSpanDetail = TraceSpanDetail(
        traceId = span.traceId,
        spanId = span.spanId,
        parentSpanId = span.parentSpanId,
        service = span.service,
        operation = spanDisplayOperation(span),
        status = span.status,
        startEpochMs = span.startEpochMs,
        endEpochMs = span.endEpochMs,
        durationMs = span.durationMs,
        host = spanHost(span),
        protocol = spanProtocol(span),
        component = spanComponent(span),
        edgeFrom = span.edgeFrom,
        edgeTo = span.edgeTo,
        attributes = span.attributes
    )

    private fun httpStatusCode(span: TraceSpanEvent): Int? {
        return span.attributes["http.status_code"]?.toIntOrNull()
            ?: span.attributes["http.response.status_code"]?.toIntOrNull()
    }

    private fun spanDisplayOperation(span: TraceSpanEvent): String {
        return span.attributes["keel.display.operation"]
            ?: listOfNotNull(span.attributes["http.method"], span.attributes["http.route"])
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
            ?: span.operation
    }

    private fun spanHost(span: TraceSpanEvent): String? {
        val attrHost = span.attributes["keel.host"]
            ?: span.attributes["server.address"]
            ?: span.attributes["net.host.name"]
            ?: span.attributes["host.name"]
        if (!attrHost.isNullOrBlank()) return attrHost
        if (span.service == "kernel") return kernelAssetMetadata().address
        val runtimeAsset = runtimeSnapshots()
            .firstOrNull { it.pluginId == span.service }
            ?.diagnostics?.assetMetadata
            ?.let(::toNodeAssetMetadata)
        return runtimeAsset?.address
            ?: jvmSnapshot().firstOrNull { it.id == span.service }?.labels?.get("address")
    }

    private fun spanProtocol(span: TraceSpanEvent): String? {
        return span.attributes["keel.protocol"]
            ?: span.attributes["network.protocol.name"]
            ?: span.attributes["http.flavor"]?.let { "HTTP / $it" }
            ?: span.attributes["db.system"]?.uppercase()
            ?: if (span.attributes["http.method"] != null) "HTTP" else null
    }

    private fun spanComponent(span: TraceSpanEvent): String? {
        return span.attributes["keel.component"]
            ?: span.attributes["component"]
            ?: span.attributes["otel.library.name"]
            ?: span.attributes["keel.pluginId"]
            ?: span.attributes["keel.jvm"]
    }

    private fun buildLatencyHistogram(durations: List<Long>): LatencyHistogram {
        val totalCount = durations.size
        val avgMs = durations.average()
        val p50Ms = percentile(durations, 0.50)
        val p95Ms = percentile(durations, 0.95)
        val p99Ms = percentile(durations, 0.99)

        // Define 8 fixed buckets: upper bound (inclusive) in ms
        data class BucketDef(val label: String, val maxMs: Long)
        val bucketDefs = listOf(
            BucketDef("<10ms",    10L),
            BucketDef("10-50ms",  50L),
            BucketDef("50-100ms", 100L),
            BucketDef("100-250ms", 250L),
            BucketDef("250-500ms", 500L),
            BucketDef("500ms-1s", 1000L),
            BucketDef("1-2s",     2000L),
            BucketDef("2s+",      Long.MAX_VALUE)
        )

        val counts = IntArray(bucketDefs.size)
        for (d in durations) {
            val idx = bucketDefs.indexOfFirst { d <= it.maxMs }.takeIf { it >= 0 } ?: (bucketDefs.size - 1)
            counts[idx]++
        }

        val buckets = bucketDefs.mapIndexed { idx, def ->
            LatencyHistogramBucket(
                label = def.label,
                maxValueMs = def.maxMs,
                count = counts[idx],
                percentOfTotal = if (totalCount > 0) (counts[idx].toDouble() / totalCount * 100.0) else 0.0
            )
        }

        return LatencyHistogram(
            buckets = buckets,
            totalCount = totalCount,
            avgMs = avgMs,
            p50Ms = p50Ms,
            p95Ms = p95Ms,
            p99Ms = p99Ms
        )
    }

    private fun loadPercent(
        inflight: Int,
        queueDepth: Int,
        droppedLogs: Long,
        errorCount: Int,
        healthState: String?
    ): Int {
        val base = (inflight * 12) + (queueDepth * 6) + (droppedLogs.coerceAtMost(12).toInt() * 2) + (errorCount * 18)
        val healthPenalty = when {
            healthState.equals("UNREACHABLE", ignoreCase = true) || healthState.equals("FAILED", ignoreCase = true) -> 30
            healthState.equals("DEGRADED", ignoreCase = true) -> 16
            else -> 0
        }
        return (base + healthPenalty).coerceIn(0, 100)
    }

    private fun memoryPressurePercent(queueDepth: Int, droppedLogs: Long, errorCount: Int): Int {
        return ((queueDepth * 8) + (droppedLogs.coerceAtMost(10).toInt() * 4) + (errorCount * 10)).coerceIn(0, 100)
    }

    private fun utilizationPercent(totalInflight: Int, totalQueueDepth: Int, droppedLogCount: Long): Double {
        return ((totalInflight * 7) + (totalQueueDepth * 4) + (droppedLogCount.coerceAtMost(20).toInt() * 1.5))
            .coerceIn(0.0, 100.0)
    }

    private fun runtimeSnapshots(): List<PluginRuntimeSnapshot> = pluginSnapshotProvider?.invoke().orEmpty()

    private fun kernelAssetMetadata(): NodeAssetMetadata {
        val address = runCatching { InetAddress.getLocalHost().hostAddress }.getOrDefault("127.0.0.1")
        return NodeAssetMetadata(
            assetId = "KERNEL-${address.replace('.', '-')}",
            address = address,
            zone = "local-control",
            region = "local",
            role = "control-plane",
            roleDescription = "Orchestration & Control Plane",
            featured = true
        )
    }

    private fun fallbackAssetMetadata(node: JvmNode): NodeAssetMetadata {
        return NodeAssetMetadata(
            assetId = node.pluginId ?: node.id,
            address = null,
            zone = null,
            region = null,
            role = node.kind,
            roleDescription = node.label,
            featured = node.id == "kernel"
        )
    }

    private fun toNodeAssetMetadata(asset: PluginNodeAssetMetadata?): NodeAssetMetadata? {
        return asset?.let {
            NodeAssetMetadata(
                assetId = it.assetId,
                address = it.address,
                zone = it.zone,
                region = it.region,
                role = it.role,
                roleDescription = it.roleDescription,
                featured = it.featured
            )
        }
    }

    private fun assetLabels(asset: NodeAssetMetadata?): Map<String, String> {
        if (asset == null) return emptyMap()
        return buildMap {
            asset.assetId?.let { put("assetId", it) }
            asset.address?.let { put("address", it) }
            asset.zone?.let { put("zone", it) }
            asset.region?.let { put("region", it) }
            asset.role?.let { put("role", it) }
        }
    }

    private fun resourceMetricsFor(
        summary: NodeSummary,
        runtimeSnapshot: PluginRuntimeSnapshot?,
        kernelMetrics: KernelProcessMetrics
    ): NodeResourceMetrics {
        if (summary.node.id == "kernel") {
            return NodeResourceMetrics(
                cpuPercent = kernelMetrics.processCpuLoad,
                memoryUsedBytes = kernelMetrics.heapUsedBytes,
                memoryMaxBytes = kernelMetrics.heapMaxBytes,
                memoryPercent = kernelMetrics.heapUsedPercent,
                metricsDerived = false
            )
        }

        val diagnostics = runtimeSnapshot?.diagnostics
        val hasRealMetrics = diagnostics?.processCpuLoadPercent != null || diagnostics?.heapUsedPercent != null
        if (summary.node.runtimeMode.equals("EXTERNAL_JVM", ignoreCase = true) && hasRealMetrics) {
            return NodeResourceMetrics(
                cpuPercent = diagnostics.processCpuLoadPercent,
                memoryUsedBytes = diagnostics.heapUsedBytes,
                memoryMaxBytes = diagnostics.heapMaxBytes,
                memoryPercent = diagnostics.heapUsedPercent,
                metricsDerived = false
            )
        }

        return NodeResourceMetrics(
            cpuPercent = null,
            memoryUsedBytes = null,
            memoryMaxBytes = null,
            memoryPercent = null,
            metricsDerived = false
        )
    }

    private fun degradationReason(summary: NodeSummary, runtimeSnapshot: PluginRuntimeSnapshot?): String? {
        val diagnostics = runtimeSnapshot?.diagnostics
        val lastFailure = runtimeSnapshot?.lastFailure?.message
        return when {
            !summary.node.healthState.equals("HEALTHY", ignoreCase = true) -> summary.node.healthState
            summary.errorCount > 0 -> "Error activity detected"
            summary.node.droppedLogCount > 0 -> "Dropped logs"
            summary.node.eventQueueDepth >= 8 -> "Backpressure risk"
            summary.summaryLatencyMs() != null && summary.summaryLatencyMs()!! >= 250L -> "High control latency"
            diagnostics?.eventOverflowed == true -> "Event queue overflow"
            !lastFailure.isNullOrBlank() -> lastFailure
            else -> null
        }
    }

    private fun primaryActionFor(summary: NodeSummary, runtimeSnapshot: PluginRuntimeSnapshot?): String {
        return "inspect"
    }

    private fun secondaryActionFor(summary: NodeSummary, runtimeSnapshot: PluginRuntimeSnapshot?): String? {
        val lifecycle = summary.node.lifecycleState.orEmpty().uppercase()
        if (summary.node.id == "kernel") return null
        if (lifecycle == "STOPPED") return "start"
        return if (degradationReason(summary, runtimeSnapshot) != null) "reload" else "stop"
    }

    private fun NodeSummary.summaryLatencyMs(): Long? = lastHealthLatencyMs ?: lastAdminLatencyMs

    private fun filterStructuredLogs(
        query: String?,
        level: String?,
        source: String?,
        sinceEpochMs: Long?
    ): List<StructuredLogRecord> {
        val queryLower = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val sourceLower = source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val levelUpper = level?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        return recentStructuredLogs().filter { record ->
            val matchesLevel = levelUpper == null || record.level.uppercase() == levelUpper
            val matchesSource = sourceLower == null || record.source.lowercase().contains(sourceLower)
            val matchesSince = sinceEpochMs == null || record.timestamp >= sinceEpochMs
            val haystack = buildString {
                append(record.source)
                append('\n')
                append(record.message)
                append('\n')
                append(record.throwable.orEmpty())
                append('\n')
                append(record.traceId.orEmpty())
                append('\n')
                append(record.spanId.orEmpty())
                append('\n')
                append(record.pluginId.orEmpty())
                append('\n')
                append(record.attributes.entries.joinToString(" ") { "${it.key}:${it.value}" })
            }.lowercase()
            val matchesQuery = queryLower == null || haystack.contains(queryLower)
            matchesLevel && matchesSource && matchesSince && matchesQuery
        }.asReversed()
    }

    private fun buildLogHistogram(items: List<StructuredLogRecord>, sinceEpochMs: Long?): LogHistogramSnapshot {
        val now = System.currentTimeMillis()
        val windowStart = sinceEpochMs ?: (items.minOfOrNull { it.timestamp } ?: now)
        val windowEnd = max(items.maxOfOrNull { it.timestamp } ?: now, windowStart + 1)
        val bucketCount = 21
        val bucketSizeMs = max(1L, ceil((windowEnd - windowStart).toDouble() / bucketCount.toDouble()).toLong())
        val buckets = MutableList(bucketCount) { index ->
            val start = windowStart + (index * bucketSizeMs)
            val end = if (index == bucketCount - 1) max(windowEnd, start + 1) else start + bucketSizeMs
            LogHistogramBucket(startEpochMs = start, endEpochMs = end, totalCount = 0)
        }
        val levelMaps = MutableList(bucketCount) { linkedMapOf<String, Int>() }

        items.forEach { item ->
            val rawIndex = ((item.timestamp - windowStart) / bucketSizeMs).toInt()
            val index = rawIndex.coerceIn(0, bucketCount - 1)
            val bucket = buckets[index]
            buckets[index] = bucket.copy(totalCount = bucket.totalCount + 1)
            val levelKey = item.level.uppercase()
            levelMaps[index][levelKey] = (levelMaps[index][levelKey] ?: 0) + 1
        }

        return LogHistogramSnapshot(
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
            bucketSizeMs = bucketSizeMs,
            buckets = buckets.mapIndexed { index, bucket ->
                bucket.copy(levelCounts = levelMaps[index].toMap())
            }
        )
    }

    private fun enrichLog(entry: LogEntry): StructuredLogRecord {
        val pluginId = inferPluginId(entry.source, entry.message)
        val traceId = extractToken(entry.message, "trace")
        val spanId = extractToken(entry.message, "span")
        val cluster = extractToken(entry.message, "cluster")
        val instance = extractToken(entry.message, "instance")
        val service = pluginId ?: entry.source.substringBefore('.').takeIf { it.isNotBlank() }
        val attributes = buildMap {
            pluginId?.let { put("pluginId", it) }
            traceId?.let { put("traceId", it) }
            spanId?.let { put("spanId", it) }
            cluster?.let { put("cluster", it) }
            instance?.let { put("instance", it) }
            put("source", entry.source)
        }
        val payload = buildJsonObject {
            traceId?.let { put("traceId", it) }
            spanId?.let { put("spanId", it) }
            pluginId?.let { put("pluginId", it) }
            cluster?.let { put("cluster", it) }
            instance?.let { put("instance", it) }
            put("message", entry.message)
            entry.throwable?.let { put("throwable", it) }
        }.takeIf { it.isNotEmpty() }
        val meta = buildJsonObject {
            service?.let { put("service", it) }
            cluster?.let { put("cluster", it) }
            instance?.let { put("instance", it) }
            put("timestamp", entry.timestamp)
            put("level", entry.level)
            putJsonObject("attributes") {
                attributes.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            }
        }
        return StructuredLogRecord(
            timestamp = entry.timestamp,
            level = entry.level,
            source = entry.source,
            message = entry.message,
            throwable = entry.throwable,
            traceId = traceId,
            spanId = spanId,
            pluginId = pluginId,
            service = service,
            cluster = cluster,
            instance = instance,
            payload = payload,
            meta = meta,
            attributes = attributes
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

        buffers.logSnapshot().forEach { record ->
            merged[record.snapshotKey()] = record
        }

        return merged.values.toList()
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

    private data class TraceGroup(
        val traceId: String,
        val startEpochMs: Long,
        val durationMs: Long,
        val root: TraceSpanEvent,
        val spans: List<TraceSpanEvent>,
        val operation: String,
        val service: String,
        val status: String,
        val errorCount: Int,
        val activeSpanCount: Int,
        val httpStatusCode: Int?
    )

    private data class TraceQueryResult(
        val now: Long,
        val filters: TraceFilterOptions,
        val filteredGroups: List<TraceGroup>,
        val visibleGroups: List<TraceGroup>,
        val selectedGroup: TraceGroup?,
        val selectedSpan: TraceSpanEvent?
    )
}
