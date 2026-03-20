import { state, refreshSelectionDefaults } from './state.js';
import { fetchPlugins, refreshMetrics, refreshNodes, refreshLogs } from './api.js';
import { renderChrome, renderTopologyPanel, renderTracesPanel } from './render.js';

export function startStreamPoll() {
    if (state.streamIntervalId !== null) clearInterval(state.streamIntervalId);
    state.streamIntervalId = setInterval(async () => {
        if (!state.streamEnabled) return;
        await fetchPlugins();
        refreshMetrics();
        refreshNodes();
        await refreshLogs();
    }, state.refreshIntervalMs);
}

export function connectStream() {
    if (!state.streamEnabled) return;
    if (state.eventSource) state.eventSource.close();
    state.connectionState = "Connecting";
    renderChrome();
    state.eventSource = new EventSource("/api/plugins/observability/stream");
    state.eventSource.addEventListener("open", () => {
        state.connectionState = "Live";
        startStreamPoll();
        renderChrome();
    });
    state.eventSource.addEventListener("error", () => {
        state.connectionState = "Retrying";
        renderChrome();
    });
    state.eventSource.addEventListener("jvm_status", (event) => {
        upsertNode(JSON.parse(event.data));
    });
    state.eventSource.addEventListener("plugin_status", (event) => {
        upsertNode(JSON.parse(event.data));
    });
    state.eventSource.addEventListener("trace_event", (event) => {
        const trace = JSON.parse(event.data);
        state.traces.push(trace);
        state.traces = state.traces.slice(-150);
        refreshSelectionDefaults();
        renderTopologyPanel();
        renderTracesPanel();
    });
    state.eventSource.addEventListener("flow_event", (event) => {
        const flow = JSON.parse(event.data);
        state.flows.push(flow);
        state.flows = state.flows.slice(-150);
        state.activeFlows.push({
            ...flow,
            id: crypto.randomUUID(),
            createdAt: Date.now(),
            ttlMs: 2200,
            durationMs: 1300
        });
        renderTopologyPanel();
    });
    state.eventSource.addEventListener("log_event", () => {
        // logs are polled by startStreamPoll(); no action needed here
    });
    state.eventSource.addEventListener("panel_update", (event) => {
        const panel = JSON.parse(event.data);
        state.panels = [...state.panels.filter((item) => item.id !== panel.id), panel];
        renderChrome();
        renderTopologyPanel();
    });
}

export function upsertNode(node) {
    const index = state.topology.findIndex((item) => item.id === node.id);
    if (index >= 0) state.topology[index] = node;
    else state.topology.push(node);
}
