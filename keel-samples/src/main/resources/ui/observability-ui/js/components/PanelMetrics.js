import { KeelElement } from './base/KeelElement.js';
import { formatPercent, formatBytes } from '../utils.js';
import { nodeLabel } from '../formatters.js';

export class PanelMetrics extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <style>
                .panel-root {
                    display: flex;
                    flex-direction: column;
                    height: 100%;
                    overflow-y: auto;
                    padding: 32px 48px;
                    background: transparent;
                }
                .metrics-header {
                    margin-bottom: 32px;
                    display: flex;
                    justify-content: space-between;
                    align-items: flex-end;
                }
                .metrics-header h1 {
                    font-family: "Newsreader", serif;
                    font-size: 36px;
                    margin: 8px 0;
                    color: var(--ink);
                    font-weight: 400;
                }
                .metrics-header p {
                    color: var(--muted);
                    margin: 0;
                    max-width: 600px;
                    line-height: 1.5;
                    font-size: 14px;
                }
                .breadcrumbs {
                    display: flex;
                    gap: 8px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.1em;
                    color: var(--muted);
                    margin-bottom: 16px;
                }
                .breadcrumbs .current { color: var(--ink); }
                
                .bento-grid {
                    display: grid;
                    grid-template-columns: repeat(12, 1fr);
                    gap: 24px;
                    margin-bottom: 48px;
                }

                .bento-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 32px;
                    box-shadow: var(--shadow-sm);
                    border: 1px solid var(--line);
                    display: flex;
                    flex-direction: column;
                }
                .bento-card.col-8 { grid-column: span 8; }
                .bento-card.col-4 { grid-column: span 4; }
                .bento-card.col-5 { grid-column: span 5; }
                .bento-card.col-7 { grid-column: span 7; }

                .card-title {
                    font-family: "Newsreader", serif;
                    font-size: 20px;
                    color: var(--ink);
                    margin: 0 0 4px 0;
                }

                .card-subtitle {
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.1em;
                    color: var(--muted);
                    margin: 0 0 24px 0;
                }

                /* CPU Card */
                .cpu-header-row {
                    display: flex;
                    justify-content: space-between;
                    align-items: flex-start;
                    margin-bottom: 16px;
                }
                .cpu-value-wrap { text-align: right; }
                .cpu-value { font-size: 36px; font-weight: 300; color: var(--ink); }
                .cpu-value span { font-size: 16px; margin-left: 4px; }
                .cpu-status { font-size: 10px; font-weight: 700; text-transform: uppercase; color: var(--teal); }
                .cpu-chart-container {
                    position: relative;
                    flex-grow: 1;
                    min-height: 200px;
                    margin-top: 16px;
                }
                .cpu-chart-container svg {
                    width: 100%;
                    height: 100%;
                    overflow: visible;
                }

                /* Memory Card */
                .memory-center {
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    gap: 32px;
                    flex-grow: 1;
                }
                .memory-svg-wrap {
                    position: relative;
                    width: 160px;
                    height: 160px;
                }
                .memory-svg-wrap svg {
                    transform: rotate(-90deg);
                    width: 100%;
                    height: 100%;
                }
                .memory-text {
                    position: absolute;
                    inset: 0;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                }
                .memory-val { font-size: 28px; font-weight: 300; color: var(--ink); }
                .memory-val span { font-size: 12px; }
                .memory-label { font-size: 10px; font-weight: 700; text-transform: uppercase; color: var(--muted); }
                .memory-stats { width: 100%; display: flex; flex-direction: column; gap: 16px; }
                .memory-stat-row { display: flex; justify-content: space-between; align-items: center; }
                .memory-stat-label { font-size: 10px; font-weight: 700; text-transform: uppercase; color: var(--muted); }
                .memory-stat-val { font-size: 14px; color: var(--ink); }

                /* Latency Card */
                .latency-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
                .latency-box { padding: 16px; background: var(--bg); border-radius: var(--radius-md); border: 1px solid var(--line); }
                .latency-box-title { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: var(--muted); }
                .latency-box-val { font-size: 36px; font-weight: 300; color: var(--ink); margin-top: 8px; }
                .latency-box-val span { font-size: 14px; margin-left: 4px; }
                .latency-trend { margin-top: 16px; display: flex; align-items: center; gap: 4px; font-size: 10px; font-weight: 700; }
                .trend-down { color: var(--teal); }
                .trend-up { color: var(--amber); }

                .latency-dist { margin-top: 32px; display: flex; flex-direction: column; gap: 16px; }
                .dist-header { display: flex; justify-content: space-between; align-items: flex-end; }
                .dist-title { font-size: 10px; font-weight: 700; text-transform: uppercase; color: var(--muted); }
                .dist-avg { font-size: 10px; color: var(--ink); }
                .dist-bars { display: flex; align-items: flex-end; gap: 4px; height: 48px; }
                .dist-bar { flex: 1; background: var(--line-strong); border-radius: 2px 2px 0 0; }
                .dist-bar.active { background: var(--ink); }

                /* Traffic Card */
                .traffic-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 48px; }
                .traffic-col .label-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
                .traffic-col .label { font-size: 10px; font-weight: 700; text-transform: uppercase; color: var(--muted); }
                .traffic-col .val { font-size: 14px; font-weight: 500; color: var(--ink); }
                .traffic-chart { height: 96px; display: flex; align-items: center; }
                
                .traffic-footer { margin-top: 40px; padding-top: 32px; border-top: 1px solid var(--line); display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
                .tf-stat { text-align: center; }
                .tf-stat-label { font-size: 9px; font-weight: 700; text-transform: uppercase; color: var(--muted); margin-bottom: 4px; }
                .tf-stat-val { font-size: 14px; color: var(--ink); }

                /* Node Table */
                .node-section { border-radius: var(--radius-lg); border: 1px solid var(--line); }
                .node-section-header { padding: 24px 32px; border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; align-items: center; background: var(--bg); }
                .node-section-title { font-family: "Newsreader", serif; font-size: 20px; color: var(--ink); margin: 0; font-weight: 500; }
                .node-table-wrap { width: 100%; overflow-x: auto; }
                .node-table { width: 100%; border-collapse: collapse; text-align: left; }
                .node-table th { padding: 12px 32px; font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: var(--muted); background: var(--bg); border-bottom: 1px solid var(--line); }
                .node-table th.right { text-align: right; }
                .node-table td { padding: 16px 32px; font-size: 13px; border-bottom: 1px solid var(--line); color: var(--ink); vertical-align: middle; }
                .node-table td.right { text-align: right; font-weight: 500; font-family: ui-monospace, monospace; font-size: 12px; }
                .node-table tr:last-child td { border-bottom: none; }
                .node-table tr:hover { background: var(--line); }
                
                .node-identity { display: flex; align-items: center; gap: 16px; }
                .node-icon-box { width: 32px; height: 32px; border-radius: 8px; background: var(--line); display: flex; align-items: center; justify-content: center; color: var(--muted); }
                .node-name { font-weight: 600; font-size: 14px; color: var(--ink); }
                .node-sub { font-size: 10px; color: var(--muted); font-weight: 500; margin-top: 2px; }

                .status-badge {
                    padding: 4px 10px;
                    border-radius: 6px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    color: white;
                    display: inline-block;
                    letter-spacing: 0.05em;
                    line-height: 1;
                }
                .status-badge.ok { background: var(--green); }
                .status-badge.warn { background: var(--amber); }
                .status-badge.err { background: var(--red); }
                .status-badge.neutral { background: var(--muted); }

                .plugin-btn {
                    padding: 6px 14px;
                    border: 1px solid var(--line-strong);
                    border-radius: 6px;
                    background: var(--paper);
                    color: var(--ink);
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    cursor: pointer;
                    transition: all 0.2s;
                    letter-spacing: 0.05em;
                }
                .plugin-btn:hover { background: var(--line); border-color: var(--muted); }
                .plugin-btn.danger { color: var(--red); }
                .plugin-btn.danger:hover { background: var(--red-soft); border-color: var(--red); }

                /* Media Queries */
                @media (max-width: 1024px) {
                    .bento-card.col-8, .bento-card.col-4, .bento-card.col-5, .bento-card.col-7 { grid-column: span 12; }
                }
            </style>
            
            <div class="panel-root">
                <header class="metrics-header">
                    <div>
                        <div class="breadcrumbs">
                            <span>Clusters</span>
                            <span>/</span>
                            <span class="current">Production-West-01</span>
                        </div>
                        <h1>Metrics & System Health</h1>
                        <p>Real-time performance telemetry across the distributed node architecture. <span data-ref="windowMsLabel"></span>.</p>
                    </div>
                </header>

                <div class="bento-grid">
                    <!-- CPU Utilization -->
                    <div class="bento-card col-8">
                        <div class="cpu-header-row">
                            <div>
                                <h2 class="card-title">CPU Utilization</h2>
                                <p class="card-subtitle">Core Load Distribution</p>
                            </div>
                            <div class="cpu-value-wrap">
                                <div class="cpu-value" data-ref="cpuValueLabel">0<span>%</span></div>
                                <div class="cpu-status">Normal Operating Range</div>
                            </div>
                        </div>
                        <div class="cpu-chart-container">
                            <svg data-ref="cpuLineSvg" viewBox="0 0 800 200" preserveAspectRatio="none">
                                <!-- Grid Lines -->
                                <line x1="0" x2="800" y1="50" y2="50" stroke="var(--line)" stroke-width="1" stroke-dasharray="4 4" />
                                <line x1="0" x2="800" y1="100" y2="100" stroke="var(--line)" stroke-width="1" stroke-dasharray="4 4" />
                                <line x1="0" x2="800" y1="150" y2="150" stroke="var(--line)" stroke-width="1" stroke-dasharray="4 4" />
                                
                                <path data-ref="cpuPathLine" d="" fill="none" stroke="var(--ink)" stroke-width="2" />
                                <path data-ref="cpuPathFill" d="" fill="var(--ink)" opacity="0.05" />
                            </svg>
                        </div>
                    </div>

                    <!-- Memory Pressure -->
                    <div class="bento-card col-4">
                        <h2 class="card-title">Memory Pressure</h2>
                        <p class="card-subtitle" style="margin-bottom: 48px;">Heap Allocation</p>
                        
                        <div class="memory-center">
                            <div class="memory-svg-wrap">
                                <svg viewBox="0 0 160 160">
                                    <circle cx="80" cy="80" r="70" fill="transparent" stroke="var(--line)" stroke-width="8" />
                                    <circle data-ref="memoryCircle" cx="80" cy="80" r="70" fill="transparent" stroke="var(--ink)" stroke-width="8" stroke-dasharray="440" stroke-dashoffset="440" style="transition: stroke-dashoffset 0.5s ease;" />
                                </svg>
                                <div class="memory-text">
                                    <div class="memory-val" data-ref="memoryPercentLabel">0<span>%</span></div>
                                    <div class="memory-label">Used</div>
                                </div>
                            </div>
                            
                            <div class="memory-stats">
                                <div class="memory-stat-row">
                                    <span class="memory-stat-label">Allocated</span>
                                    <span class="memory-stat-val" data-ref="memAllocatedLabel">0 GB</span>
                                </div>
                                <div class="memory-stat-row">
                                    <span class="memory-stat-label">Available</span>
                                    <span class="memory-stat-val" data-ref="memAvailableLabel">0 GB</span>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Request Latency -->
                    <div class="bento-card col-5">
                        <h2 class="card-title">Request Latency</h2>
                        <p class="card-subtitle" style="margin-bottom: 32px;">P95 / P99 Percentiles</p>
                        
                        <div class="latency-grid">
                            <div class="latency-box">
                                <div class="latency-box-title">P95</div>
                                <div class="latency-box-val" data-ref="latencyP95Label">0<span>ms</span></div>
                                <div class="latency-trend trend-down">
                                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><polyline points="19 12 12 19 5 12"></polyline></svg>
                                    -12ms
                                </div>
                            </div>
                            <div class="latency-box">
                                <div class="latency-box-title">P99</div>
                                <div class="latency-box-val" data-ref="latencyP99Label">0<span>ms</span></div>
                                <div class="latency-trend trend-up">
                                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"></line><polyline points="5 12 12 5 19 12"></polyline></svg>
                                    +45ms
                                </div>
                            </div>
                        </div>

                        <div class="latency-dist">
                            <div class="dist-header">
                                <span class="dist-title">Distribution Curve</span>
                                <span class="dist-avg" data-ref="latencyAvgLabel">avg 0ms</span>
                            </div>
                            <div class="dist-bars">
                                <div class="dist-bar" style="height: 20%;"></div>
                                <div class="dist-bar" style="height: 40%;"></div>
                                <div class="dist-bar active" style="height: 90%;"></div>
                                <div class="dist-bar active" style="height: 70%;"></div>
                                <div class="dist-bar active" style="height: 30%;"></div>
                                <div class="dist-bar" style="height: 15%;"></div>
                                <div class="dist-bar" style="height: 5%;"></div>
                            </div>
                        </div>
                    </div>

                    <!-- Traffic Flow (Adapted) -->
                    <div class="bento-card col-7">
                        <h2 class="card-title">Traffic Flow</h2>
                        <p class="card-subtitle" style="margin-bottom: 32px;">Throughput Velocity</p>
                        
                        <div class="traffic-grid">
                            <div class="traffic-col">
                                <div class="label-row">
                                    <span class="label">Recent Flow Count</span>
                                    <span class="val" data-ref="flowCountLabel">0</span>
                                </div>
                                <div class="traffic-chart">
                                    <svg viewBox="0 0 300 100" style="width:100%;height:100%;" preserveAspectRatio="none">
                                        <polyline data-ref="flowCountPath" fill="none" stroke="var(--teal)" stroke-linecap="round" stroke-width="3" points="0,80 30,75 60,85 90,60 120,70 150,40 180,50 210,30 240,40 270,10 300,20"></polyline>
                                    </svg>
                                </div>
                            </div>
                            <div class="traffic-col">
                                <div class="label-row">
                                    <span class="label">Recent Trace Count</span>
                                    <span class="val" data-ref="traceCountLabel">0</span>
                                </div>
                                <div class="traffic-chart">
                                    <svg viewBox="0 0 300 100" style="width:100%;height:100%;" preserveAspectRatio="none">
                                        <polyline data-ref="traceCountPath" fill="none" stroke="var(--ink)" stroke-linecap="round" stroke-width="3" points="0,50 30,55 60,45 90,70 120,60 150,80 180,75 210,90 240,85 270,95 300,80"></polyline>
                                    </svg>
                                </div>
                            </div>
                        </div>

                        <div class="traffic-footer">
                            <div class="tf-stat">
                                <div class="tf-stat-label">Error Rate</div>
                                <div class="tf-stat-val" data-ref="errorRateLabel">0.0%</div>
                            </div>
                            <div class="tf-stat">
                                <div class="tf-stat-label">Top Edge</div>
                                <div class="tf-stat-val" data-ref="topEdgeLabel">N/A</div>
                            </div>
                            <div class="tf-stat">
                                <div class="tf-stat-label">Dropped Logs</div>
                                <div class="tf-stat-val" data-ref="droppedLogsLabel">0</div>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- Active Node Health -->
                <section class="node-section">
                    <div class="node-section-header">
                        <h3 class="node-section-title">Active Node Health</h3>
                    </div>
                    <div class="node-table-wrap">
                        <table class="node-table">
                            <thead>
                                <tr>
                                    <th>Node Identity</th>
                                    <th>Status</th>
                                    <th class="right">Inflight</th>
                                    <th class="right">Traces</th>
                                    <th class="right">Last Signal</th>
                                    <th class="right">Action</th>
                                </tr>
                            </thead>
                            <tbody data-ref="nodeTableBody">
                                <!-- Generated rows -->
                            </tbody>
                        </table>
                    </div>
                </section>
            </div>
        `;
    }

    afterMount() {
        this.shadowRoot.addEventListener('click', (event) => {
            const button = event.target.closest('[data-plugin-id][data-plugin-action]');
            if (!button) return;
            this.emit('keel:plugin-toggle', {
                pluginId: button.dataset.pluginId,
                action: button.dataset.pluginAction
            });
        });
    }

    render(appState) {
        this.ensureInitialized();
        const metrics = appState.metrics;

        if (!metrics) {
            this.refs.windowMsLabel.textContent = 'Metrics snapshot not loaded yet';
            this.refs.nodeTableBody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--muted);">Metrics snapshot not loaded yet.</td></tr>';
            return;
        }

        this.refs.windowMsLabel.textContent = `Data resolution: ${Math.round(metrics.windowMs / 1000)}s intervals`;

        // CPU Card
        const cpuPercentStr = formatPercent(metrics.kernel.processCpuLoad);
        if (cpuPercentStr === 'n/a') {
            this.refs.cpuValueLabel.innerHTML = 'n/a';
        } else {
            this.refs.cpuValueLabel.innerHTML = `${cpuPercentStr.replace('%', '')}<span>%</span>`;
        }
        
        // Render CPU Path
        const history = appState.metricsHistory || [];
        if (history.length > 0) {
            const values = history.map((item) => item.cpu || 0);
            const max = Math.max(...values, 1);
            const min = Math.min(...values, 0);
            const range = Math.max(max - min, 1);
            const width = 800;
            const height = 200;
            
            const points = values.map((val, i) => {
                const x = values.length === 1 ? 0 : (i / (values.length - 1)) * width;
                const normalized = (val - min) / range;
                const y = height - (normalized * (height - 40)) - 20; // add some padding
                return {x, y};
            });
            
            if (points.length > 0) {
                let d = `M${points[0].x},${points[0].y} `;
                for (let i = 1; i < points.length; i++) {
                    d += `L${points[i].x},${points[i].y} `;
                }
                this.refs.cpuPathLine.setAttribute('d', d);
                this.refs.cpuPathFill.setAttribute('d', `${d} L${points[points.length-1].x},${height} L0,${height} Z`);
            }
        }

        // Memory Card
        const heapUsedPct = metrics.kernel.heapUsedPercent || 0;
        const heapUsedInt = Math.round(heapUsedPct);
        this.refs.memoryPercentLabel.innerHTML = `${heapUsedInt}<span>%</span>`;
        
        const offset = 440 - ((heapUsedPct / 100) * 440);
        this.refs.memoryCircle.style.strokeDashoffset = offset;
        
        this.refs.memAllocatedLabel.textContent = formatBytes(metrics.kernel.heapMaxBytes);
        this.refs.memAvailableLabel.textContent = formatBytes(metrics.kernel.heapMaxBytes - metrics.kernel.heapUsedBytes);

        // Latency Card
        this.refs.latencyP95Label.innerHTML = `${metrics.latency.p95Ms}<span>ms</span>`;
        this.refs.latencyP99Label.innerHTML = `${metrics.latency.p99Ms}<span>ms</span>`;
        this.refs.latencyAvgLabel.textContent = `avg ${metrics.latency.avgMs.toFixed(1)}ms`;

        // Distribution Curve (real data from histogram, fallback to derived from p95/avg)
        const curveContainer = this.shadowRoot.querySelector('.dist-bars');
        if (curveContainer) {
            const histogram = appState.latencyHistogram;
            let heights, labels;
            if (histogram && histogram.buckets && histogram.buckets.length > 0) {
                const maxCount = Math.max(...histogram.buckets.map(b => b.count), 1);
                // Map 8 buckets to 7 bars (aggregate last bucket if needed)
                const bucketHeights = histogram.buckets.map(b => (b.count / maxCount) * 100);
                heights = bucketHeights.slice(0, 7);
                labels = histogram.buckets.map(b => b.label).slice(0, 7);
            } else {
                // Fallback: derive bell curve from p95 and avg using a deterministic approach
                const avg = metrics.latency.avgMs || 0;
                const p95 = metrics.latency.p95Ms || Math.max(avg * 2, 1);
                const p50 = avg > 0 ? avg : p95 / 2;
                // Generate 7 approximate bucket heights based on percentiles
                heights = [5, 20, 55, 85, 55, 25, 8].map((h, i) => {
                    const position = i / 6; // 0 to 1
                    const expectedLatency = p50 + (p95 - p50) * (2 * position - 1) * (2 * position - 1);
                    const normalized = 1 - Math.min(1, Math.abs(expectedLatency - (avg || 1)) / Math.max(avg || 1, 1));
                    return Math.max(5, Math.min(95, h * normalized + 10));
                });
                labels = null;
            }

            curveContainer.innerHTML = heights.map((h, i) => {
                const label = labels ? labels[i] : '';
                const activeClass = label ? '' : (i >= 2 && i <= 4 ? 'active' : '');
                return `<div class="dist-bar ${activeClass}" style="height: ${h}%;" title="${label}"></div>`;
            }).join('');
        }
        
        // Traffic Card
        this.refs.flowCountLabel.textContent = metrics.traffic.recentFlowCount || 0;
        this.refs.traceCountLabel.textContent = metrics.traffic.recentTraceCount || 0;
        this.refs.errorRateLabel.textContent = formatPercent(metrics.latency.errorRate);
        const topEdge = (metrics.traffic.topEdges && metrics.traffic.topEdges[0]) 
            ? `${metrics.traffic.topEdges[0].edgeFrom} -> ${metrics.traffic.topEdges[0].edgeTo}` 
            : 'N/A';
        this.refs.topEdgeLabel.textContent = topEdge;
        this.refs.droppedLogsLabel.textContent = metrics.droppedLogCount || 0;

        // Render Traffic Paths
        if (history.length > 0) {
            const w = 300;
            const h = 100;
            
            // Flow Count
            const flows = history.map((item) => item.flowCount || 0);
            const maxFlow = Math.max(...flows, 1);
            const minFlow = Math.min(...flows, 0);
            const rangeFlow = Math.max(maxFlow - minFlow, 1);
            
            const flowPoints = flows.map((val, i) => {
                const x = flows.length === 1 ? 0 : (i / (flows.length - 1)) * w;
                const normalized = (val - minFlow) / rangeFlow;
                const y = h - (normalized * (h - 20)) - 10;
                return {x, y};
            });
            
            if (flowPoints.length > 0) {
                let d = `M${flowPoints[0].x},${flowPoints[0].y} `;
                for (let i = 1; i < flowPoints.length; i++) {
                    d += `L${flowPoints[i].x},${flowPoints[i].y} `;
                }
                this.refs.flowCountPath.setAttribute('points', flowPoints.map(p => `${p.x},${p.y}`).join(' '));
                this.refs.flowCountPath.removeAttribute('d'); // It's a polyline
            }
            
            // Trace Count
            const traces = history.map((item) => item.traceCount || 0);
            const maxTrace = Math.max(...traces, 1);
            const minTrace = Math.min(...traces, 0);
            const rangeTrace = Math.max(maxTrace - minTrace, 1);
            
            const tracePoints = traces.map((val, i) => {
                const x = traces.length === 1 ? 0 : (i / (traces.length - 1)) * w;
                const normalized = (val - minTrace) / rangeTrace;
                const y = h - (normalized * (h - 20)) - 10;
                return {x, y};
            });
            
            if (tracePoints.length > 0) {
                this.refs.traceCountPath.setAttribute('points', tracePoints.map(p => `${p.x},${p.y}`).join(' '));
            }
        }

        // Node Table
        const rowsHtml = (metrics.nodes || []).map((item) => {
            const pluginEntry = item.node.pluginId && appState.plugins ? appState.plugins.find((plugin) => plugin.pluginId === item.node.pluginId) : null;
            const pluginStatus = pluginEntry ? (pluginEntry.lifecycleState || '').toUpperCase() : '';
            const isRunning = pluginStatus === 'RUNNING';
            
            const pluginCtrl = pluginEntry
                ? (isRunning
                    ? `<button class="plugin-btn danger" data-plugin-id="${pluginEntry.pluginId}" data-plugin-action="stop">Stop</button>`
                    : `<button class="plugin-btn" data-plugin-id="${pluginEntry.pluginId}" data-plugin-action="start">Start</button>`)
                : '<span style="color: var(--muted); font-size: 10px;">—</span>';

            const health = item.node.healthState || item.node.lifecycleState || 'UNKNOWN';
            const tone = health.toUpperCase() === 'RUNNING' || health.toUpperCase() === 'UP' || health.toUpperCase() === 'HEALTHY' ? 'ok' 
                       : (health.toUpperCase() === 'ERROR' || health.toUpperCase() === 'DOWN' ? 'err' : 'warn');
            const statusText = (tone === 'ok' ? 'Operational' : (tone === 'err' ? 'Critical' : health)).toUpperCase();
            
            const iconSvg = item.node.kind === 'kernel' 
                ? `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>`
                : `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8" rx="2" ry="2"></rect><rect x="2" y="14" width="20" height="8" rx="2" ry="2"></rect><line x1="6" y1="6" x2="6.01" y2="6"></line><line x1="6" y1="18" x2="6.01" y2="18"></line></svg>`;

            return `
                <tr>
                    <td>
                        <div class="node-identity">
                            <div class="node-icon-box">${iconSvg}</div>
                            <div>
                                <div class="node-name">${nodeLabel(item.node)}</div>
                                <div class="node-sub">${item.node.id} · PID ${item.node.pid || '—'}</div>
                            </div>
                        </div>
                    </td>
                    <td>
                        <span class="status-badge ${tone}">${statusText}</span>
                    </td>
                    <td class="right">${item.node.inflightInvocations || 0}</td>
                    <td class="right">${item.recentTraceCount || 0}</td>
                    <td class="right" style="color: var(--muted); font-size: 10px;">Just now</td>
                    <td class="right">${pluginCtrl}</td>
                </tr>
            `;
        }).join('');

        this.refs.nodeTableBody.innerHTML = rowsHtml || '<tr><td colspan="6" style="padding: 32px; text-align: center; color: var(--muted);">No active nodes found.</td></tr>';
    }
}

if (!customElements.get('keel-panel-metrics')) {
    customElements.define('keel-panel-metrics', PanelMetrics);
}
