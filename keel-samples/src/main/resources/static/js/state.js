import { LOG_LIMIT } from './config.js';

let appShell = null;

function initialTabConnectionStates() {
    return {
        topology: 'connecting',
        traces: 'connecting',
        logs: 'connecting',
        nodes: 'connecting',
        metrics: 'connecting',
        openapi: 'connecting'
    };
}

export const state = {
    activeTab: 'topology',
    topology: [],
    traces: [],
    traceDashboard: null,
    traceSummary: null,
    traceListSnapshot: null,
    traceTimelineSnapshot: null,
    traceSpanDetailSnapshot: null,
    traceSliceVersions: { summary: 0, list: 0, timeline: 0, detail: 0 },
    flows: [],
    panels: [],
    metrics: null,
    latencyHistogram: null,
    plugins: [],
    nodeSummaries: [],
    nodeDashboard: null,
    nodeOverview: { recentTraceCount: 0, recentFlowCount: 0, droppedLogCount: 0 },
    logs: {
        page: { items: [], total: 0, limit: LOG_LIMIT, page: 1, pageSize: LOG_LIMIT, totalPages: 0, hasPrev: false, hasNext: false },
        histogram: { windowStartEpochMs: 0, windowEndEpochMs: 0, bucketSizeMs: 0, buckets: [] },
        summary: { totalMatched: 0, showingCount: 0, availableLevels: [], activeWindow: '1h' },
        availableLevels: []
    },
    logFilters: { query: '', level: '', window: '1h', page: 1, pageSize: LOG_LIMIT },
    openApiSpec: null,
    openApiLoadState: 'idle',
    openApiError: '',
    openApiMeta: { generatedAtEpochMs: 0, source: '' },
    openApiFilters: { query: '', tag: '' },
    selectedOpenApiOpKey: null,
    metricsHistory: [],
    selectedNodeId: null,
    selectedTraceId: null,
    selectedSpanId: null,
    traceFilters: { query: '', status: 'all', service: '', window: '1h', limit: 40 },
    selectedLogKey: null,
    streamEnabled: true,
    refreshIntervalMs: 5_000,
    refreshOverlayOpen: false,
    streamIntervalId: null,
    connectionState: 'Booting',
    tabConnectionStates: initialTabConnectionStates(),
    tabEventSources: {},
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

export function setAppShell(element) {
    appShell = element;
}

export function getAppShell() {
    return appShell || document.querySelector('keel-observability-app');
}

export function currentNodeSummary(nodeId) {
    return state.nodeSummaries.find((item) => item.node.id === nodeId) || null;
}

export function selectedNode() {
    return state.topology.find((node) => node.id === state.selectedNodeId) || null;
}

export function selectedTraceSnapshot() {
    return state.traceDashboard || null;
}

export function selectedTraceTimeline() {
    const dashboard = selectedTraceSnapshot();
    if (!dashboard || !dashboard.timeline) return null;
    if (dashboard.timeline.traceId === state.selectedTraceId || !state.selectedTraceId) return dashboard.timeline;
    return null;
}

export function selectedTraceSpan() {
    const dashboard = selectedTraceSnapshot();
    if (!dashboard || !dashboard.spanDetail) return null;
    if (dashboard.spanDetail.spanId === state.selectedSpanId || !state.selectedSpanId) return dashboard.spanDetail;
    return null;
}

export function logKey(item) {
    return [item.timestamp, item.level, item.source, item.message].join('|');
}

export function selectedLog() {
    const items = state.logs.page?.items || [];
    if (!items.length || !state.selectedLogKey) return null;
    return items.find((item) => logKey(item) === state.selectedLogKey) || null;
}

export function refreshSelectionDefaults() {
    const traceIds = (state.traceListSnapshot?.traces || []).map((trace) => trace.traceId);
    if (traceIds.length && !traceIds.includes(state.selectedTraceId)) {
        state.selectedTraceId = state.traceListSnapshot?.selectedTraceId || traceIds[0] || null;
    }
    if (!traceIds.length) state.selectedTraceId = null;

    const spanIds = state.traceTimelineSnapshot?.timeline?.spans ? state.traceTimelineSnapshot.timeline.spans.map((span) => span.spanId) : [];
    if (spanIds.length && !spanIds.includes(state.selectedSpanId)) {
        state.selectedSpanId = state.traceSpanDetailSnapshot?.selectedSpanId || spanIds[0] || null;
    }
    if (!spanIds.length) state.selectedSpanId = null;

    state.traceDashboard = {
        summary: state.traceSummary?.summary || state.traceDashboard?.summary || null,
        filters: state.traceListSnapshot?.filters || state.traceDashboard?.filters || state.traceFilters,
        traces: state.traceListSnapshot?.traces || state.traceDashboard?.traces || [],
        selectedTraceId: state.traceListSnapshot?.selectedTraceId || state.traceTimelineSnapshot?.selectedTraceId || state.selectedTraceId,
        selectedSpanId: state.traceSpanDetailSnapshot?.selectedSpanId || state.selectedSpanId,
        timeline: state.traceTimelineSnapshot?.timeline || null,
        spanDetail: state.traceSpanDetailSnapshot?.spanDetail || null
    };

    const logItems = state.logs.page?.items || [];
    if (state.selectedLogKey && !logItems.some((item) => logKey(item) === state.selectedLogKey)) {
        state.selectedLogKey = null;
    }
}

const OPENAPI_METHOD_ORDER = ['get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace'];

export function openApiOperations(spec = state.openApiSpec) {
    if (!spec || typeof spec !== 'object' || !spec.paths || typeof spec.paths !== 'object') return [];
    const operations = [];
    Object.entries(spec.paths).forEach(([path, methods]) => {
        if (!methods || typeof methods !== 'object') return;
        Object.entries(methods).forEach(([method, operation]) => {
            const normalized = String(method || '').toLowerCase();
            if (!OPENAPI_METHOD_ORDER.includes(normalized)) return;
            operations.push({
                key: `${normalized.toUpperCase()} ${path}`,
                path,
                method: normalized,
                operation: operation || {}
            });
        });
    });

    operations.sort((a, b) => {
        if (a.path !== b.path) return a.path.localeCompare(b.path);
        return OPENAPI_METHOD_ORDER.indexOf(a.method) - OPENAPI_METHOD_ORDER.indexOf(b.method);
    });
    return operations;
}

export function rememberMetrics(snapshot) {
    const cpu = Number(snapshot.kernel.processCpuLoad || 0);
    const heap = Number(snapshot.kernel.heapUsedPercent || 0);
    const flowCount = Number(snapshot.traffic?.recentFlowCount || 0);
    const traceCount = Number(snapshot.traffic?.recentTraceCount || 0);
    state.metricsHistory.push({ cpu, heap, flowCount, traceCount, timestamp: Date.now() });
    if (state.metricsHistory.length > 24) state.metricsHistory.shift();
}
