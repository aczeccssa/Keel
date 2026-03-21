import { state, refreshSelectionDefaults, rememberMetrics, openApiOperations } from './state.js';
import { requestJson, buildUrl, sinceFromWindow } from './utils.js';
import { LOG_LIMIT, SNAPSHOT_WINDOW_MS, API_BASE } from './config.js';
import { renderChrome, renderPanels, renderLogsPanel, renderMetricsPanel, renderTopologyPanel, renderTracesPanel, renderNodesPanel, renderOpenApiPanel } from './render.js';
import { connectStream } from './stream.js';
import { hydrateHash } from './events.js';

export function apiUrl(path) {
    return `${API_BASE}${path}`;
}

export async function fetchPlugins() {
    try {
        const resp = await fetch(apiUrl('/_system/plugins'));
        const json = await resp.json();
        if (json.code === 200 && json.data && json.data.plugins) {
            state.plugins = json.data.plugins;
            renderMetricsPanel();
        }
    } catch {
        state.plugins = [];
        renderMetricsPanel();
    }
}

export async function togglePlugin(pluginId, action) {
    try {
        await fetch(apiUrl(`/_system/plugins/${encodeURIComponent(pluginId)}/${action}`), { method: 'POST' });
        await fetchPlugins();
    } catch {
        // silent
    }
}

export async function refreshLogs() {
    const page = await requestJson(buildUrl(apiUrl('/plugins/observability/logs'), {
        limit: LOG_LIMIT,
        query: state.logFilters.query,
        level: state.logFilters.level,
        source: state.logFilters.source,
        since: sinceFromWindow(state.logFilters.window)
    }));

    state.logs = page.page || page;
    refreshSelectionDefaults();
    renderLogsPanel();
    renderChrome();
}

export async function refreshMetrics() {
    const payload = await requestJson(buildUrl(apiUrl('/plugins/observability/metrics'), { windowMs: SNAPSHOT_WINDOW_MS }));
    state.metrics = payload.snapshot || payload;
    if (state.metrics) rememberMetrics(state.metrics);
    renderMetricsPanel();
    renderTopologyPanel();
    renderTracesPanel();
    renderChrome();
}

export async function refreshNodes() {
    const payload = await requestJson(buildUrl(apiUrl('/plugins/observability/nodes'), { windowMs: SNAPSHOT_WINDOW_MS }));
    state.nodeSummaries = payload.nodes || payload;
    renderNodesPanel();
    renderTopologyPanel();
    renderMetricsPanel();
    renderChrome();
}

export async function refreshOpenApiSpec({ render = true } = {}) {
    state.openApiLoadState = 'loading';
    state.openApiError = '';
    if (render) {
        renderOpenApiPanel();
        renderChrome();
    }

    try {
        const response = await fetch(apiUrl('/_system/docs/openapi.json'), {
            headers: { Accept: 'application/json' }
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const spec = await response.json();
        if (!spec || typeof spec !== 'object' || !spec.paths || typeof spec.paths !== 'object') {
            throw new Error('Invalid OpenAPI schema');
        }

        state.openApiSpec = spec;
        state.openApiLoadState = 'ready';
        state.openApiError = '';
        const operations = openApiOperations(spec);
        if (!operations.some((item) => item.key === state.selectedOpenApiOpKey)) {
            state.selectedOpenApiOpKey = operations[0] ? operations[0].key : null;
        }
    } catch (error) {
        state.openApiSpec = null;
        state.openApiLoadState = 'error';
        state.openApiError = error && error.message ? error.message : String(error);
        state.selectedOpenApiOpKey = null;
    }

    if (render) {
        renderOpenApiPanel();
        renderChrome();
    }
}

export async function hydrate() {
    hydrateHash();
    renderChrome();
    const openApiPromise = refreshOpenApiSpec({ render: false });
    const [topology, traces, flows, panels, metrics, nodes, logs] = await Promise.all([
        requestJson(apiUrl('/plugins/observability/topology')),
        requestJson(buildUrl(apiUrl('/plugins/observability/traces'), { limit: 120 })),
        requestJson(buildUrl(apiUrl('/plugins/observability/flows'), { limit: 120 })),
        requestJson(apiUrl('/plugins/observability/panels')),
        requestJson(buildUrl(apiUrl('/plugins/observability/metrics'), { windowMs: SNAPSHOT_WINDOW_MS })),
        requestJson(buildUrl(apiUrl('/plugins/observability/nodes'), { windowMs: SNAPSHOT_WINDOW_MS })),
        requestJson(buildUrl(apiUrl('/plugins/observability/logs'), {
            limit: LOG_LIMIT,
            since: sinceFromWindow(state.logFilters.window)
        }))
    ]);
    state.topology = topology.nodes || topology || [];
    state.traces = (traces.spans || traces || []).slice(-120);
    state.flows = (flows.flows || flows || []).slice(-120);
    state.panels = panels.panels || panels || [];
    state.metrics = metrics.snapshot || metrics;
    state.nodeSummaries = nodes.nodes || nodes || [];
    state.logs = logs.page || logs;

    if (state.metrics) rememberMetrics(state.metrics);
    refreshSelectionDefaults();
    renderChrome();
    await fetchPlugins();
    await openApiPromise;
    renderPanels();
    connectStream();
}
