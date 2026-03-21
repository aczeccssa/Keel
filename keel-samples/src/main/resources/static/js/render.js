import { state, getAppShell } from './state.js';
import { escapeHtml, formatTime, formatDate, formatDuration, clamp } from './utils.js';
import { healthTone, healthColor } from './formatters.js';

function appShell() {
    return getAppShell();
}

export function renderChrome() {
    const app = appShell();
    if (app) app.renderChrome(state);
}

export function renderPanels() {
    const app = appShell();
    if (app) app.renderPanels(state);
}

export function renderTopologyPanel() {
    const app = appShell();
    if (app) app.renderTopologyPanel(state);
}

export function renderTracesPanel() {
    const app = appShell();
    if (app) app.renderTracesPanel(state);
}

export function renderLogsPanel() {
    const app = appShell();
    if (app) app.renderLogsPanel(state);
}

export function renderNodesPanel() {
    const app = appShell();
    if (app) app.renderNodesPanel(state);
}

export function renderMetricsPanel() {
    const app = appShell();
    if (app) app.renderMetricsPanel(state);
}

export function renderOpenApiPanel() {
    const app = appShell();
    if (app) app.renderOpenApiPanel(state);
}

export function renderPluginList() {
    const app = appShell();
    if (app && typeof app.renderMetricsPanel === 'function') app.renderMetricsPanel(state);
}

export function renderWaterfall(group) {
    const start = Math.min(...group.spans.map((span) => span.startEpochMs || 0));
    const total = Math.max(group.durationMs, 1);
    return group.spans.map((span) => {
        const left = ((span.startEpochMs - start) / total) * 100;
        const width = Math.max(((span.durationMs || 1) / total) * 100, 2);
        return `
            <div class="waterfall-row">
                <div><strong>${escapeHtml(span.operation)}</strong></div>
                <div class="waterfall-bar" data-span-id="${escapeHtml(span.spanId)}">
                    <span style="left:${left}%; width:${width}%; background:${healthColor(span.status)};"></span>
                </div>
                <div class="mono muted">${formatDuration(span)}</div>
            </div>
        `;
    }).join('');
}

export function renderHistogram(items) {
    if (!items || !items.length) return '<div class="empty" style="grid-column:1/-1;">No log rows to chart.</div>';
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
    }).join('');
}

export function renderLogDetail(selected) {
    if (!selected) return '<div class="empty">Select a log row to inspect it.</div>';
    const attrs = selected.attributes || {};
    const hasAttrs = Object.keys(attrs).length > 0;
    return `
        <div class="log-detail-template">
            <div class="log-meta-row">
                <span class="badge ${healthTone(selected.level)}">${escapeHtml(selected.level)}</span>
                <span class="badge neutral">${escapeHtml(selected.pluginId || 'n/a')}</span>
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
                </div>` : ''}
                ${selected.spanId ? `
                <div class="topo-kv-row">
                    <span class="topo-kv-label">Span ID</span>
                    <span class="topo-kv-val muted">${escapeHtml(selected.spanId)}</span>
                </div>` : ''}
            </div>
            ${hasAttrs ? `
            <div class="log-attr-block">
                <div class="topo-node-kv">
                    ${Object.entries(attrs).map(([k, v]) => `
                    <div class="topo-kv-row">
                        <span class="topo-kv-label">${escapeHtml(k)}</span>
                        <span class="topo-kv-val muted">${escapeHtml(String(v))}</span>
                    </div>`).join('')}
                </div>
            </div>` : ''}
            ${selected.throwable ? `<div class="throwable-block">${escapeHtml(selected.throwable)}</div>` : ''}
        </div>
    `;
}

export function statCards(entries) {
    return entries.map(([label, value, hint]) => `
        <div class="stat-card">
            <div class="label">${escapeHtml(label)}</div>
            <div class="value">${escapeHtml(value)}</div>
            <div class="hint">${escapeHtml(hint)}</div>
        </div>
    `).join('');
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
    return lines.join('');
}

export function badgeHtml(value) {
    return `<span class="badge ${healthTone(value)}">${escapeHtml(value || 'UNKNOWN')}</span>`;
}

export function tableHtml(headers, rows) {
    return `
        <table>
            <thead>
                <tr>${headers.map((header) => `<th>${escapeHtml(header)}</th>`).join('')}</tr>
            </thead>
            <tbody>
                ${rows.map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join('')}</tr>`).join('')}
            </tbody>
        </table>
    `;
}

export function sparklinePath(values, color = '#0f766e') {
    if (!values.length) return '';
    const max = Math.max(...values, 1);
    const min = Math.min(...values, 0);
    const range = Math.max(max - min, 1);
    const points = values.map((value, index) => {
        const x = values.length === 1 ? 0 : (index / (values.length - 1)) * 320;
        const y = 110 - (((value - min) / range) * 84 + 12);
        return `${x},${clamp(y, 6, 114)}`;
    }).join(' ');
    return `
        <polyline points="${points}" fill="none" stroke="${color}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"></polyline>
        <polyline points="0,116 ${points} 320,116" fill="${color}" opacity="0.08"></polyline>
    `;
}
