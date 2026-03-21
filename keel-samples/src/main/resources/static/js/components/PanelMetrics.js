import './shared/KeelHero.js';
import './shared/KeelStatGrid.js';
import './shared/KeelDetailList.js';
import './shared/KeelDataTable.js';

import { KeelElement } from './base/KeelElement.js';
import { detailEntries, sparklinePath, badgeHtml } from '../render.js';
import { formatPercent, formatBytes } from '../utils.js';
import { nodeLabel } from '../formatters.js';

export class PanelMetrics extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <div class="panel-root">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="scroll-panel">
                    <keel-stat-grid data-ref="metricsGrid"></keel-stat-grid>

                    <div class="chart-grid">
                        <div class="card chart-box">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">CPU Utilization</div>
                                    <h3>Kernel Load</h3>
                                </div>
                                <span class="badge neutral mono" data-ref="cpuValueChip">0%</span>
                            </div>
                            <svg class="sparkline" data-ref="cpuSparkline" viewBox="0 0 320 120" preserveAspectRatio="none"></svg>
                            <keel-detail-list data-ref="cpuDetail"></keel-detail-list>
                        </div>

                        <div class="card chart-box">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Heap Pressure</div>
                                    <h3>Memory</h3>
                                </div>
                                <span class="badge neutral mono" data-ref="heapValueChip">0%</span>
                            </div>
                            <svg class="sparkline" data-ref="heapSparkline" viewBox="0 0 320 120" preserveAspectRatio="none"></svg>
                            <keel-detail-list data-ref="heapDetail"></keel-detail-list>
                        </div>

                        <div class="card chart-box">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Latency & Traffic</div>
                                    <h3>Window Summary</h3>
                                </div>
                            </div>
                            <keel-detail-list data-ref="latencyDetail"></keel-detail-list>
                        </div>
                    </div>

                    <div class="layout-grid metrics">
                        <div class="card">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Top Edges</div>
                                    <h3>Recent Traffic</h3>
                                </div>
                            </div>
                            <keel-data-table data-ref="edgeTable"></keel-data-table>
                        </div>

                        <div class="card">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Node & Plugin Inventory</div>
                                    <h3>Runtime Inventory</h3>
                                </div>
                            </div>
                            <div class="table-scroll">
                                <keel-data-table data-ref="nodeTable"></keel-data-table>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    render(appState) {
        this.ensureInitialized();
        const metrics = appState.metrics;
        this.refs.hero.render({
            label: 'System Performance',
            title: 'Metrics & Runtime Health',
            metaHtml: `<span class="status-pill">${metrics ? `${Math.round(metrics.windowMs / 1000)}s window` : 'Recent snapshot'}</span>`
        });

        if (!metrics) {
            this.refs.metricsGrid.render({
                entries: [],
                className: 'metrics-grid'
            });
            this.refs.metricsGrid.shadowRoot.querySelector('[data-ref="grid"]').innerHTML = '<div class="empty">Metrics snapshot not loaded yet.</div>';
            return;
        }

        this.refs.metricsGrid.render({
            entries: [
                ['CPU', formatPercent(metrics.kernel.processCpuLoad), 'kernel process'],
                ['Heap', formatPercent(metrics.kernel.heapUsedPercent), `${formatBytes(metrics.kernel.heapUsedBytes)} used`],
                ['P95 Latency', `${metrics.latency.p95Ms} ms`, `${metrics.latency.completedSpanCount} spans`],
                ['Flow Volume', metrics.traffic.recentFlowCount, `${metrics.traffic.recentTraceCount} traces`]
            ],
            className: 'metrics-grid'
        });
        this.refs.cpuValueChip.textContent = formatPercent(metrics.kernel.processCpuLoad);
        this.refs.heapValueChip.textContent = formatPercent(metrics.kernel.heapUsedPercent);
        this.refs.cpuSparkline.innerHTML = sparklinePath(appState.metricsHistory.map((item) => item.cpu));
        this.refs.heapSparkline.innerHTML = sparklinePath(appState.metricsHistory.map((item) => item.heap), '#c56a1b');
        this.refs.cpuDetail.render({ html: detailEntries([
            ['System Load Avg', metrics.kernel.systemLoadAverage != null ? metrics.kernel.systemLoadAverage.toFixed(2) : 'n/a'],
            ['Processors', metrics.kernel.availableProcessors],
            ['Threads', metrics.kernel.threadCount],
            ['Dropped Logs', metrics.droppedLogCount]
        ]), className: 'detail-list' });
        this.refs.heapDetail.render({ html: detailEntries([
            ['Used', formatBytes(metrics.kernel.heapUsedBytes)],
            ['Max', formatBytes(metrics.kernel.heapMaxBytes)],
            ['Threads', metrics.kernel.threadCount],
            ['Dropped Logs', metrics.droppedLogCount]
        ]), className: 'detail-list' });
        this.refs.latencyDetail.render({ html: detailEntries([
            ['Average', `${metrics.latency.avgMs.toFixed(1)} ms`],
            ['P95', `${metrics.latency.p95Ms} ms`],
            ['P99', `${metrics.latency.p99Ms} ms`],
            ['Error Rate', formatPercent(metrics.latency.errorRate)],
            ['Top Edge', metrics.traffic.topEdges[0] ? `${metrics.traffic.topEdges[0].edgeFrom} -> ${metrics.traffic.topEdges[0].edgeTo}` : 'n/a']
        ]), className: 'detail-list' });
        this.refs.edgeTable.render({
            headers: ['Edge', 'Count', 'Errors'],
            rows: metrics.traffic.topEdges.map((edge) => [
                `${edge.edgeFrom} -> ${edge.edgeTo}`,
                edge.count,
                edge.errorCount
            ]),
            emptyHtml: '<div class="empty">No edge traffic in the current window.</div>'
        });

        this.refs.nodeTable.render({
            headers: ['Node', 'Status', 'Inflight', 'Queue', 'Traces', 'Errors', 'Plugin'],
            rows: (metrics.nodes || []).map((item) => {
                const pluginEntry = item.node.pluginId ? appState.plugins.find((plugin) => plugin.pluginId === item.node.pluginId) : null;
                const pluginStatus = pluginEntry ? (pluginEntry.lifecycleState || '').toUpperCase() : '';
                const isRunning = pluginStatus === 'RUNNING';
                const pluginCtrl = pluginEntry
                    ? (isRunning
                        ? `<button class="action-btn danger" style="padding:4px 8px;font-size:10px;" data-plugin-id="${pluginEntry.pluginId}" data-plugin-action="stop">Stop</button>`
                        : `<button class="action-btn" style="padding:4px 8px;font-size:10px;" data-plugin-id="${pluginEntry.pluginId}" data-plugin-action="start">Start</button>`)
                    : '<span class="muted">—</span>';
                return [
                    nodeLabel(item.node),
                    badgeHtml(item.node.healthState || item.node.lifecycleState),
                    item.node.inflightInvocations || 0,
                    item.node.eventQueueDepth || 0,
                    item.recentTraceCount,
                    item.errorCount,
                    pluginCtrl
                ];
            }),
            emptyHtml: '<div class="empty">No metric node rows available.</div>'
        });
    }
}

if (!customElements.get('keel-panel-metrics')) {
    customElements.define('keel-panel-metrics', PanelMetrics);
}
