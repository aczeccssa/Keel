import './TopologyCanvas.js';

import { KeelElement } from './base/KeelElement.js';
import { currentNodeSummary, selectedNode } from '../state.js';
import { statCards } from '../render.js';
import { sum, escapeHtml, formatTime } from '../utils.js';
import { healthTone, nodeLabel } from '../formatters.js';

function statusText(value, fallback = 'UNKNOWN') {
    return escapeHtml(String(value || fallback));
}

export class PanelTopology extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <style>
                .topology-page {
                    display: flex;
                    flex-direction: column;
                    height: 100%;
                    min-height: 0;
                    overflow-y: auto;
                    padding: 32px 48px 40px;
                    background: transparent;
                }
                .topology-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: flex-end;
                    gap: 24px;
                    margin-bottom: 28px;
                }
                .topology-kicker {
                    margin: 0 0 12px;
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.16em;
                    text-transform: uppercase;
                }
                .topology-title {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 40px;
                    line-height: 0.96;
                    letter-spacing: -0.04em;
                    color: var(--ink);
                    font-weight: 500;
                }
                .topology-subtitle {
                    margin: 14px 0 0;
                    max-width: 720px;
                    color: var(--muted);
                    font-size: 14px;
                    line-height: 1.65;
                }
                .topology-meta {
                    display: flex;
                    flex-wrap: wrap;
                    justify-content: flex-end;
                    gap: 10px;
                    flex-shrink: 0;
                }
                .topology-pill {
                    display: inline-flex;
                    align-items: center;
                    gap: 10px;
                    padding: 11px 16px;
                    border-radius: 999px;
                    background: var(--panel-strong);
                    border: 1px solid var(--line);
                    box-shadow: var(--shadow-sm);
                    color: var(--ink);
                    font-size: 11px;
                    font-weight: 800;
                    letter-spacing: 0.12em;
                    text-transform: uppercase;
                    white-space: nowrap;
                }
                .topology-pill-dot {
                    width: 8px;
                    height: 8px;
                    border-radius: 999px;
                    background: var(--green);
                    box-shadow: 0 0 0 4px rgba(19, 130, 79, 0.12);
                    flex-shrink: 0;
                }
                .topology-boot {
                    padding: 28px 30px;
                    border-radius: var(--radius-xl);
                    border: 1px solid rgba(198, 40, 40, 0.16);
                    background: linear-gradient(180deg, rgba(255, 255, 255, 0.95), rgba(255, 218, 214, 0.44));
                    box-shadow: var(--shadow-md);
                }
                .topology-boot pre {
                    margin: 18px 0 0;
                    padding: 18px 20px;
                    border-radius: var(--radius-md);
                    background: #1f2937;
                    color: #f9fafb;
                    font-size: 12px;
                    line-height: 1.7;
                    overflow: auto;
                    white-space: pre-wrap;
                    word-break: break-word;
                }
                .topology-layout {
                    display: grid;
                    grid-template-columns: minmax(0, 1.12fr) minmax(320px, 0.74fr);
                    gap: 24px;
                    flex: 1 1 auto;
                    min-height: 0;
                }
                .topology-stage-card,
                .topology-panel {
                    background: var(--panel-strong);
                    border: 1px solid var(--line);
                    border-radius: var(--radius-xl);
                    box-shadow: var(--shadow-md);
                    min-width: 0;
                }
                .topology-stage-card {
                    display: flex;
                    flex-direction: column;
                    min-height: 620px;
                    overflow: visible;
                    padding: 0;
                    background: transparent;
                    border: 0;
                    border-radius: 0;
                    box-shadow: none;
                }
                .topology-stage-card keel-topology-canvas {
                    display: block;
                    flex: 1 1 auto;
                    min-height: 0;
                }
                .topology-sidebar {
                    display: flex;
                    flex-direction: column;
                    gap: 18px;
                    min-height: 0;
                }
                .topology-panel {
                    display: flex;
                    flex-direction: column;
                    min-height: 0;
                    overflow: hidden;
                }
                .topology-panel.is-summary {
                    padding: 18px;
                }
                .topology-stats {
                    display: grid;
                    grid-template-columns: repeat(2, minmax(0, 1fr));
                    gap: 12px;
                }
                .topology-stats .stat-card {
                    padding: 18px;
                    border-radius: 18px;
                    background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(246, 243, 236, 0.78));
                    border: 1px solid rgba(17, 24, 39, 0.06);
                    box-shadow: none;
                }
                .topology-stats .stat-card .label {
                    color: var(--muted);
                }
                .topology-stats .stat-card .value {
                    margin-top: 10px;
                    font-size: 30px;
                }
                .topology-stats .stat-card .hint {
                    margin-top: 8px;
                    font-size: 11px;
                }
                .topology-panel-head {
                    display: flex;
                    align-items: flex-start;
                    justify-content: space-between;
                    gap: 12px;
                    padding: 22px 24px 0;
                    min-width: 0;
                    flex-shrink: 0;
                }
                .topology-panel-copy {
                    min-width: 0;
                    flex: 1;
                }
                .topology-panel-label {
                    display: block;
                    margin-bottom: 10px;
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.16em;
                    text-transform: uppercase;
                }
                .topology-panel-title {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 24px;
                    line-height: 1.05;
                    letter-spacing: -0.03em;
                    color: var(--ink);
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .topology-chip {
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    min-height: 34px;
                    padding: 8px 12px;
                    border-radius: 999px;
                    border: 1px solid var(--line);
                    background: rgba(255, 255, 255, 0.9);
                    color: var(--ink);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.12em;
                    text-transform: uppercase;
                    white-space: nowrap;
                }
                .topology-chip.ok {
                    background: var(--green-soft);
                    color: var(--green);
                    border-color: rgba(19, 130, 79, 0.18);
                }
                .topology-chip.warn {
                    background: var(--amber-soft);
                    color: var(--amber);
                    border-color: rgba(197, 106, 27, 0.18);
                }
                .topology-chip.err {
                    background: var(--red-soft);
                    color: var(--red);
                    border-color: rgba(198, 40, 40, 0.18);
                }
                .topology-chip.neutral {
                    background: rgba(102, 112, 133, 0.1);
                    color: var(--muted);
                    border-color: rgba(102, 112, 133, 0.14);
                }
                .topology-body {
                    flex: 1 1 auto;
                    min-height: 0;
                    padding: 18px 24px 24px;
                }
                .topology-scroll {
                    height: 100%;
                    overflow-y: auto;
                    padding-right: 6px;
                }
                .topology-scroll::-webkit-scrollbar {
                    width: 8px;
                }
                .topology-scroll::-webkit-scrollbar-thumb {
                    border-radius: 999px;
                    background: rgba(102, 112, 133, 0.28);
                }
                .topology-flow-list {
                    display: grid;
                    gap: 12px;
                }
                .topology-flow-item {
                    padding: 16px 18px;
                    border-radius: 18px;
                    background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(246, 243, 236, 0.68));
                    border: 1px solid rgba(17, 24, 39, 0.06);
                }
                .topology-flow-title {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 12px;
                    margin-bottom: 8px;
                    min-width: 0;
                }
                .topology-flow-title strong {
                    min-width: 0;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                    font-size: 14px;
                    color: var(--ink);
                }
                .topology-flow-subtitle {
                    color: var(--muted);
                    font-size: 12px;
                    line-height: 1.5;
                    word-break: break-word;
                }
                .topology-empty {
                    display: grid;
                    place-items: center;
                    min-height: 180px;
                    padding: 24px;
                    border-radius: 18px;
                    border: 1px dashed var(--line-strong);
                    background: rgba(255, 255, 255, 0.56);
                    color: var(--muted);
                    text-align: center;
                    font-size: 13px;
                    line-height: 1.6;
                }
                .topo-node-meta-row {
                    display: flex;
                    gap: 8px;
                    flex-wrap: wrap;
                    margin-bottom: 16px;
                }
                .topo-node-metrics {
                    display: grid;
                    grid-template-columns: repeat(3, minmax(0, 1fr));
                    gap: 10px;
                    margin-bottom: 18px;
                }
                .topo-metric-card {
                    background: rgba(17, 24, 39, 0.04);
                    border: 1px solid rgba(17, 24, 39, 0.06);
                    border-radius: 16px;
                    padding: 14px 12px;
                    text-align: center;
                }
                .topo-metric-label {
                    margin-bottom: 6px;
                    color: var(--muted);
                    font-size: 9px;
                    font-weight: 800;
                    letter-spacing: 0.12em;
                    text-transform: uppercase;
                }
                .topo-metric-value {
                    font-family: var(--font-headline);
                    font-size: 24px;
                    line-height: 1;
                    font-weight: 700;
                    color: var(--ink);
                }
                .topo-metric-value.warn {
                    color: var(--amber);
                }
                .topo-metric-value.err {
                    color: var(--red);
                }
                .topo-metric-value.ok {
                    color: var(--green);
                }
                .topo-node-kv {
                    display: flex;
                    flex-direction: column;
                    border-top: 1px solid rgba(17, 24, 39, 0.08);
                }
                .topo-kv-row {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    padding: 11px 0;
                    border-bottom: 1px solid rgba(17, 24, 39, 0.06);
                }
                .topo-kv-label {
                    color: var(--muted);
                    font-size: 11px;
                    font-weight: 700;
                    letter-spacing: 0.04em;
                }
                .topo-kv-val {
                    color: var(--ink);
                    font-family: var(--font-headline);
                    font-size: 14px;
                    font-weight: 700;
                    text-align: right;
                    word-break: break-word;
                }
                .topo-kv-val.muted {
                    color: var(--muted);
                    font-family: var(--font-mono);
                    font-size: 11px;
                    font-weight: 500;
                }
                .topo-kv-val.err {
                    color: var(--red);
                }
                .topo-kv-val.ok {
                    color: var(--green);
                }
                .badge {
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    padding: 7px 10px;
                    border-radius: 999px;
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.1em;
                    text-transform: uppercase;
                    border: 1px solid transparent;
                }
                .badge.ok {
                    background: var(--green-soft);
                    color: var(--green);
                    border-color: rgba(19, 130, 79, 0.18);
                }
                .badge.warn {
                    background: var(--amber-soft);
                    color: var(--amber);
                    border-color: rgba(197, 106, 27, 0.18);
                }
                .badge.err {
                    background: var(--red-soft);
                    color: var(--red);
                    border-color: rgba(198, 40, 40, 0.18);
                }
                .badge.neutral {
                    background: rgba(102, 112, 133, 0.1);
                    color: var(--muted);
                    border-color: rgba(102, 112, 133, 0.14);
                }
                .mono {
                    font-family: var(--font-mono);
                }
                @media (max-width: 1180px) {
                    .topology-layout {
                        grid-template-columns: 1fr;
                    }
                    .topology-stage-card {
                        min-height: 520px;
                    }
                }
                @media (max-width: 720px) {
                    .topology-page {
                        padding: 24px 20px 28px;
                    }
                    .topology-header {
                        flex-direction: column;
                        align-items: stretch;
                    }
                    .topology-meta {
                        justify-content: flex-start;
                    }
                    .topology-stats,
                    .topo-node-metrics {
                        grid-template-columns: 1fr;
                    }
                    .topology-stage-card {
                        min-height: 440px;
                        padding: 10px;
                    }
                    .topology-panel-head,
                    .topology-body {
                        padding-left: 18px;
                        padding-right: 18px;
                    }
                    .topology-flow-title,
                    .topo-kv-row {
                        align-items: flex-start;
                        flex-direction: column;
                    }
                    .topology-panel-title {
                        white-space: normal;
                    }
                }
            </style>

            <div class="topology-page">
                <header class="topology-header">
                    <div class="topology-copy">
                        <p class="topology-kicker">System Observer</p>
                        <h1 class="topology-title">Topology & Live Flow</h1>
                        <p class="topology-subtitle">Live node topology, flow activity, and selected runtime health in one operational surface.</p>
                    </div>
                    <div class="topology-meta" data-ref="heroMeta"></div>
                </header>

                <div data-ref="errorWrap" style="display:none;"></div>

                <div class="topology-layout" data-ref="layout">
                    <section class="topology-stage-card">
                        <keel-topology-canvas data-ref="canvas"></keel-topology-canvas>
                    </section>

                    <aside class="topology-sidebar">
                        <section class="topology-panel is-summary">
                            <div class="topology-stats" data-ref="stats"></div>
                        </section>

                        <section class="topology-panel" data-ref="nodeCard" style="display:none;">
                            <div class="topology-panel-head">
                                <div class="topology-panel-copy">
                                    <span class="topology-panel-label">Selected Node</span>
                                    <h3 class="topology-panel-title" data-ref="nodeTitle">Kernel JVM</h3>
                                </div>
                                <span class="topology-chip neutral" data-ref="nodeHealth">unknown</span>
                            </div>
                            <div class="topology-body">
                                <div class="topology-scroll" data-ref="nodeDetail"></div>
                            </div>
                        </section>

                        <section class="topology-panel" data-ref="edgeCard">
                            <div class="topology-panel-head">
                                <div class="topology-panel-copy">
                                    <span class="topology-panel-label">Recent Packets</span>
                                    <h3 class="topology-panel-title">Edge Activity</h3>
                                </div>
                                <span class="topology-chip neutral mono" data-ref="flowCountChip">0 flows</span>
                            </div>
                            <div class="topology-body">
                                <div class="topology-scroll" data-ref="flowList"></div>
                            </div>
                        </section>
                    </aside>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.flowList.innerHTML = '';
        this.refs.nodeDetail.innerHTML = '';
    }

    render(appState, { bootError = '' } = {}) {
        this.ensureInitialized();
        this.refs.heroMeta.innerHTML = `
            <span class="topology-pill"><span class="topology-pill-dot"></span><span>${appState.topology.length} active nodes</span></span>
            <span class="topology-pill">${appState.panels.length} panels</span>
        `;

        if (bootError) {
            this.refs.layout.style.display = 'none';
            this.refs.errorWrap.style.display = 'block';
            this.refs.errorWrap.innerHTML = `
                <section class="topology-boot">
                    <span class="topology-panel-label">Boot Failure</span>
                    <h3 class="topology-panel-title">Observability UI could not load</h3>
                    <pre>${escapeHtml(bootError)}</pre>
                </section>
            `;
            return;
        }

        this.refs.layout.style.display = 'grid';
        this.refs.errorWrap.style.display = 'none';
        this.refs.errorWrap.innerHTML = '';

        this.refs.stats.innerHTML = statCards([
            ['Active Nodes', appState.topology.length, `${appState.nodeSummaries.filter((item) => healthTone(item.node.healthState) === 'err').length} unhealthy`],
            ['Recent Traces', appState.traces.length, `${appState.flows.length} flows`],
            ['Inflight', sum(appState.topology, 'inflightInvocations'), `${sum(appState.topology, 'eventQueueDepth')} queued`],
            ['Dropped Logs', sum(appState.topology, 'droppedLogCount'), `${appState.panels.length} panels`]
        ]);
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
            this.refs.nodeHealth.className = `topology-chip ${healthTone(node.healthState || node.lifecycleState)}`;
            this.refs.nodeHealth.textContent = node.healthState || node.lifecycleState || 'UNKNOWN';
            this.refs.nodeDetail.innerHTML = `
                <div class="topo-node-meta-row">
                    <span class="badge ${healthTone(node.lifecycleState)}">${statusText(node.lifecycleState, 'RUNNING')}</span>
                    <span class="badge ${healthTone(node.healthState)}">${statusText(node.healthState)}</span>
                    <span class="badge neutral">${statusText(node.runtimeMode, 'IN_PROCESS')}</span>
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
            `;
            return;
        }

        const recentFlows = [...appState.flows].slice(-15).reverse();
        this.refs.flowCountChip.textContent = `${appState.flows.length} flows`;
        this.refs.flowList.innerHTML = recentFlows.length ? `
            <div class="topology-flow-list">
                ${recentFlows.map((flow) => `
                    <article class="topology-flow-item">
                        <div class="topology-flow-title">
                            <strong>${escapeHtml(flow.edgeFrom)} -> ${escapeHtml(flow.edgeTo)}</strong>
                            <span class="badge ${healthTone(flow.status)}">${escapeHtml(flow.status || 'OK')}</span>
                        </div>
                        <div class="topology-flow-subtitle mono">${escapeHtml(flow.operation || 'rpc.invoke')} · ${formatTime(flow.startEpochMs)} · ${escapeHtml(flow.traceId || 'trace')}</div>
                    </article>
                `).join('')}
            </div>
        ` : '<div class="topology-empty">No recent flows captured yet.</div>';
    }
}

if (!customElements.get('keel-panel-topology')) {
    customElements.define('keel-panel-topology', PanelTopology);
}
