import { state, els, selectedNode, currentNodeSummary, groupedTraces, selectedTraceGroup, selectedSpan, logKey, selectedLog } from './state.js';
import { TABS, REFRESH_INTERVALS } from './config.js';
import { escapeHtml, formatPercent, formatTime, formatDate, formatBytes, formatDuration, clamp, sum } from './utils.js';
import { healthTone, healthColor, nodeLabel } from './formatters.js';
import { renderTopologyGraph } from './topology.js';
import { togglePlugin } from './api.js';

export function renderChrome() {
    els.sidebarNav.innerHTML = TABS.map((tab, index) => `
            <a class="nav-link ${tab.id === state.activeTab ? "is-active" : ""}" href="#${tab.id}">
                <span class="nav-link-label">
                    <span class="nav-icon">${index + 1}</span>
                    <span class="nav-copy"><strong>${escapeHtml(tab.label)}</strong><span>${escapeHtml(tab.note)}</span></span>
                </span>
            </a>
        `).join("");

    els.topbarTabs.innerHTML = TABS.map((tab) => `
            <a class="tab-link ${tab.id === state.activeTab ? "is-active" : ""}" href="#${tab.id}">${escapeHtml(tab.label)}</a>
        `).join("");

    document.querySelectorAll(".tab-panel").forEach((panel) => {
        panel.classList.toggle("is-active", panel.dataset.tab === state.activeTab);
    });

    const nodes = state.nodeSummaries.length || state.topology.length;
    els.sidebarMeta.innerHTML = `
            <a class="meta-link" href="#nodes">
                <span class="meta-copy"><strong>${nodes} Nodes</strong><span>Shared node selection</span></span>
            </a>
            <a class="meta-link" href="#logs">
                <span class="meta-copy"><strong>${state.logs.total || 0} Logs</strong><span>Structured explorer</span></span>
            </a>
            <a class="meta-link" href="#metrics">
                <span class="meta-copy"><strong>${state.metrics ? formatPercent(state.metrics.kernel.processCpuLoad) : "n/a"}</strong><span>Kernel CPU</span></span>
            </a>
        `;

    els.connectionText.textContent = state.connectionState === "Live"
        ? `Live ${REFRESH_INTERVALS.find((r) => r.ms === state.refreshIntervalMs)?.label || "5s"}`
        : state.connectionState;
    els.connectionPill.classList.toggle("is-offline", state.connectionState !== "Live");
    els.streamToggleBtn.textContent = state.streamEnabled ? "Live On" : "Live Off";
}

export function renderPanels() {
    renderTopologyPanel();
    renderTracesPanel();
    renderLogsPanel();
    renderNodesPanel();
    renderMetricsPanel();
}

export function renderTopologyPanel() {
    // === 核心修复：DOM存活守卫 ===
    if (!document.getElementById("topology-summary-pill")) return;

    document.getElementById("topology-summary-pill").textContent = `${state.topology.length} active nodes`;
    document.getElementById("panel-count-pill").textContent = `${state.panels.length} panels`;

    // 渲染2x2固定数据网格
    document.getElementById("topology-stats").innerHTML = statCards([
        ["Active Nodes", state.topology.length, `${state.nodeSummaries.filter((item) => healthTone(item.node.healthState) === "err").length} unhealthy`],
        ["Recent Traces", state.traces.length, `${state.flows.length} flows`],
        ["Inflight", sum(state.topology, "inflightInvocations"), `${sum(state.topology, "eventQueueDepth")} queued`],
        ["Dropped Logs", sum(state.topology, "droppedLogCount"), `${state.panels.length} panels`]
    ]);

    renderTopologyGraph();

    // 动态显隐逻辑
    const node = selectedNode();
    const summary = node ? currentNodeSummary(node.id) : null;

    document.getElementById("topology-node-card").style.display = node ? "flex" : "none";
    document.getElementById("topology-edge-card").style.display = node ? "none" : "flex";

    if (node) {
        document.getElementById("topology-node-title").textContent = nodeLabel(node);
        document.getElementById("topology-node-health").className = `badge ${healthTone(node && (node.healthState || node.lifecycleState))}`;
        document.getElementById("topology-node-health").textContent = (node.healthState || node.lifecycleState || "UNKNOWN");
        const health = node.healthState || node.lifecycleState || "UNKNOWN";
        const inflight = node.inflightInvocations || 0;
        const queue = node.eventQueueDepth || 0;
        const dropped = node.droppedLogCount || 0;
        document.getElementById("topology-node-detail").innerHTML = `
                    <div class="topo-node-meta-row">
                        <span class="badge ${healthTone(node.lifecycleState)}">${node.lifecycleState || "RUNNING"}</span>
                        <span class="badge ${healthTone(node.healthState)}">${node.healthState || "UNKNOWN"}</span>
                        <span class="badge neutral">${node.runtimeMode || "IN_PROCESS"}</span>
                    </div>
                    <div class="topo-node-metrics">
                        <div class="topo-metric-card">
                            <div class="topo-metric-label">Inflight</div>
                            <div class="topo-metric-value ${inflight > 5 ? "warn" : ""}">${inflight}</div>
                        </div>
                        <div class="topo-metric-card">
                            <div class="topo-metric-label">Queue</div>
                            <div class="topo-metric-value ${queue > 10 ? "err" : ""}">${queue}</div>
                        </div>
                        <div class="topo-metric-card">
                            <div class="topo-metric-label">Dropped</div>
                            <div class="topo-metric-value ${dropped > 0 ? "err" : "ok"}">${dropped}</div>
                        </div>
                    </div>
                    <div class="topo-node-kv">
                        <div class="topo-kv-row">
                            <span class="topo-kv-label">Recent Flows</span>
                            <span class="topo-kv-val">${summary ? summary.recentFlowCount : 0}</span>
                        </div>
                        <div class="topo-kv-row">
                            <span class="topo-kv-label">Recent Traces</span>
                            <span class="topo-kv-val">${summary ? summary.recentTraceCount : 0}</span>
                        </div>
                        <div class="topo-kv-row">
                            <span class="topo-kv-label">Errors</span>
                            <span class="topo-kv-val ${(summary ? summary.errorCount : 0) > 0 ? "err" : "ok"}">${summary ? summary.errorCount : 0}</span>
                        </div>
                        <div class="topo-kv-row">
                            <span class="topo-kv-label">PID</span>
                            <span class="topo-kv-val muted">${node.pid || "n/a"}</span>
                        </div>
                    </div>
                `;
    } else {
        const recentFlows = [...state.flows].slice(-15).reverse();
        document.getElementById("flow-count-chip").textContent = `${state.flows.length} flows`;
        document.getElementById("topology-flow-list").innerHTML = recentFlows.length ? recentFlows.map((flow) => `
                <div class="list-item">
                    <div class="row-title">
                        <strong>${escapeHtml(flow.edgeFrom)} -> ${escapeHtml(flow.edgeTo)}</strong>
                        <span class="badge ${healthTone(flow.status)}">${escapeHtml(flow.status || "OK")}</span>
                    </div>
                    <div class="row-subtitle mono">${escapeHtml(flow.operation || "rpc.invoke")} · ${formatTime(flow.startEpochMs)} · ${escapeHtml(flow.traceId || "trace")}</div>
                </div>
            `).join("") : `<div class="empty">No recent flows captured yet.</div>`;
    }
}

export function renderTracesPanel() {
    // === 核心修复：DOM存活守卫 ===
    if (!document.getElementById("trace-window-pill")) return;

    const groups = groupedTraces();
    const selectedGroup = selectedTraceGroup();
    const span = selectedSpan();
    document.getElementById("trace-window-pill").textContent = `${groups.length} grouped traces`;
    document.getElementById("trace-stats").innerHTML = statCards([
        ["Trace Groups", groups.length, `${state.traces.length} spans`],
        ["P95 Latency", state.metrics ? `${state.metrics.latency.p95Ms} ms` : "n/a", "recent window"],
        ["Error Rate", state.metrics ? formatPercent(state.metrics.latency.errorRate) : "n/a", "trace status"],
        ["Top Service", selectedGroup ? selectedGroup.service : "n/a", "selected trace"]
    ]);
    document.getElementById("trace-group-count").textContent = `${groups.length} traces`;
    document.getElementById("trace-group-list").innerHTML = groups.length ? groups.map((group) => `
            <div class="list-item trace-group ${selectedGroup && group.traceId === selectedGroup.traceId ? "is-selected" : ""}" data-trace-id="${escapeHtml(group.traceId)}">
                <div class="trace-title">
                    <strong>${escapeHtml(group.operation)}</strong>
                    <span class="badge ${group.errorCount ? "err" : "ok"}">${group.errorCount ? `${group.errorCount} errors` : "healthy"}</span>
                </div>
                <div class="trace-meta mono">${escapeHtml(group.traceId)}</div>
                <div class="trace-meta">${escapeHtml(group.service)} · ${group.spanCount} spans · ${group.durationMs} ms</div>
            </div>
        `).join("") : `<div class="empty">No traces available in memory.</div>`;

    document.getElementById("trace-waterfall-title").textContent = selectedGroup ? selectedGroup.traceId : "Select a trace";
    document.getElementById("trace-duration-chip").textContent = selectedGroup ? `${selectedGroup.durationMs} ms` : "—";
    document.getElementById("trace-waterfall-card").style.display = selectedGroup ? "block" : "none";
    document.getElementById("trace-waterfall").innerHTML = selectedGroup ? renderWaterfall(selectedGroup) : "";
    document.getElementById("span-detail-title").textContent = span ? span.operation : "Select a span";
    document.getElementById("span-duration-chip").textContent = span ? formatDuration(span) : "—";
    document.getElementById("span-detail-list").innerHTML = span ? detailEntries([
        ["Service", span.service],
        ["Trace ID", span.traceId],
        ["Span ID", span.spanId],
        ["Parent Span", span.parentSpanId || "root"],
        ["Start", formatDate(span.startEpochMs)],
        ["Duration", formatDuration(span)],
        ["Edge", `${span.edgeFrom || "?"} → ${span.edgeTo || "?"}`]
    ], span.attributes) : `<div class="empty">Select a trace to inspect its spans.</div>`;
}

export function renderLogsPanel() {
    // === 核心修复：DOM存活守卫 ===
    if (!document.getElementById("log-total-pill")) return;

    document.getElementById("log-query-input").value = state.logFilters.query;
    document.getElementById("log-level-select").value = state.logFilters.level;
    document.getElementById("log-source-input").value = state.logFilters.source;
    document.getElementById("log-window-select").value = state.logFilters.window;

    document.getElementById("log-total-pill").textContent = `${state.logs.total || 0} entries`;
    document.getElementById("log-page-chip").textContent = `limit ${state.logs.limit || 80}`;
    const logItems = state.logs.items || [];
    const windowChip = document.getElementById("log-window-chip");
    if (windowChip) windowChip.textContent = `${logItems.length} page items`;
    document.getElementById("log-histogram").innerHTML = renderHistogram(logItems);

    const selected = selectedLog();
    document.getElementById("log-table").innerHTML = logItems.length ? logItems.map((item) => `
            <div class="table-row log-row ${selected && logKey(item) === logKey(selected) ? "is-selected" : ""}" data-log-key="${escapeHtml(logKey(item))}">
                <div class="mono muted">${formatTime(item.timestamp)}</div>
                <div><span class="badge ${healthTone(item.level)}">${escapeHtml(item.level)}</span></div>
                <div>
                    <div class="row-title"><strong>${escapeHtml(item.message)}</strong></div>
                    <div class="row-subtitle">${escapeHtml(item.source)}${item.traceId ? ` · ${escapeHtml(item.traceId)}` : ""}</div>
                </div>
            </div>
        `).join("") : `<div class="empty">No logs matched the current filters.</div>`;

    document.getElementById("log-detail-title").textContent = selected ? selected.source : "No record selected";
    document.getElementById("log-detail-level").className = `badge ${healthTone(selected && selected.level)}`;
    document.getElementById("log-detail-level").textContent = selected ? selected.level : "idle";
    const attrs = selected.attributes || {};
    const hasAttrs = Object.keys(attrs).length > 0;
    document.getElementById("log-detail-list").innerHTML = selected ? `
                <div class="log-detail-template">
                    <div class="log-meta-row">
                        <span class="badge ${healthTone(selected.level)}">${escapeHtml(selected.level)}</span>
                        <span class="badge neutral">${escapeHtml(selected.pluginId || "n/a")}</span>
                    </div>
                    <div class="topo-node-kv">
                        <div class="topo-kv-row">
                            <span class="topo-kv-label">Timestamp</span>
                            <span class="topo-kv-val muted">${formatDate(selected.timestamp)}</span>
                        </div>
                        <div class="topo-kv-row">
                            <span class="topo-kv-label">Source</span>
                            <span class="topo-kv-val">${escapeHtml(selected.source)}</span>
                        </div>
                        ${selected.traceId ? `
                        <div class="topo-kv-row">
                            <span class="topo-kv-label">Trace ID</span>
                            <span class="topo-kv-val muted">${escapeHtml(selected.traceId)}</span>
                        </div>` : ""}
                        ${selected.spanId ? `
                        <div class="topo-kv-row">
                            <span class="topo-kv-label">Span ID</span>
                            <span class="topo-kv-val muted">${escapeHtml(selected.spanId)}</span>
                        </div>` : ""}
                    </div>
                    ${hasAttrs ? `
                    <div class="log-attr-block">
                        <div class="topo-node-kv">
                            ${Object.entries(attrs).map(([k, v]) => `
                            <div class="topo-kv-row">
                                <span class="topo-kv-label">${escapeHtml(k)}</span>
                                <span class="topo-kv-val muted">${escapeHtml(String(v))}</span>
                            </div>`).join("")}
                        </div>
                    </div>` : ""}
                    ${selected.throwable ? `<div class="throwable-block">${escapeHtml(selected.throwable)}</div>` : ""}
                </div>
            ` : `<div class="empty">Select a log row to inspect it.</div>`;
}

export function renderNodesPanel() {
    // === 核心修复：DOM存活守卫 ===
    if (!document.getElementById("node-total-pill")) return;

    const summaries = [...state.nodeSummaries];
    const selected = selectedNode();
    const selectedSummary = selected ? currentNodeSummary(selected.id) : null;

    document.getElementById("node-total-pill").textContent = `${summaries.length} nodes`;
    document.getElementById("node-stats").innerHTML = statCards([
        ["Active Nodes", summaries.length, `${summaries.filter((item) => item.node.runtimeMode === "EXTERNAL_JVM").length} external`],
        ["Unhealthy", summaries.filter((item) => healthTone(item.node.healthState) === "err").length, "health summary"],
        ["Queued Events", sum(summaries.map((item) => item.node), "eventQueueDepth"), "runtime pressure"],
        ["Dropped Logs", sum(summaries.map((item) => item.node), "droppedLogCount"), "logger backpressure"]
    ]);

    // 动态侧边栏逻辑
    const layoutGrid = document.getElementById("nodes-layout-grid");
    const detailCard = document.getElementById("nodes-detail-card");

    if (selected) {
        layoutGrid.classList.add("has-selection");
        detailCard.style.display = "flex";
    } else {
        layoutGrid.classList.remove("has-selection");
        detailCard.style.display = "none";
    }

    document.getElementById("node-grid").innerHTML = summaries.length ? summaries.map((item) => `
            <div class="card node-summary ${selected && selected.id === item.node.id ? "is-selected" : ""}" data-node-id="${escapeHtml(item.node.id)}">
                <div class="section-head" style="margin-bottom: 10px;">
                    <div>
                        <div class="section-label">${escapeHtml(item.node.kind || "node")}</div>
                        <h3 style="font-size: 20px;">${escapeHtml(nodeLabel(item.node))}</h3>
                    </div>
                    <span class="badge ${healthTone(item.node.healthState || item.node.lifecycleState)}">${escapeHtml(item.node.healthState || item.node.lifecycleState || "UNKNOWN")}</span>
                </div>
                <div class="muted" style="margin-bottom: 12px;">${escapeHtml(item.node.runtimeMode || "IN_PROCESS")} · ${item.node.pid ? `PID ${escapeHtml(item.node.pid)}` : escapeHtml(item.node.pluginId || item.node.id)}</div>
                <div class="detail-list">
                    <div><div class="section-label">Queue</div><div class="progress"><span style="width:${clamp((item.node.eventQueueDepth || 0) * 10, 0, 100)}%;"></span></div></div>
                    <div><div class="section-label">Inflight</div><div class="progress"><span style="width:${clamp((item.node.inflightInvocations || 0) * 10, 0, 100)}%;"></span></div></div>
                </div>
            </div>
        `).join("") : `<div class="empty">No node summaries available.</div>`;

    if (selected) {
        document.getElementById("nodes-detail-title").textContent = nodeLabel(selected);
        document.getElementById("nodes-detail-health").className = `badge ${healthTone(selected && (selected.healthState || selected.lifecycleState))}`;
        document.getElementById("nodes-detail-health").textContent = (selected.healthState || selected.lifecycleState || "UNKNOWN");
        document.getElementById("nodes-detail-list").innerHTML = detailEntries([
            ["Node ID", selected.id],
            ["Plugin", selected.pluginId || "kernel"],
            ["Runtime", selected.runtimeMode || "IN_PROCESS"],
            ["Lifecycle", selected.lifecycleState || "RUNNING"],
            ["Inflight", selected.inflightInvocations || 0],
            ["Queue", selected.eventQueueDepth || 0],
            ["Dropped Logs", selected.droppedLogCount || 0],
            ["Recent Flows", selectedSummary ? selectedSummary.recentFlowCount : 0],
            ["Recent Traces", selectedSummary ? selectedSummary.recentTraceCount : 0],
            ["Error Count", selectedSummary ? selectedSummary.errorCount : 0]
        ], selected.labels);
    }
}

export function renderMetricsPanel() {
    // === 核心修复：DOM存活守卫 ===
    if (!document.getElementById("metrics-window-pill")) return;

    const metrics = state.metrics;
    if (!metrics) {
        document.getElementById("metrics-grid").innerHTML = `<div class="empty">Metrics snapshot not loaded yet.</div>`;
        return;
    }

    document.getElementById("metrics-window-pill").textContent = `${Math.round(metrics.windowMs / 1000)}s window`;
    document.getElementById("metrics-grid").innerHTML = statCards([
        ["CPU", formatPercent(metrics.kernel.processCpuLoad), "kernel process"],
        ["Heap", formatPercent(metrics.kernel.heapUsedPercent), `${formatBytes(metrics.kernel.heapUsedBytes)} used`],
        ["P95 Latency", `${metrics.latency.p95Ms} ms`, `${metrics.latency.completedSpanCount} spans`],
        ["Flow Volume", metrics.traffic.recentFlowCount, `${metrics.traffic.recentTraceCount} traces`]
    ]);

    document.getElementById("cpu-value-chip").textContent = formatPercent(metrics.kernel.processCpuLoad);
    document.getElementById("heap-value-chip").textContent = formatPercent(metrics.kernel.heapUsedPercent);
    document.getElementById("cpu-sparkline").innerHTML = sparklinePath(state.metricsHistory.map((item) => item.cpu));
    document.getElementById("heap-sparkline").innerHTML = sparklinePath(state.metricsHistory.map((item) => item.heap), "#c56a1b");
    document.getElementById("cpu-detail-list").innerHTML = detailEntries([
        ["System Load Avg", metrics.kernel.systemLoadAverage != null ? metrics.kernel.systemLoadAverage.toFixed(2) : "n/a"],
        ["Processors", metrics.kernel.availableProcessors],
        ["Threads", metrics.kernel.threadCount],
        ["Dropped Logs", metrics.droppedLogCount]
    ]);
    document.getElementById("heap-detail-list").innerHTML = detailEntries([
        ["Used", formatBytes(metrics.kernel.heapUsedBytes)],
        ["Max", formatBytes(metrics.kernel.heapMaxBytes)],
        ["Threads", metrics.kernel.threadCount],
        ["Dropped Logs", metrics.droppedLogCount]
    ]);
    document.getElementById("metrics-latency-list").innerHTML = detailEntries([
        ["Average", `${metrics.latency.avgMs.toFixed(1)} ms`],
        ["P95", `${metrics.latency.p95Ms} ms`],
        ["P99", `${metrics.latency.p99Ms} ms`],
        ["Error Rate", formatPercent(metrics.latency.errorRate)],
        ["Top Edge", metrics.traffic.topEdges[0] ? `${metrics.traffic.topEdges[0].edgeFrom} -> ${metrics.traffic.topEdges[0].edgeTo}` : "n/a"]
    ]);
    document.getElementById("edge-table-wrap").innerHTML = metrics.traffic.topEdges.length ? tableHtml(
        ["Edge", "Count", "Errors"],
        metrics.traffic.topEdges.map((edge) => [
            `${edge.edgeFrom} -> ${edge.edgeTo}`,
            edge.count,
            edge.errorCount
        ])
    ) : `<div class="empty">No edge traffic in the current window.</div>`;
    document.getElementById("metrics-node-table-wrap").innerHTML = (metrics.nodes && metrics.nodes.length) ? tableHtml(
        ["Node", "Status", "Inflight", "Queue", "Traces", "Errors", "Plugin"],
        metrics.nodes.map((item) => {
            const nodePluginId = item.node.pluginId;
            const pluginEntry = nodePluginId
                ? (window._cachedPlugins || []).find(p => p.pluginId === nodePluginId)
                : null;
            const pluginStatus = pluginEntry
                ? (pluginEntry.lifecycleState || '').toUpperCase()
                : '';
            const isRunning = pluginStatus === 'RUNNING';
            const pluginCtrl = pluginEntry
                ? (isRunning
                    ? `<button class="action-btn danger" style="padding:4px 8px;font-size:10px;" onclick="window.togglePlugin('${escapeHtml(pluginEntry.pluginId)}', 'stop')">Stop</button>`
                    : `<button class="action-btn" style="padding:4px 8px;font-size:10px;" onclick="window.togglePlugin('${escapeHtml(pluginEntry.pluginId)}', 'start')">Start</button>`)
                : '';
            return [
                nodeLabel(item.node),
                badgeHtml(item.node.healthState || item.node.lifecycleState),
                item.node.inflightInvocations || 0,
                item.node.eventQueueDepth || 0,
                item.recentTraceCount,
                item.errorCount,
                nodePluginId && pluginEntry
                    ? `${pluginCtrl}`
                    : `<span class="muted">—</span>`
            ];
        })
    ) : `<div class="empty">No metric node rows available.</div>`;
}

export function renderPluginList(plugins) {
    const container = document.getElementById('admin-plugin-list');
    if (!container) return;
    document.getElementById('admin-plugin-count-pill').textContent = plugins.length + ' plugins';
    if (!plugins.length) {
        container.innerHTML = '<div class="empty-state">No plugins registered</div>';
        return;
    }
    container.innerHTML = plugins.map(p => {
        const lifecycleState = (p.lifecycleState || '').toUpperCase();
        const isRunning = lifecycleState === 'RUNNING';
        const healthCls = isRunning ? 'ok' : (lifecycleState === 'FAILED' ? 'bad' : 'neutral');
        return `
                    <div class="plugin-item">
                        <span class="plugin-item-id">${escapeHtml(p.pluginId)}</span>
                        <span class="plugin-item-version">${escapeHtml(p.version || '')}</span>
                        <span class="badge ${healthCls}">${escapeHtml(lifecycleState)}</span>
                        <div class="plugin-item-actions">
                            ${isRunning
                ? `<button class="action-btn danger" onclick="window.togglePlugin('${escapeHtml(p.pluginId)}', 'stop')">Stop</button>`
                : `<button class="action-btn" onclick="window.togglePlugin('${escapeHtml(p.pluginId)}', 'start')">Start</button>`
            }
                        </div>
                    </div>`;
    }).join('');
}

export function renderWaterfall(group) {
    const start = Math.min(...group.spans.map((span) => span.startEpochMs || 0));
    const total = Math.max(group.durationMs, 1);
    let prevEnd = null;
    return group.spans.map((span) => {
        const left = ((span.startEpochMs - start) / total) * 100;
        const width = Math.max(((span.durationMs || 1) / total) * 100, 2);
        const isKernel = (span.service || '').toLowerCase().includes('kernel');
        const svcLabel = isKernel ? 'kernel' : 'other';
        prevEnd = (span.startEpochMs || 0) + (span.durationMs || 0);
        return `
                <div class="waterfall-row">
                    <div><strong>${escapeHtml(span.operation)}</strong></div>
                    <div class="waterfall-bar" data-span-id="${escapeHtml(span.spanId)}">
                        <span style="left:${left}%; width:${width}%; background:${healthColor(span.status)};"></span>
                    </div>
                    <div class="mono muted">${formatDuration(span)}</div>
                </div>
            `;
    }).join("");
}

export function renderHistogram(items) {
    if (!items || !items.length) return `<div class="empty" style="grid-column:1/-1;">No log rows to chart.</div>`;
    const timestamps = items.map((item) => item.timestamp);
    const min = Math.min(...timestamps);
    const max = Math.max(...timestamps);
    const span = Math.max(max - min, 1);
    const buckets = Array.from({ length: 12 }, () => []);
    items.forEach((item) => {
        const index = Math.min(11, Math.floor(((item.timestamp - min) / span) * 12));
        buckets[index].push(item);
    });
    const tallest = Math.max(...buckets.map((bucket) => bucket.length), 1);
    return buckets.map((bucket, index) => {
        const height = Math.max((bucket.length / tallest) * 100, bucket.length ? 12 : 6);
        const labelTs = min + (span / 12) * index;
        return `<div class="histogram-bar" style="height:${height}%"><span>${formatTime(labelTs)}</span></div>`;
    }).join("");
}

export function statCards(entries) {
    return entries.map(([label, value, hint]) => `
            <div class="stat-card">
                <div class="label">${escapeHtml(label)}</div>
                <div class="value">${escapeHtml(value)}</div>
                <div class="hint">${escapeHtml(hint)}</div>
            </div>
        `).join("");
}

export function detailEntries(entries, extra = {}) {
    const lines = entries.map(([label, value]) => `
            <div class="list-item">
                <div class="section-label">${escapeHtml(label)}</div>
                <div style="margin-top:8px; font-weight:700;">${escapeHtml(value)}</div>
            </div>
        `);
    Object.entries(extra || {}).forEach(([label, value]) => {
        lines.push(`
                <div class="list-item">
                    <div class="section-label">${escapeHtml(label)}</div>
                    <div style="margin-top:8px; font-weight:700;">${escapeHtml(value)}</div>
                </div>
            `);
    });
    return lines.join("");
}

export function badgeHtml(value) {
    return `<span class="badge ${healthTone(value)}">${escapeHtml(value || "UNKNOWN")}</span>`;
}

export function tableHtml(headers, rows) {
    return `
            <table>
                <thead>
                    <tr>${headers.map((header) => `<th>${escapeHtml(header)}</th>`).join("")}</tr>
                </thead>
                <tbody>
                    ${rows.map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join("")}</tr>`).join("")}
                </tbody>
            </table>
        `;
}

export function sparklinePath(values, color = "#0f766e") {
    if (!values.length) return "";
    const max = Math.max(...values, 1);
    const min = Math.min(...values, 0);
    const range = Math.max(max - min, 1);
    const points = values.map((value, index) => {
        const x = values.length === 1 ? 0 : (index / (values.length - 1)) * 320;
        const y = 88 - (((value - min) / range) * 78);
        return `${x},${y}`;
    });
    return `
            <polyline fill="none" stroke="${color}" stroke-width="4" points="${points.join(" ")}" stroke-linecap="round" stroke-linejoin="round"></polyline>
            <line x1="0" y1="88" x2="320" y2="88" stroke="rgba(100,116,139,0.2)" stroke-width="1"></line>
        `;
}
