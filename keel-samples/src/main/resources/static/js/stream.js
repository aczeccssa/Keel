import { API_BASE, SNAPSHOT_WINDOW_MS } from './config.js';
import { renderChrome, renderLogsPanel, renderMetricsPanel, renderNodesPanel, renderOpenApiPanel, renderTopologyPanel, renderTracesPanel } from './render.js';
import { state, refreshSelectionDefaults, rememberMetrics, openApiOperations } from './state.js';
import { buildUrl } from './utils.js';

const TAB_IDS = ['topology', 'traces', 'logs', 'nodes', 'metrics', 'openapi'];

export function connectAllStreams() {
    if (!state.streamEnabled) {
        disconnectAllStreams();
        return;
    }

    TAB_IDS.forEach((tabId) => {
        connectTabStream(tabId);
    });
}

export function reconnectAllStreams() {
    if (!state.streamEnabled) return;
    TAB_IDS.forEach((tabId) => {
        reconnectTabStream(tabId);
    });
}

export function reconnectTabStream(tabId) {
    if (!TAB_IDS.includes(tabId)) return;
    if (!state.streamEnabled) return;
    closeTabStream(tabId);
    connectTabStream(tabId);
}

export function disconnectAllStreams() {
    TAB_IDS.forEach((tabId) => {
        closeTabStream(tabId);
        state.tabConnectionStates[tabId] = 'paused';
    });
    syncConnectionState();
    renderChrome();
}

function connectTabStream(tabId) {
    if (!state.streamEnabled) return;
    state.tabConnectionStates[tabId] = 'connecting';
    syncConnectionState();
    renderChrome();

    const source = new EventSource(streamUrl(tabId));
    state.tabEventSources[tabId] = source;

    source.addEventListener('open', () => {
        state.tabConnectionStates[tabId] = 'live';
        syncConnectionState();
        renderChrome();
    });

    source.addEventListener('error', () => {
        if (!state.streamEnabled) return;
        state.tabConnectionStates[tabId] = 'retrying';
        syncConnectionState();
        renderChrome();
    });

    source.addEventListener('system', (event) => {
        try {
            const payload = JSON.parse(event.data || '{}');
            if (typeof payload.intervalMs === 'number' && payload.intervalMs !== state.refreshIntervalMs) {
                state.refreshIntervalMs = payload.intervalMs;
            }
        } catch {
            // ignore malformed system events
        }
    });

    source.addEventListener('snapshot', (event) => {
        try {
            const payload = JSON.parse(event.data);
            applyTabSnapshot(tabId, payload);
            state.tabConnectionStates[tabId] = 'live';
            syncConnectionState();
            renderChrome();
        } catch (error) {
            if (tabId === 'openapi') {
                state.openApiLoadState = 'error';
                state.openApiError = error && error.message ? error.message : String(error);
                renderOpenApiPanel();
            }
            state.tabConnectionStates[tabId] = 'retrying';
            syncConnectionState();
            renderChrome();
        }
    });
}

function closeTabStream(tabId) {
    const source = state.tabEventSources[tabId];
    if (!source) return;
    source.close();
    delete state.tabEventSources[tabId];
}

function streamUrl(tabId) {
    const params = { intervalMs: state.refreshIntervalMs };
    switch (tabId) {
        case 'topology':
            return buildUrl(`${API_BASE}/plugins/observability/topology`, params);
        case 'traces':
            return buildUrl(`${API_BASE}/plugins/observability/traces`, {
                ...params,
                window: state.traceFilters.window,
                query: state.traceFilters.query,
                status: state.traceFilters.status,
                service: state.traceFilters.service,
                limit: state.traceFilters.limit,
                traceId: state.selectedTraceId,
                spanId: state.selectedSpanId
            });
        case 'logs':
            return buildUrl(`${API_BASE}/plugins/observability/logs`, {
                ...params,
                query: state.logFilters.query,
                level: state.logFilters.level,
                window: state.logFilters.window,
                page: state.logFilters.page,
                pageSize: state.logFilters.pageSize
            });
        case 'nodes':
            return buildUrl(`${API_BASE}/plugins/observability/nodes`, {
                ...params,
                windowMs: SNAPSHOT_WINDOW_MS
            });
        case 'metrics':
            return buildUrl(`${API_BASE}/plugins/observability/metrics`, {
                ...params,
                windowMs: SNAPSHOT_WINDOW_MS
            });
        case 'openapi':
            return buildUrl(`${API_BASE}/plugins/observability/openapi`, params);
        default:
            return buildUrl(`${API_BASE}/plugins/observability/${tabId}`, params);
    }
}

function syncConnectionState() {
    if (!state.streamEnabled) {
        state.connectionState = 'Paused';
        return;
    }

    const statuses = TAB_IDS.map((tabId) => state.tabConnectionStates[tabId] || 'connecting');
    if (statuses.every((status) => status === 'live')) {
        state.connectionState = 'Live';
        return;
    }
    if (statuses.some((status) => status === 'retrying')) {
        state.connectionState = 'Retrying';
        return;
    }
    state.connectionState = 'Connecting';
}

function applyTabSnapshot(tabId, payload) {
    if (tabId === 'topology') {
        applyTopologySnapshot(payload);
        return;
    }
    if (tabId === 'traces') {
        applyTracesSnapshot(payload);
        return;
    }
    if (tabId === 'logs') {
        applyLogsSnapshot(payload);
        return;
    }
    if (tabId === 'nodes') {
        applyNodesSnapshot(payload);
        return;
    }
    if (tabId === 'metrics') {
        applyMetricsSnapshot(payload);
        return;
    }
    if (tabId === 'openapi') {
        applyOpenApiSnapshot(payload);
    }
}

function applyTopologySnapshot(snapshot) {
    const nextFlows = snapshot?.flows || [];
    animateNewFlows(nextFlows);

    state.topology = snapshot?.nodes || [];
    state.nodeSummaries = snapshot?.nodeSummaries || [];
    state.traces = snapshot?.traces || [];
    state.flows = nextFlows;
    state.panels = snapshot?.panels || [];

    if (state.selectedNodeId && !state.topology.some((node) => node.id === state.selectedNodeId)) {
        state.selectedNodeId = null;
    }

    renderTopologyPanel();
}

function applyTracesSnapshot(snapshot) {
    state.traceSummary = { summary: snapshot?.summary || null };
    state.traceListSnapshot = {
        filters: snapshot?.filters || state.traceFilters,
        traces: snapshot?.traces || [],
        selectedTraceId: snapshot?.selectedTraceId || null
    };
    state.traceTimelineSnapshot = {
        selectedTraceId: snapshot?.selectedTraceId || null,
        timeline: snapshot?.timeline || null
    };
    state.traceSpanDetailSnapshot = {
        selectedTraceId: snapshot?.selectedTraceId || null,
        selectedSpanId: snapshot?.selectedSpanId || null,
        spanDetail: snapshot?.spanDetail || null
    };
    state.selectedTraceId = snapshot?.selectedTraceId || null;
    state.selectedSpanId = snapshot?.selectedSpanId || null;
    state.traceSliceVersions.summary += 1;
    state.traceSliceVersions.list += 1;
    state.traceSliceVersions.timeline += 1;
    state.traceSliceVersions.detail += 1;
    refreshSelectionDefaults();
    renderTracesPanel();
}

function applyLogsSnapshot(snapshot) {
    state.logs = snapshot || state.logs;
    refreshSelectionDefaults();
    renderLogsPanel();
}

function applyNodesSnapshot(snapshot) {
    state.nodeDashboard = snapshot?.snapshot || null;
    state.nodeOverview = {
        recentTraceCount: Number(snapshot?.recentTraceCount || 0),
        recentFlowCount: Number(snapshot?.recentFlowCount || 0),
        droppedLogCount: Number(snapshot?.droppedLogCount || 0)
    };
    state.nodeSummaries = state.nodeDashboard?.items
        ? state.nodeDashboard.items.map((item) => item.summary)
        : [];

    if (state.nodeDashboard?.items?.length) {
        const exists = state.nodeDashboard.items.some((item) => item.node.id === state.selectedNodeId);
        if (!exists) state.selectedNodeId = state.nodeDashboard.items[0].node.id;
    } else {
        state.selectedNodeId = null;
    }

    renderNodesPanel();
    renderTopologyPanel();
}

function applyMetricsSnapshot(snapshot) {
    state.metrics = snapshot?.snapshot || null;
    state.latencyHistogram = snapshot?.histogram || state.metrics?.latency?.histogram || null;
    if (state.metrics) rememberMetrics(state.metrics);
    renderMetricsPanel();
}

function applyOpenApiSnapshot(snapshot) {
    const spec = snapshot?.spec;
    if (!spec || typeof spec !== 'object' || !spec.paths || typeof spec.paths !== 'object') {
        throw new Error('Invalid OpenAPI schema');
    }

    state.openApiSpec = spec;
    state.openApiMeta = {
        generatedAtEpochMs: Number(snapshot?.generatedAtEpochMs || 0),
        source: snapshot?.source || ''
    };
    state.openApiLoadState = 'ready';
    state.openApiError = '';

    const operations = openApiOperations(spec);
    if (!operations.some((item) => item.key === state.selectedOpenApiOpKey)) {
        state.selectedOpenApiOpKey = operations[0] ? operations[0].key : null;
    }

    renderOpenApiPanel();
}

function animateNewFlows(nextFlows) {
    const previousKeys = new Set((state.flows || []).map(flowKey));
    const additions = nextFlows.filter((flow) => !previousKeys.has(flowKey(flow))).slice(-12);
    additions.forEach((flow) => {
        state.activeFlows.push({
            ...flow,
            id: crypto.randomUUID(),
            createdAt: Date.now(),
            ttlMs: 2200,
            durationMs: 1300
        });
    });
}

function flowKey(flow) {
    return [
        flow?.traceId || '',
        flow?.spanId || '',
        flow?.edgeFrom || '',
        flow?.edgeTo || '',
        flow?.startEpochMs || 0
    ].join('|');
}
