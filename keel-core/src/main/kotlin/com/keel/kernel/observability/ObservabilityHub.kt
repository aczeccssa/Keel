package com.keel.kernel.observability

import com.keel.kernel.logging.KeelLoggerService
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
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class ObservabilityHub(
    private val config: ObservabilityConfig = ObservabilityConfig.fromSystem()
) : KeelObservability {
    private val json = Json { encodeDefaults = true }
    private val panels = ConcurrentHashMap<String, PanelInfo>()
    private val traceBuffer = ArrayDeque<TraceSpanEvent>()
    private val flowBuffer = ArrayDeque<FlowEvent>()
    private val eventFlow = MutableSharedFlow<ObservabilityStreamEvent>(extraBufferCapacity = 512)

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
                val event = LogEvent(
                    timestamp = entry.timestamp,
                    level = entry.level,
                    source = entry.source,
                    message = entry.message
                )
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
}
