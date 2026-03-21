import './shared/KeelHero.js';
import './shared/KeelStatGrid.js';
import './shared/KeelDetailList.js';
import './TopologyCanvas.js';

import { KeelElement } from './base/KeelElement.js';
import { currentNodeSummary, selectedNode } from '../state.js';
import { sum, escapeHtml, formatTime } from '../utils.js';
import { healthTone, nodeLabel } from '../formatters.js';

export class PanelTopology extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <div class="panel-root" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>
                <div data-ref="errorWrap" class="hidden"></div>
                <div class="layout-grid topology" data-ref="layout">
                    <keel-topology-canvas data-ref="canvas"></keel-topology-canvas>

                    <div class="stack">
                        <div class="card" style="padding: 18px;">
                            <keel-stat-grid data-ref="stats"></keel-stat-grid>
                        </div>

                        <div class="card" data-ref="nodeCard" style="display: none; flex: 1; min-height: 0;">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Selected Node</div>
                                    <h3 data-ref="nodeTitle">Kernel JVM</h3>
                                </div>
                                <span class="badge neutral" data-ref="nodeHealth">unknown</span>
                            </div>
                            <keel-detail-list data-ref="nodeDetail"></keel-detail-list>
                        </div>

                        <div class="card" data-ref="edgeCard" style="flex: 1; min-height: 0;">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Recent Packets</div>
                                    <h3>Edge Activity</h3>
                                </div>
                                <span class="badge neutral mono" data-ref="flowCountChip">0 flows</span>
                            </div>
                            <keel-detail-list data-ref="flowList"></keel-detail-list>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.flowList.render({ html: '', className: 'list scroll-panel' });
        this.refs.nodeDetail.render({ html: '', className: 'detail-list scroll-panel' });
    }

    render(appState, { bootError = '' } = {}) {
        this.ensureInitialized();
        this.refs.hero.render({
            label: 'System Observer',
            title: 'Topology & Live Flow',
            metaHtml: `
                <span class="status-pill"><span class="status-dot"></span><span>${appState.topology.length} active nodes</span></span>
                <span class="status-pill">${appState.panels.length} panels</span>
            `
        });

        if (bootError) {
            this.refs.layout.classList.add('hidden');
            this.refs.errorWrap.classList.remove('hidden');
            this.refs.errorWrap.innerHTML = `
                <div class="card">
                    <div class="section-head">
                        <div>
                            <div class="section-label">Boot Failure</div>
                            <h3>Observability UI could not load</h3>
                        </div>
                    </div>
                    <div class="code-block mono">${escapeHtml(bootError)}</div>
                </div>
            `;
            return;
        }

        this.refs.layout.classList.remove('hidden');
        this.refs.errorWrap.classList.add('hidden');
        this.refs.errorWrap.innerHTML = '';

        this.refs.stats.render({
            entries: [
                ['Active Nodes', appState.topology.length, `${appState.nodeSummaries.filter((item) => healthTone(item.node.healthState) === 'err').length} unhealthy`],
                ['Recent Traces', appState.traces.length, `${appState.flows.length} flows`],
                ['Inflight', sum(appState.topology, 'inflightInvocations'), `${sum(appState.topology, 'eventQueueDepth')} queued`],
                ['Dropped Logs', sum(appState.topology, 'droppedLogCount'), `${appState.panels.length} panels`]
            ],
            className: 'grid-2x2'
        });
        this.refs.canvas.render(appState);

        const node = selectedNode();
        const summary = node ? currentNodeSummary(node.id) : null;
        this.refs.nodeCard.style.display = node ? 'flex' : 'none';
        this.refs.edgeCard.style.display = node ? 'none' : 'flex';

        if (node) {
            const inflight = node.inflightInvocations || 0;
            const queue = node.eventQueueDepth || 0;
            const dropped = node.droppedLogCount || 0;
            this.refs.nodeTitle.textContent = nodeLabel(node);
            this.refs.nodeHealth.className = `badge ${healthTone(node.healthState || node.lifecycleState)}`;
            this.refs.nodeHealth.textContent = node.healthState || node.lifecycleState || 'UNKNOWN';
            this.refs.nodeDetail.render({
                html: `
                    <div class="topo-node-meta-row">
                        <span class="badge ${healthTone(node.lifecycleState)}">${node.lifecycleState || 'RUNNING'}</span>
                        <span class="badge ${healthTone(node.healthState)}">${node.healthState || 'UNKNOWN'}</span>
                        <span class="badge neutral">${node.runtimeMode || 'IN_PROCESS'}</span>
                    </div>
                    <div class="topo-node-metrics">
                        <div class="topo-metric-card">
                            <div class="topo-metric-label">Inflight</div>
                            <div class="topo-metric-value ${inflight > 5 ? 'warn' : ''}">${inflight}</div>
                        </div>
                        <div class="topo-metric-card">
                            <div class="topo-metric-label">Queue</div>
                            <div class="topo-metric-value ${queue > 10 ? 'err' : ''}">${queue}</div>
                        </div>
                        <div class="topo-metric-card">
                            <div class="topo-metric-label">Dropped</div>
                            <div class="topo-metric-value ${dropped > 0 ? 'err' : 'ok'}">${dropped}</div>
                        </div>
                    </div>
                    <div class="topo-node-kv">
                        <div class="topo-kv-row"><span class="topo-kv-label">Recent Flows</span><span class="topo-kv-val">${summary ? summary.recentFlowCount : 0}</span></div>
                        <div class="topo-kv-row"><span class="topo-kv-label">Recent Traces</span><span class="topo-kv-val">${summary ? summary.recentTraceCount : 0}</span></div>
                        <div class="topo-kv-row"><span class="topo-kv-label">Errors</span><span class="topo-kv-val ${(summary ? summary.errorCount : 0) > 0 ? 'err' : 'ok'}">${summary ? summary.errorCount : 0}</span></div>
                        <div class="topo-kv-row"><span class="topo-kv-label">PID</span><span class="topo-kv-val muted">${escapeHtml(node.pid || 'n/a')}</span></div>
                    </div>
                `,
                className: 'detail-list scroll-panel'
            });
            return;
        }

        const recentFlows = [...appState.flows].slice(-15).reverse();
        this.refs.flowCountChip.textContent = `${appState.flows.length} flows`;
        this.refs.flowList.render({
            html: recentFlows.length ? recentFlows.map((flow) => `
                <div class="list-item">
                    <div class="row-title">
                        <strong>${escapeHtml(flow.edgeFrom)} -> ${escapeHtml(flow.edgeTo)}</strong>
                        <span class="badge ${healthTone(flow.status)}">${escapeHtml(flow.status || 'OK')}</span>
                    </div>
                    <div class="row-subtitle mono">${escapeHtml(flow.operation || 'rpc.invoke')} · ${formatTime(flow.startEpochMs)} · ${escapeHtml(flow.traceId || 'trace')}</div>
                </div>
            `).join('') : '<div class="empty">No recent flows captured yet.</div>',
            className: 'list scroll-panel'
        });
    }
}

if (!customElements.get('keel-panel-topology')) {
    customElements.define('keel-panel-topology', PanelTopology);
}
