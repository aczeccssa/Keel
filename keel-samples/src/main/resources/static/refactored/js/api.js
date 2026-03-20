import { state, refreshSelectionDefaults, rememberMetrics } from './state.js';
import { requestJson, buildUrl, sinceFromWindow } from './utils.js';
import { LOG_LIMIT, SNAPSHOT_WINDOW_MS } from './config.js';
import { renderChrome, renderPanels, renderLogsPanel, renderMetricsPanel, renderTopologyPanel, renderTracesPanel, renderNodesPanel, renderPluginList } from './render.js';
import { connectStream } from './stream.js';
import { hydrateHash } from './events.js';

export async function fetchPlugins() {
    try {
        const resp = await fetch('/api/_system/plugins');
        const json = await resp.json();
        if (json.code === 200 && json.data && json.data.plugins) {
            window._cachedPlugins = json.data.plugins;
            renderPluginList(json.data.plugins);
        }
    } catch (e) {
        const container = document.getElementById('admin-plugin-list');
        if (container) {
            container.innerHTML = '<div class="empty-state">Failed to load plugins</div>';
        }
    }
}

export async function togglePlugin(pluginId, action) {
    try {
        await fetch(`/api/_system/plugins/${encodeURIComponent(pluginId)}/${action}`, { method: 'POST' });
        await fetchPlugins();
    } catch (e) {
        // silent
    }
}

export async function refreshLogs() {
    const page = await requestJson(buildUrl("/api/plugins/observability/logs", {
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
    const payload = await requestJson(buildUrl("/api/plugins/observability/metrics", { windowMs: SNAPSHOT_WINDOW_MS }));
    state.metrics = payload.snapshot || payload;
    if (state.metrics) rememberMetrics(state.metrics);
    renderMetricsPanel();
    renderTopologyPanel();
    renderTracesPanel();
    renderChrome();
}

export async function refreshNodes() {
    const payload = await requestJson(buildUrl("/api/plugins/observability/nodes", { windowMs: SNAPSHOT_WINDOW_MS }));
    state.nodeSummaries = payload.nodes || payload;
    renderNodesPanel();
    renderTopologyPanel();
    renderMetricsPanel();
    renderChrome();
}

export async function hydrate() {
    hydrateHash();
    renderChrome();
    const [topology, traces, flows, panels, metrics, nodes, logs] = await Promise.all([
        requestJson("/api/plugins/observability/topology"),
        requestJson(buildUrl("/api/plugins/observability/traces", { limit: 120 })),
        requestJson(buildUrl("/api/plugins/observability/flows", { limit: 120 })),
        requestJson("/api/plugins/observability/panels"),
        requestJson(buildUrl("/api/plugins/observability/metrics", { windowMs: SNAPSHOT_WINDOW_MS })),
        requestJson(buildUrl("/api/plugins/observability/nodes", { windowMs: SNAPSHOT_WINDOW_MS })),
        requestJson(buildUrl("/api/plugins/observability/logs", {
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
    renderPanels();
    connectStream();
}
