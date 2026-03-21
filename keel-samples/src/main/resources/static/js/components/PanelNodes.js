import './shared/KeelHero.js';
import './shared/KeelStatGrid.js';
import './shared/KeelDetailList.js';

import { KeelElement } from './base/KeelElement.js';
import { currentNodeSummary, selectedNode } from '../state.js';
import { detailEntries } from '../render.js';
import { sum, escapeHtml, clamp } from '../utils.js';
import { healthTone, nodeLabel } from '../formatters.js';

export class PanelNodes extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <div class="panel-root">
                <keel-hero data-ref="hero"></keel-hero>
                <keel-stat-grid data-ref="stats"></keel-stat-grid>

                <div class="layout-grid nodes" data-ref="layoutGrid">
                    <div class="card node-directory-card">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Active Inventory</div>
                                <h3>Node Directory</h3>
                            </div>
                        </div>
                        <div class="node-grid scroll-panel" data-ref="nodeGrid"></div>
                    </div>

                    <div class="card" data-ref="detailCard" style="display: none;">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Selected Node</div>
                                <h3 data-ref="detailTitle">Kernel JVM</h3>
                            </div>
                            <span class="badge neutral" data-ref="detailHealth">unknown</span>
                        </div>
                        <keel-detail-list data-ref="detailList"></keel-detail-list>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.nodeGrid.addEventListener('click', (event) => {
            const item = event.target.closest('[data-node-id]');
            if (!item) return;
            this.emit('keel:node-select', { nodeId: item.dataset.nodeId });
        });
    }

    render(appState) {
        this.ensureInitialized();
        const summaries = [...appState.nodeSummaries];
        const selected = selectedNode();
        const selectedSummary = selected ? currentNodeSummary(selected.id) : null;

        this.refs.hero.render({
            label: 'Cluster Management',
            title: 'Cluster Nodes',
            metaHtml: `<span class="status-pill">${summaries.length} nodes</span>`
        });
        this.refs.stats.render({
            entries: [
                ['Active Nodes', summaries.length, `${summaries.filter((item) => item.node.runtimeMode === 'EXTERNAL_JVM').length} external`],
                ['Unhealthy', summaries.filter((item) => healthTone(item.node.healthState) === 'err').length, 'health summary'],
                ['Queued Events', sum(summaries.map((item) => item.node), 'eventQueueDepth'), 'runtime pressure'],
                ['Dropped Logs', sum(summaries.map((item) => item.node), 'droppedLogCount'), 'logger backpressure']
            ]
        });

        this.refs.layoutGrid.classList.toggle('has-selection', Boolean(selected));
        this.refs.detailCard.style.display = selected ? 'flex' : 'none';
        this.refs.nodeGrid.innerHTML = summaries.length ? summaries.map((item) => `
            <div class="card node-summary ${selected && selected.id === item.node.id ? 'is-selected' : ''}" data-node-id="${escapeHtml(item.node.id)}">
                <div class="section-head" style="margin-bottom: 10px;">
                    <div>
                        <div class="section-label">${escapeHtml(item.node.kind || 'node')}</div>
                        <h3 style="font-size: 20px;">${escapeHtml(nodeLabel(item.node))}</h3>
                    </div>
                    <span class="badge ${healthTone(item.node.healthState || item.node.lifecycleState)}">${escapeHtml(item.node.healthState || item.node.lifecycleState || 'UNKNOWN')}</span>
                </div>
                <div class="muted" style="margin-bottom: 12px;">${escapeHtml(item.node.runtimeMode || 'IN_PROCESS')} · ${item.node.pid ? `PID ${escapeHtml(item.node.pid)}` : escapeHtml(item.node.pluginId || item.node.id)}</div>
                <div class="detail-list">
                    <div><div class="section-label">Queue</div><div class="progress"><span style="width:${clamp((item.node.eventQueueDepth || 0) * 10, 0, 100)}%;"></span></div></div>
                    <div><div class="section-label">Inflight</div><div class="progress"><span style="width:${clamp((item.node.inflightInvocations || 0) * 10, 0, 100)}%;"></span></div></div>
                </div>
            </div>
        `).join('') : '<div class="empty">No node summaries available.</div>';

        if (!selected) return;
        this.refs.detailTitle.textContent = nodeLabel(selected);
        this.refs.detailHealth.className = `badge ${healthTone(selected.healthState || selected.lifecycleState)}`;
        this.refs.detailHealth.textContent = selected.healthState || selected.lifecycleState || 'UNKNOWN';
        this.refs.detailList.render({
            html: detailEntries([
                ['Node ID', selected.id],
                ['Plugin', selected.pluginId || 'kernel'],
                ['Runtime', selected.runtimeMode || 'IN_PROCESS'],
                ['Lifecycle', selected.lifecycleState || 'RUNNING'],
                ['Inflight', selected.inflightInvocations || 0],
                ['Queue', selected.eventQueueDepth || 0],
                ['Dropped Logs', selected.droppedLogCount || 0],
                ['Recent Flows', selectedSummary ? selectedSummary.recentFlowCount : 0],
                ['Recent Traces', selectedSummary ? selectedSummary.recentTraceCount : 0],
                ['Error Count', selectedSummary ? selectedSummary.errorCount : 0]
            ], selected.labels),
            className: 'detail-list scroll-panel'
        });
    }
}

if (!customElements.get('keel-panel-nodes')) {
    customElements.define('keel-panel-nodes', PanelNodes);
}
