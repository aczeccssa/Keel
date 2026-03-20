import { LOG_LIMIT } from './config.js';

export const state = {
    activeTab: "topology",
    topology: [],
    traces: [],
    flows: [],
    panels: [],
    metrics: null,
    nodeSummaries: [],
    logs: { items: [], total: 0, limit: LOG_LIMIT },
    logFilters: { query: "", level: "", source: "", window: "1h" },
    metricsHistory: [],
    selectedNodeId: null, // 默认无选择
    selectedTraceId: null,
    selectedSpanId: null,
    selectedLogKey: null,
    streamEnabled: true,
    refreshIntervalMs: 5_000,
    streamIntervalId: null,
    connectionState: "booting",
    zoom: 1,
    panX: 0,
    panY: 0,
    nodePositions: {},
    activeFlows: [],
    draggingNodeId: null,
    dragState: null,
    panState: null,
    _pendingMove: null,
    _dragRect: null,
    refreshTimers: {},
    eventSource: null
};

export const els = {
    get sidebarNav() { return document.getElementById("sidebar-nav"); },
    get sidebarMeta() { return document.getElementById("sidebar-meta"); },
    get topbarTabs() { return document.getElementById("topbar-tabs"); },
    get connectionText() { return document.getElementById("connection-text"); },
    get connectionPill() { return document.getElementById("connection-pill"); },
    get streamToggleBtn() { return document.getElementById("stream-toggle-btn"); },
    get topologyStage() { return document.getElementById("topology-stage"); },
    get topologyViewport() { return document.getElementById("topology-viewport"); },
    get topologyBaseSvg() { return document.getElementById("topology-base-svg"); },
    get topologyFlowSvg() { return document.getElementById("topology-flow-svg"); },
    get topologyNodeLayer() { return document.getElementById("topology-node-layer"); },
    get zoomLabel() { return document.getElementById("zoom-label"); }
};

export function currentNodeSummary(nodeId) {
    return state.nodeSummaries.find((item) => item.node.id === nodeId) || null;
}

export function selectedNode() {
    return state.topology.find((node) => node.id === state.selectedNodeId) || null;
}

export function groupedTraces() {
    const map = new Map();
    state.traces.forEach((trace) => {
        const current = map.get(trace.traceId) || [];
        current.push(trace);
        map.set(trace.traceId, current);
    });
    return [...map.entries()].map(([traceId, spans]) => {
        const ordered = [...spans].sort((a, b) => (a.startEpochMs || 0) - (b.startEpochMs || 0));
        const start = ordered[0] ? ordered[0].startEpochMs : 0;
        const end = Math.max(...ordered.map((span) => span.endEpochMs || (span.startEpochMs || 0)));
        const errors = ordered.filter((span) => String(span.status).toUpperCase() === "ERROR").length;
        return {
            traceId,
            spans: ordered,
            startEpochMs: start,
            endEpochMs: end,
            durationMs: Math.max(end - start, 0),
            operation: ordered[0] ? ordered[0].operation : traceId,
            service: ordered[0] ? ordered[0].service : "unknown",
            errorCount: errors,
            spanCount: ordered.length
        };
    }).sort((a, b) => (b.startEpochMs || 0) - (a.startEpochMs || 0));
}

export function selectedTraceGroup() {
    const groups = groupedTraces();
    if (!groups.length) return null;
    const match = groups.find((group) => group.traceId === state.selectedTraceId);
    return match || groups[0];
}

export function selectedSpan() {
    const group = selectedTraceGroup();
    if (!group) return null;
    return group.spans.find((span) => span.spanId === state.selectedSpanId) || group.spans[0] || null;
}

export function logKey(item) {
    return [item.timestamp, item.level, item.source, item.message].join("|");
}

export function selectedLog() {
    if (!state.logs.items || !state.logs.items.length) return null;
    const match = state.logs.items.find((item) => logKey(item) === state.selectedLogKey);
    return match || state.logs.items[0];
}

export function refreshSelectionDefaults() {
    const groups = groupedTraces();
    if (groups.length && !groups.some((group) => group.traceId === state.selectedTraceId)) {
        state.selectedTraceId = groups[0].traceId;
    }
    const selectedGroup = selectedTraceGroup();
    if (selectedGroup && !selectedGroup.spans.some((span) => span.spanId === state.selectedSpanId)) {
        state.selectedSpanId = selectedGroup.spans[0] ? selectedGroup.spans[0].spanId : null;
    }
    if (state.logs.items && state.logs.items.length && !state.logs.items.some((item) => logKey(item) === state.selectedLogKey)) {
        state.selectedLogKey = logKey(state.logs.items[0]);
    }
}

export function rememberMetrics(snapshot) {
    const cpu = Number(snapshot.kernel.processCpuLoad || 0);
    const heap = Number(snapshot.kernel.heapUsedPercent || 0);
    state.metricsHistory.push({ cpu, heap, timestamp: Date.now() });
    if (state.metricsHistory.length > 24) state.metricsHistory.shift();
}
