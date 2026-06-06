import { state } from './state.js';
import { API_BASE } from './config.js';
import { renderChrome, renderOpenApiPanel, renderPanels } from './render.js';
import { hydrateHash } from './events.js';
import { connectAllStreams, reconnectAllStreams, reconnectTabStream } from './stream.js';

export function apiUrl(path) {
    return `${API_BASE}${path}`;
}

export async function fetchPlugins() {
    try {
        const resp = await fetch(apiUrl('/_system/plugins'));
        const json = await resp.json();
        if (json.code === 200 && json.data && json.data.plugins) {
            state.plugins = json.data.plugins;
        } else {
            state.plugins = [];
        }
    } catch {
        state.plugins = [];
    }
    renderChrome();
    renderPanels();
}

export async function togglePlugin(pluginId, action) {
    try {
        await fetch(apiUrl(`/_system/plugins/${encodeURIComponent(pluginId)}/${action}`), { method: 'POST' });
        await fetchPlugins();
        reconnectAllStreams();
    } catch {
        // silent
    }
}

export async function refreshLogs() {
    reconnectTabStream('logs');
}

export async function refreshMetrics() {
    reconnectTabStream('metrics');
}

export async function refreshTraceSummaryList() {
    reconnectTabStream('traces');
}

export async function refreshTraceTimelineAndDetail() {
    reconnectTabStream('traces');
}

export async function refreshTraces() {
    reconnectTabStream('traces');
}

export async function refreshNodes() {
    reconnectTabStream('nodes');
}

export async function refreshOpenApiSpec({ render = true } = {}) {
    state.openApiLoadState = 'loading';
    state.openApiError = '';
    if (render) {
        renderOpenApiPanel();
        renderChrome();
    }
    reconnectTabStream('openapi');
}

export async function hydrate() {
    hydrateHash();
    state.connectionState = 'Connecting';
    state.openApiLoadState = 'loading';
    state.openApiError = '';
    renderChrome();
    renderPanels();
    await fetchPlugins();
    connectAllStreams();
}
