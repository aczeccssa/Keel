import './shell/SidebarShell.js';
import './shell/TopbarShell.js';
import './shell/RefreshOverlay.js';
import './PanelTopology.js';
import './PanelTraces.js';
import './PanelLogs.js';
import './PanelNodes.js';
import './PanelMetrics.js';
import './PanelOpenApi.js';

import { KeelElement } from './base/KeelElement.js';
import { state, groupedTraces } from '../state.js';
import { TABS } from '../config.js';
import { clamp } from '../utils.js';
import { setActiveTab, hydrateHash, downloadLogsJSON, downloadLogsText } from '../events.js';
import { refreshLogs, refreshMetrics, refreshOpenApiSpec, togglePlugin } from '../api.js';
import { connectStream, startStreamPoll } from '../stream.js';
import { renderChrome, renderLogsPanel, renderMetricsPanel, renderNodesPanel, renderOpenApiPanel, renderTopologyPanel, renderTracesPanel } from '../render.js';

export class ObservabilityApp extends KeelElement {
    hostStyles() {
        return 'display:block;height:100vh;';
    }

    template() {
        return `
            <div class="app-shell">
                <keel-sidebar data-ref="sidebar"></keel-sidebar>

                <div class="main-shell">
                    <keel-topbar data-ref="topbar"></keel-topbar>

                    <main class="content">
                        <keel-panel-topology class="tab-panel" data-ref="topologyPanel"></keel-panel-topology>
                        <keel-panel-traces class="tab-panel" data-ref="tracesPanel"></keel-panel-traces>
                        <keel-panel-logs class="tab-panel" data-ref="logsPanel"></keel-panel-logs>
                        <keel-panel-nodes class="tab-panel" data-ref="nodesPanel"></keel-panel-nodes>
                        <keel-panel-metrics class="tab-panel" data-ref="metricsPanel"></keel-panel-metrics>
                        <keel-panel-openapi class="tab-panel" data-ref="openApiPanel"></keel-panel-openapi>
                    </main>
                </div>
            </div>
            <keel-refresh-overlay data-ref="overlay"></keel-refresh-overlay>
        `;
    }

    afterMount() {
        this._bootErrorMessage = '';
        this._panels = {
            topology: this.refs.topologyPanel,
            traces: this.refs.tracesPanel,
            logs: this.refs.logsPanel,
            nodes: this.refs.nodesPanel,
            metrics: this.refs.metricsPanel,
            openapi: this.refs.openApiPanel
        };

        window.addEventListener('hashchange', () => {
            hydrateHash();
            this.renderChrome(state);
            this.renderPanels(state);
        });

        [
            'keel:navigate',
            'keel:open-refresh-overlay',
            'keel:close-refresh-overlay',
            'keel:stream-toggle',
            'keel:refresh-interval-change',
            'keel:node-select',
            'keel:node-clear',
            'keel:trace-select',
            'keel:span-select',
            'keel:log-select',
            'keel:logs-refresh',
            'keel:download-logs-json',
            'keel:download-logs-text',
            'keel:topology-zoom-in',
            'keel:topology-zoom-out',
            'keel:topology-layout-reset',
            'keel:topology-recenter',
            'keel:plugin-toggle',
            'keel:openapi-filter-change',
            'keel:openapi-select',
            'keel:openapi-retry'
        ].forEach((eventName) => {
            this.addEventListener(eventName, (event) => {
                this.handleAppEvent(event).catch((error) => {
                    console.error(error);
                });
            });
        });
    }

    async handleAppEvent(event) {
        const { type, detail } = event;
        if (type === 'keel:navigate') {
            setActiveTab(detail.tabId);
            return;
        }
        if (type === 'keel:open-refresh-overlay') {
            state.refreshOverlayOpen = true;
            this.renderChrome(state);
            return;
        }
        if (type === 'keel:close-refresh-overlay') {
            state.refreshOverlayOpen = false;
            this.renderChrome(state);
            return;
        }
        if (type === 'keel:stream-toggle') {
            state.streamEnabled = !state.streamEnabled;
            if (state.streamEnabled) {
                connectStream();
            } else {
                if (state.eventSource) state.eventSource.close();
                state.eventSource = null;
                if (state.streamIntervalId !== null) {
                    clearInterval(state.streamIntervalId);
                    state.streamIntervalId = null;
                }
                state.connectionState = 'Paused';
            }
            renderChrome();
            return;
        }
        if (type === 'keel:refresh-interval-change') {
            state.refreshIntervalMs = detail.ms;
            state.refreshOverlayOpen = false;
            startStreamPoll();
            this.renderChrome(state);
            return;
        }
        if (type === 'keel:node-select') {
            state.selectedNodeId = detail.nodeId;
            if (detail.resetViewport) {
                state.panX = 0;
                state.panY = 0;
                state.zoom = 1;
            }
            if (detail.navigate) {
                setActiveTab(detail.navigate);
                return;
            }
            renderTopologyPanel();
            renderNodesPanel();
            return;
        }
        if (type === 'keel:node-clear') {
            state.selectedNodeId = null;
            renderTopologyPanel();
            renderNodesPanel();
            return;
        }
        if (type === 'keel:trace-select') {
            state.selectedTraceId = detail.traceId;
            const group = groupedTraces().find((item) => item.traceId === detail.traceId);
            state.selectedSpanId = group && group.spans[0] ? group.spans[0].spanId : null;
            renderTracesPanel();
            return;
        }
        if (type === 'keel:span-select') {
            state.selectedSpanId = detail.spanId;
            renderTracesPanel();
            return;
        }
        if (type === 'keel:log-select') {
            state.selectedLogKey = detail.logKey;
            renderLogsPanel();
            return;
        }
        if (type === 'keel:logs-refresh') {
            state.logFilters.query = detail.query;
            state.logFilters.level = detail.level;
            state.logFilters.source = detail.source;
            state.logFilters.window = detail.window;
            await refreshLogs();
            await refreshMetrics();
            return;
        }
        if (type === 'keel:download-logs-json') {
            downloadLogsJSON();
            return;
        }
        if (type === 'keel:download-logs-text') {
            downloadLogsText();
            return;
        }
        if (type === 'keel:topology-zoom-in') {
            state.zoom = clamp(state.zoom + 0.12, 0.65, 1.85);
            renderTopologyPanel();
            return;
        }
        if (type === 'keel:topology-zoom-out') {
            state.zoom = clamp(state.zoom - 0.12, 0.65, 1.85);
            renderTopologyPanel();
            return;
        }
        if (type === 'keel:topology-layout-reset') {
            state.nodePositions = {};
            renderTopologyPanel();
            return;
        }
        if (type === 'keel:topology-recenter') {
            state.panX = 0;
            state.panY = 0;
            state.zoom = 1;
            renderTopologyPanel();
            return;
        }
        if (type === 'keel:plugin-toggle') {
            await togglePlugin(detail.pluginId, detail.action);
            return;
        }
        if (type === 'keel:openapi-filter-change') {
            state.openApiFilters.query = detail.query || '';
            state.openApiFilters.tag = detail.tag || '';
            renderOpenApiPanel();
            return;
        }
        if (type === 'keel:openapi-select') {
            state.selectedOpenApiOpKey = detail.opKey || null;
            renderOpenApiPanel();
            return;
        }
        if (type === 'keel:openapi-retry') {
            await refreshOpenApiSpec({ render: true });
        }
    }

    renderChrome(appState) {
        this.ensureInitialized();
        this.refs.sidebar.render(appState);
        this.refs.topbar.render(appState);
        this.refs.overlay.render({
            open: appState.refreshOverlayOpen,
            refreshIntervalMs: appState.refreshIntervalMs
        });
        TABS.forEach((tab) => {
            const panel = this._panels[tab.id];
            if (!panel) return;
            panel.classList.toggle('is-active', tab.id === appState.activeTab);
        });
    }

    renderPanels(appState) {
        this.renderTopologyPanel(appState);
        this.renderTracesPanel(appState);
        this.renderLogsPanel(appState);
        this.renderNodesPanel(appState);
        this.renderMetricsPanel(appState);
        this.renderOpenApiPanel(appState);
    }

    renderTopologyPanel(appState) {
        this.ensureInitialized();
        this.refs.topologyPanel.render(appState, { bootError: this._bootErrorMessage });
    }

    renderTracesPanel(appState) {
        this.ensureInitialized();
        this.refs.tracesPanel.render(appState);
    }

    renderLogsPanel(appState) {
        this.ensureInitialized();
        this.refs.logsPanel.render(appState);
    }

    renderNodesPanel(appState) {
        this.ensureInitialized();
        this.refs.nodesPanel.render(appState);
    }

    renderMetricsPanel(appState) {
        this.ensureInitialized();
        this.refs.metricsPanel.render(appState);
    }

    renderOpenApiPanel(appState) {
        this.ensureInitialized();
        this.refs.openApiPanel.render(appState);
    }

    showBootError(message) {
        this._bootErrorMessage = message;
        this.renderChrome(state);
        this.renderTopologyPanel(state);
    }
}

if (!customElements.get('keel-observability-app')) {
    customElements.define('keel-observability-app', ObservabilityApp);
}
