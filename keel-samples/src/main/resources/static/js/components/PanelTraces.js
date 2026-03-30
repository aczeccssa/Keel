import { KeelElement } from './base/KeelElement.js';
import { REFRESH_DEBOUNCE_MS } from '../config.js';
import { escapeHtml, formatDate, formatPercent } from '../utils.js';

const WINDOWS = [
    { key: '15m', label: 'Last 15 Minutes' },
    { key: '1h', label: 'Last 1 Hour' },
    { key: '6h', label: 'Last 6 Hours' },
    { key: '24h', label: 'Last 24 Hours' }
];

const STATUS_OPTIONS = [
    ['all', 'All Statuses'],
    ['ok', 'Healthy'],
    ['error', 'Errors'],
    ['slow', 'Slow'],
    ['active', 'Active']
];

export class PanelTraces extends KeelElement {
    hostStyles() {
        return 'height:100%;';
    }

    template() {
        return `
            <style>
                :host {
                    color: var(--ink);
                }
                * {
                    box-sizing: border-box;
                }
                .material-symbols-outlined {
                    font-family: 'Material Symbols Outlined';
                    font-weight: normal;
                    font-style: normal;
                    font-size: 20px;
                    line-height: 1;
                    letter-spacing: normal;
                    text-transform: none;
                    display: inline-block;
                    white-space: nowrap;
                    word-wrap: normal;
                    direction: ltr;
                    -webkit-font-smoothing: antialiased;
                    font-variation-settings: 'FILL' 0, 'wght' 400, 'GRAD' 0, 'opsz' 24;
                }
                .panel-shell {
                    min-height: 100%;
                    color: var(--ink);
                    overflow: auto;
                }
                .canvas {
                    padding: 32px 48px 40px;
                }
                .page-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: flex-end;
                    gap: 24px;
                    margin-bottom: 32px;
                }
                .page-title {
                    margin: 0 0 12px;
                    font-family: var(--font-headline);
                    font-size: 40px;
                    line-height: 0.96;
                    letter-spacing: -0.04em;
                    font-weight: 500;
                    color: var(--ink);
                }
                .page-subtitle {
                    margin: 0;
                    max-width: 720px;
                    color: var(--muted);
                    font-size: 14px;
                    line-height: 1.65;
                }
                .page-actions {
                    position: relative;
                    display: flex;
                    gap: 12px;
                    align-items: center;
                    flex-wrap: wrap;
                    justify-content: flex-end;
                }
                .action-btn {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    border: 1px solid var(--line);
                    background: var(--panel-strong);
                    color: var(--ink);
                    box-shadow: var(--shadow-sm);
                    padding: 11px 16px;
                    border-radius: 999px;
                    cursor: pointer;
                    font-size: 11px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.12em;
                    transition: background-color 0.18s ease, transform 0.18s ease, border-color 0.18s ease;
                }
                .action-btn:hover {
                    background: rgba(255, 255, 255, 0.98);
                    border-color: var(--line-strong);
                    transform: translateY(-1px);
                }
                .popover {
                    position: absolute;
                    top: calc(100% + 12px);
                    right: 0;
                    z-index: 4;
                    min-width: 320px;
                    display: none;
                    padding: 18px;
                    border-radius: var(--radius-lg);
                    background: var(--panel-strong);
                    border: 1px solid var(--line);
                    box-shadow: var(--shadow-lg);
                }
                .popover.is-open {
                    display: block;
                }
                .filter-grid,
                .window-menu {
                    display: grid;
                    gap: 14px;
                }
                .filter-field {
                    display: grid;
                    gap: 8px;
                }
                .filter-field label {
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                }
                .filter-field input,
                .filter-field select {
                    width: 100%;
                    border: 1px solid var(--line);
                    border-radius: 14px;
                    background: rgba(255, 255, 255, 0.9);
                    color: var(--ink);
                    padding: 12px 14px;
                    font: inherit;
                    outline: none;
                    transition: border-color 0.18s ease, box-shadow 0.18s ease, background-color 0.18s ease;
                }
                .filter-field input:focus,
                .filter-field select:focus {
                    border-color: rgba(15, 118, 110, 0.36);
                    box-shadow: 0 0 0 4px rgba(15, 118, 110, 0.08);
                    background: #fff;
                }
                .window-item {
                    border: 0;
                    border-radius: 14px;
                    background: transparent;
                    color: var(--ink);
                    padding: 12px 14px;
                    text-align: left;
                    font-size: 12px;
                    font-weight: 700;
                    cursor: pointer;
                }
                .window-item.is-active {
                    background: var(--teal-soft);
                    color: var(--teal);
                }
                .stats-grid {
                    display: grid;
                    grid-template-columns: repeat(4, minmax(0, 1fr));
                    gap: 16px;
                    margin-bottom: 32px;
                }
                .stat-card {
                    background: var(--panel-strong);
                    padding: 22px 24px;
                    border-radius: var(--radius-lg);
                    border: 1px solid var(--line);
                    box-shadow: var(--shadow-sm);
                    border-top: 4px solid var(--navy);
                }
                .stat-card.secondary { border-top-color: var(--teal); }
                .stat-card.error { border-top-color: var(--red); }
                .stat-card.warn { border-top-color: var(--amber); }
                .stat-label {
                    display: block;
                    margin-bottom: 10px;
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                }
                .stat-value {
                    font-family: var(--font-headline);
                    font-size: 38px;
                    line-height: 1.02;
                    color: var(--ink);
                }
                .content-grid {
                    display: grid;
                    grid-template-columns: minmax(320px, 0.96fr) minmax(0, 1.24fr);
                    gap: 24px;
                    align-items: start;
                }
                .recent-head {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    padding: 0 4px;
                    margin-bottom: 16px;
                }
                .recent-title,
                .detail-title {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 26px;
                    font-weight: 500;
                    color: var(--ink);
                }
                .recent-meta,
                .headline-label,
                .detail-kicker,
                .scale span {
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                }
                .trace-list {
                    display: grid;
                    gap: 12px;
                }
                .trace-item {
                    border: 1px solid var(--line);
                    width: 100%;
                    text-align: left;
                    padding: 18px 18px 16px;
                    border-radius: 18px;
                    background: var(--panel-strong);
                    box-shadow: var(--shadow-sm);
                    cursor: pointer;
                    transition: background-color 0.18s ease, border-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
                }
                .trace-item:hover {
                    background: rgba(255, 255, 255, 0.99);
                    border-color: var(--line-strong);
                    transform: translateY(-1px);
                }
                .trace-item.is-selected {
                    background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(240, 251, 248, 0.94));
                    border-color: rgba(15, 118, 110, 0.28);
                    box-shadow: 0 14px 28px -14px rgba(15, 23, 42, 0.18);
                }
                .trace-item.is-error.is-selected {
                    border-color: rgba(198, 40, 40, 0.26);
                    background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(255, 244, 243, 0.96));
                }
                .trace-top,
                .trace-bottom,
                .timeline-meta {
                    display: flex;
                    justify-content: space-between;
                    gap: 12px;
                    align-items: flex-start;
                }
                .trace-id,
                .timeline-id,
                .timeline-duration {
                    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
                }
                .trace-id {
                    font-size: 12px;
                    font-weight: 700;
                    color: var(--navy);
                }
                .trace-op {
                    margin: 10px 0 4px;
                    font-size: 15px;
                    font-weight: 700;
                    color: var(--ink);
                }
                .trace-meta {
                    color: var(--muted);
                    font-size: 11px;
                }
                .trace-duration {
                    font-family: var(--font-headline);
                    font-size: 26px;
                    color: var(--ink);
                    white-space: nowrap;
                }
                .trace-duration.error { color: var(--red); }
                .trace-duration.warn { color: var(--amber); }
                .badge {
                    border-radius: 999px;
                    padding: 5px 10px;
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.1em;
                }
                .badge.ok {
                    background: var(--green-soft);
                    color: var(--green);
                }
                .badge.error {
                    background: var(--red-soft);
                    color: var(--red);
                }
                .badge.warn {
                    background: var(--amber-soft);
                    color: var(--amber);
                }
                .badge.live {
                    background: var(--teal-soft);
                    color: var(--teal);
                }
                .timeline-card {
                    position: sticky;
                    top: 24px;
                    background: var(--panel-strong);
                    border: 1px solid var(--line);
                    border-radius: var(--radius-xl);
                    box-shadow: var(--shadow-md);
                    padding: 30px 32px;
                }
                .timeline-head {
                    display: flex;
                    justify-content: space-between;
                    align-items: flex-start;
                    gap: 24px;
                    margin-bottom: 32px;
                }
                .timeline-title {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 32px;
                    font-weight: 500;
                    color: var(--ink);
                }
                .timeline-id {
                    margin-top: 6px;
                    color: var(--muted);
                    font-size: 12px;
                }
                .headline-group {
                    display: flex;
                    gap: 20px;
                }
                .headline-stat {
                    text-align: right;
                }
                .headline-value {
                    margin-top: 4px;
                    font-family: var(--font-headline);
                    font-size: 28px;
                    color: var(--ink);
                }
                .scale {
                    display: flex;
                    justify-content: space-between;
                    gap: 10px;
                    padding: 0 8px 10px;
                    border-bottom: 1px solid var(--line);
                }
                .timeline-list {
                    display: grid;
                    gap: 14px;
                    margin-top: 20px;
                }
                .timeline-row {
                    cursor: pointer;
                }
                .timeline-row.is-selected .timeline-track {
                    box-shadow: inset 0 0 0 1px rgba(15, 118, 110, 0.28);
                }
                .timeline-copy {
                    min-width: 0;
                    display: inline-flex;
                    align-items: center;
                    gap: 10px;
                    padding-left: calc(var(--depth, 0) * 24px);
                    font-size: 12px;
                    font-weight: 600;
                    color: var(--ink);
                }
                .timeline-copy.resource {
                    color: var(--muted);
                    font-style: italic;
                    font-weight: 500;
                }
                .timeline-dot {
                    width: 8px;
                    height: 8px;
                    border-radius: 999px;
                    flex: 0 0 auto;
                    background: var(--navy);
                }
                .timeline-dot.ok { background: var(--green); }
                .timeline-dot.error { background: var(--red); }
                .timeline-dot.warn { background: var(--amber); }
                .timeline-track {
                    position: relative;
                    margin-top: 8px;
                    height: 10px;
                    border-radius: 999px;
                    background: rgba(17, 24, 39, 0.08);
                    overflow: hidden;
                }
                .timeline-bar {
                    position: absolute;
                    top: 0;
                    bottom: 0;
                    min-width: 8px;
                    border-radius: 999px;
                    background: var(--navy);
                }
                .timeline-bar.ok { background: var(--green); }
                .timeline-bar.error { background: var(--red); }
                .timeline-bar.warn { background: var(--amber); }
                .timeline-bar.resource {
                    background: var(--muted);
                    opacity: 0.6;
                }
                .detail-card {
                    margin-top: 32px;
                    background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(246, 243, 236, 0.78));
                    border: 1px solid var(--line);
                    border-radius: var(--radius-lg);
                    padding: 24px;
                }
                .detail-grid {
                    display: grid;
                    grid-template-columns: repeat(2, minmax(0, 1fr));
                    gap: 18px 28px;
                }
                .detail-value {
                    color: var(--ink);
                    font-size: 14px;
                    font-weight: 600;
                    word-break: break-word;
                }
                .empty {
                    border: 1px solid var(--line);
                    border-radius: 16px;
                    background: var(--panel);
                    padding: 22px;
                    color: var(--muted);
                    font-size: 13px;
                    line-height: 1.6;
                }
                @media (max-width: 1100px) {
                    .stats-grid {
                        grid-template-columns: repeat(2, minmax(0, 1fr));
                    }
                    .content-grid {
                        grid-template-columns: 1fr;
                    }
                    .timeline-card {
                        position: static;
                    }
                }
                @media (max-width: 720px) {
                    .canvas {
                        padding: 24px;
                    }
                    .page-header,
                    .timeline-head {
                        flex-direction: column;
                        align-items: stretch;
                    }
                    .page-actions,
                    .headline-group {
                        justify-content: flex-start;
                    }
                    .stats-grid,
                    .detail-grid {
                        grid-template-columns: 1fr;
                    }
                    .page-title {
                        font-size: 34px;
                    }
                    .timeline-title {
                        font-size: 28px;
                    }
                }
            </style>

            <section class="panel-shell">
                <div class="canvas">
                    <div class="page-header">
                        <div>
                            <h1 class="page-title">Distributed Traces</h1>
                            <p class="page-subtitle">Analyze end-to-end request flows across your distributed architecture with surgical precision.</p>
                        </div>
                        <div class="page-actions">
                            <button class="action-btn" type="button" data-ref="filterBtn">
                                <span class="material-symbols-outlined">filter_list</span>
                                <span>Filter</span>
                            </button>
                            <button class="action-btn" type="button" data-ref="windowBtn">
                                <span class="material-symbols-outlined">calendar_today</span>
                                <span data-ref="windowLabel">Last 1 Hour</span>
                            </button>

                            <div class="popover" data-ref="filterPopover">
                                <div class="filter-grid">
                                    <div class="filter-field">
                                        <label for="trace-query">Search</label>
                                        <input id="trace-query" data-ref="queryInput" type="search" placeholder="Trace ID, service, operation" />
                                    </div>
                                    <div class="filter-field">
                                        <label for="trace-status">Status</label>
                                        <select id="trace-status" data-ref="statusSelect"></select>
                                    </div>
                                    <div class="filter-field">
                                        <label for="trace-service">Service</label>
                                        <select id="trace-service" data-ref="serviceSelect"></select>
                                    </div>
                                    <div class="filter-field">
                                        <label for="trace-limit">List Size</label>
                                        <select id="trace-limit" data-ref="limitSelect">
                                            <option value="20">20 traces</option>
                                            <option value="40">40 traces</option>
                                            <option value="60">60 traces</option>
                                            <option value="80">80 traces</option>
                                        </select>
                                    </div>
                                </div>
                            </div>

                            <div class="popover" data-ref="windowPopover">
                                <div class="window-menu" data-ref="windowMenu"></div>
                            </div>
                        </div>
                    </div>

                    <div class="stats-grid" data-ref="statsGrid"></div>

                    <div class="content-grid">
                        <div>
                            <div class="recent-head">
                                <h2 class="recent-title">Recent Activity</h2>
                                <span class="recent-meta" data-ref="liveMeta">Live Updates</span>
                            </div>
                            <div class="trace-list" data-ref="traceList"></div>
                        </div>

                        <div class="timeline-card">
                            <div class="timeline-head">
                                <div>
                                    <h3 class="timeline-title">Trace Timeline</h3>
                                    <p class="timeline-id" data-ref="timelineId">ID: n/a</p>
                                </div>
                                <div class="headline-group">
                                    <div class="headline-stat">
                                        <p class="headline-label">Duration</p>
                                        <p class="headline-value" data-ref="durationValue">0ms</p>
                                    </div>
                                    <div class="headline-stat">
                                        <p class="headline-label">Spans</p>
                                        <p class="headline-value" data-ref="spanCountValue">0</p>
                                    </div>
                                </div>
                            </div>

                            <div class="scale" data-ref="scale"></div>
                            <div class="timeline-list" data-ref="timelineList"></div>

                            <div class="detail-card">
                                <h4 class="detail-title" data-ref="detailTitle">Span Details</h4>
                                <div class="detail-grid" data-ref="detailGrid"></div>
                            </div>
                        </div>
                    </div>
                </div>
            </section>
        `;
    }

    afterMount() {
        this._filterOpen = false;
        this._windowOpen = false;
        this._searchTimer = null;
        this._seenVersions = { summary: -1, list: -1, timeline: -1, detail: -1 };

        this.refs.filterBtn.addEventListener('click', (event) => {
            event.stopPropagation();
            this._filterOpen = !this._filterOpen;
            this._windowOpen = false;
            this.syncPopovers();
        });
        this.refs.windowBtn.addEventListener('click', (event) => {
            event.stopPropagation();
            this._windowOpen = !this._windowOpen;
            this._filterOpen = false;
            this.syncPopovers();
        });
        this.shadowRoot.addEventListener('click', (event) => {
            const path = event.composedPath();
            if (!path.includes(this.refs.filterBtn) && !path.includes(this.refs.filterPopover)) {
                this._filterOpen = false;
            }
            if (!path.includes(this.refs.windowBtn) && !path.includes(this.refs.windowPopover)) {
                this._windowOpen = false;
            }
            this.syncPopovers();
        });
        this.refs.queryInput.addEventListener('input', () => {
            clearTimeout(this._searchTimer);
            this._searchTimer = setTimeout(() => {
                this.emitRefresh({ query: this.refs.queryInput.value, traceId: null, spanId: null });
            }, REFRESH_DEBOUNCE_MS);
        });
        this.refs.statusSelect.addEventListener('change', () => {
            this.emitRefresh({ status: this.refs.statusSelect.value, traceId: null, spanId: null });
        });
        this.refs.serviceSelect.addEventListener('change', () => {
            this.emitRefresh({ service: this.refs.serviceSelect.value, traceId: null, spanId: null });
        });
        this.refs.limitSelect.addEventListener('change', () => {
            this.emitRefresh({ limit: Number(this.refs.limitSelect.value), traceId: null, spanId: null });
        });
        this.refs.windowMenu.addEventListener('click', (event) => {
            const item = event.target.closest('[data-window]');
            if (!item) return;
            this._windowOpen = false;
            this.syncPopovers();
            this.emitRefresh({ window: item.dataset.window, traceId: null, spanId: null });
        });
        this.refs.traceList.addEventListener('click', (event) => {
            const item = event.target.closest('[data-trace-id]');
            if (!item) return;
            this.emitRefresh({ traceId: item.dataset.traceId, spanId: null });
        });
        this.refs.timelineList.addEventListener('click', (event) => {
            const item = event.target.closest('[data-span-id]');
            if (!item) return;
            this.emitRefresh({ spanId: item.dataset.spanId });
        });
    }

    syncPopovers() {
        this.refs.filterPopover.classList.toggle('is-open', this._filterOpen);
        this.refs.windowPopover.classList.toggle('is-open', this._windowOpen);
    }

    emitRefresh(detail) {
        this.emit('keel:traces-refresh', detail);
    }

    render(appState) {
        this.ensureInitialized();
        const filters = appState.traceListSnapshot?.filters || appState.traceDashboard?.filters || appState.traceFilters;
        const activeWindow = filters.window || '1h';
        const windowMeta = WINDOWS.find((item) => item.key === activeWindow) || WINDOWS[1];

        this.refs.queryInput.value = filters.query || '';
        this.refs.statusSelect.innerHTML = STATUS_OPTIONS.map(([value, label]) => (
            `<option value="${value}" ${value === (filters.status || 'all') ? 'selected' : ''}>${escapeHtml(label)}</option>`
        )).join('');
        this.refs.serviceSelect.innerHTML = ['<option value="">All services</option>']
            .concat((filters.availableServices || []).map((service) => (
                `<option value="${escapeHtml(service)}" ${service === (filters.service || '') ? 'selected' : ''}>${escapeHtml(service)}</option>`
            )))
            .join('');
        this.refs.limitSelect.value = String(filters.limit || 40);
        this.refs.windowLabel.textContent = windowMeta.label;
        this.refs.windowMenu.innerHTML = WINDOWS.map((item) => `
            <button class="window-item ${item.key === activeWindow ? 'is-active' : ''}" type="button" data-window="${item.key}">
                ${escapeHtml(item.label)}
            </button>
        `).join('');
        this.syncPopovers();

        const versions = appState.traceSliceVersions || {};
        if (this._seenVersions.summary !== versions.summary) {
            this._seenVersions.summary = versions.summary;
            this.refs.statsGrid.innerHTML = renderStats(appState.traceSummary?.summary || {});
        }
        if (this._seenVersions.list !== versions.list) {
            this._seenVersions.list = versions.list;
            const traces = appState.traceListSnapshot?.traces || [];
            this.refs.liveMeta.textContent = traces.length ? 'Live Updates' : 'No activity';
            this.refs.traceList.innerHTML = traces.length
                ? traces.map((trace) => renderTraceItem(trace, appState.selectedTraceId)).join('')
                : '<div class="empty">No traces matched the current filters.</div>';
        }
        if (this._seenVersions.timeline !== versions.timeline) {
            this._seenVersions.timeline = versions.timeline;
            const timeline = appState.traceTimelineSnapshot?.timeline || null;
            this.refs.timelineId.textContent = timeline ? `ID: ${timeline.traceId}` : 'ID: n/a';
            this.refs.durationValue.textContent = timeline ? compactDuration(timeline.durationMs) : '0ms';
            this.refs.spanCountValue.textContent = timeline ? String(timeline.spanCount || 0) : '0';
            this.refs.scale.innerHTML = timeline && timeline.marks && timeline.marks.length
                ? timeline.marks.map((mark) => `<span>${escapeHtml(mark.label)}</span>`).join('')
                : '<span>0ms</span><span>10ms</span><span>20ms</span><span>30ms</span><span>40ms</span>';
            this.refs.timelineList.innerHTML = timeline && timeline.spans && timeline.spans.length
                ? timeline.spans.map((span) => renderTimelineRow(span, appState.selectedSpanId)).join('')
                : '<div class="empty">Select a trace to inspect its timeline.</div>';
        }
        if (this._seenVersions.detail !== versions.detail) {
            this._seenVersions.detail = versions.detail;
            const detail = appState.traceSpanDetailSnapshot?.spanDetail || null;
            this.refs.detailTitle.textContent = detail ? `Span Details: ${detail.service}` : 'Span Details';
            this.refs.detailGrid.innerHTML = detail ? renderDetailGrid(detail) : '<div class="empty">Select a span to inspect metadata.</div>';
        }
    }
}

function renderStats(summary) {
    const cards = [
        ['Total Traces', formatInteger(summary.totalTraces || 0), ''],
        ['P99 Latency', compactDuration(summary.p99LatencyMs || 0), 'secondary'],
        ['Error Rate', formatPercent(summary.errorRatePercent || 0), 'error'],
        ['Active Spans', formatCompactCount(summary.activeSpanCount || 0), 'warn']
    ];
    return cards.map(([label, value, cls]) => `
        <div class="stat-card ${cls}">
            <span class="stat-label">${escapeHtml(String(label))}</span>
            <span class="stat-value">${escapeHtml(String(value))}</span>
        </div>
    `).join('');
}

function renderTraceItem(trace, selectedTraceId) {
    const tone = trace.status === 'ERROR' ? 'error' : trace.status === 'ACTIVE' ? 'live' : trace.slow ? 'warn' : 'ok';
    const durationTone = trace.status === 'ERROR' ? 'error' : trace.slow ? 'warn' : '';
    return `
        <button class="trace-item ${trace.traceId === selectedTraceId ? 'is-selected' : ''} ${trace.status === 'ERROR' ? 'is-error' : ''}" type="button" data-trace-id="${escapeHtml(trace.traceId)}">
            <div class="trace-top">
                <span class="trace-id">${escapeHtml(shortTrace(trace.traceId))}</span>
                <span class="badge ${tone}">${escapeHtml(trace.badgeLabel || trace.status || 'OK')}</span>
            </div>
            <div class="trace-bottom" style="margin-top:10px; align-items:flex-end;">
                <div>
                    <h4 class="trace-op">${escapeHtml(trace.operation || trace.traceId)}</h4>
                    <p class="trace-meta">${escapeHtml(relativeTime(trace.startEpochMs))} • ${escapeHtml(trace.service || 'unknown')}</p>
                </div>
                <div class="trace-duration ${durationTone}">${escapeHtml(compactDuration(trace.durationMs || 0))}</div>
            </div>
        </button>
    `;
}

function renderTimelineRow(span, selectedSpanId) {
    const tone = spanTone(span);
    const resource = isResourceSpan(span);
    const icon = spanIcon(span);
    const label = `${span.service}: ${span.operation}`;
    return `
        <div class="timeline-row ${span.spanId === selectedSpanId ? 'is-selected' : ''}" data-span-id="${escapeHtml(span.spanId)}" style="--depth:${Number(span.depth || 0)};">
            <div class="timeline-meta">
                <div class="timeline-copy ${resource ? 'resource' : ''}">
                    ${icon ? `<span class="material-symbols-outlined" style="font-size:12px;">${escapeHtml(icon)}</span>` : `<span class="timeline-dot ${tone}"></span>`}
                    <span>${escapeHtml(label)}</span>
                </div>
                <span class="timeline-duration">${escapeHtml(compactDuration(span.durationMs || 0))}</span>
            </div>
            <div class="timeline-track">
                <span class="timeline-bar ${tone} ${resource ? 'resource' : ''}" style="left:${Number(span.leftPercent || 0)}%; width:${Number(span.widthPercent || 0)}%;"></span>
            </div>
        </div>
    `;
}

function renderDetailGrid(detail) {
    const entries = [
        ['Service Name', detail.service],
        ['Host', detail.host || 'n/a'],
        ['Protocol', detail.protocol || 'n/a'],
        ['Component', detail.component || 'n/a']
    ];
    return entries.map(([label, value]) => `
        <div>
            <p class="detail-kicker">${escapeHtml(label)}</p>
            <p class="detail-value">${escapeHtml(String(value ?? 'n/a'))}</p>
        </div>
    `).join('');
}

function spanTone(span) {
    if (isResourceSpan(span)) return 'warn';
    if (span.status === 'ERROR') return 'error';
    if ((span.durationMs || 0) >= 250) return 'warn';
    return 'ok';
}

function isResourceSpan(span) {
    const attrs = span.attributes || {};
    return Boolean(attrs['db.system'] || attrs['db.statement'] || attrs['cache.operation'] || attrs['cache.key'] || attrs['redis.command']);
}

function spanIcon(span) {
    const attrs = span.attributes || {};
    if (attrs['db.system']) return 'database';
    if (attrs['cache.operation'] || attrs['cache.key'] || attrs['redis.command']) return 'cached';
    return '';
}

function compactDuration(value) {
    const ms = Number(value || 0);
    if (ms >= 1000) return `${(ms / 1000).toFixed(ms >= 10_000 ? 0 : 1)}s`;
    return `${ms}ms`;
}

function relativeTime(epochMs) {
    if (!epochMs) return 'n/a';
    const diffMs = Math.max(Date.now() - epochMs, 0);
    const minutes = Math.floor(diffMs / 60000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
    const days = Math.floor(hours / 24);
    return `${days} day${days === 1 ? '' : 's'} ago`;
}

function shortTrace(value) {
    if (!value) return 'n/a';
    return value.length > 14 ? `${value.slice(0, 12)}...` : value;
}

function formatInteger(value) {
    return Number(value || 0).toLocaleString();
}

function formatCompactCount(value) {
    const count = Number(value || 0);
    if (count >= 1000) return `${(count / 1000).toFixed(count >= 10_000 ? 0 : 1)}k`;
    return String(count);
}

if (!customElements.get('keel-panel-traces')) {
    customElements.define('keel-panel-traces', PanelTraces);
}
