import { KeelElement } from './base/KeelElement.js';
import { REFRESH_DEBOUNCE_MS } from '../config.js';
import { logKey, selectedLog } from '../state.js';
import { escapeHtml, formatDate } from '../utils.js';

const WINDOW_LABELS = {
    '15m': 'Last 15 Minutes',
    '1h': 'Last 1 Hour',
    '6h': 'Last 6 Hours',
    '24h': 'Last 24 Hours'
};

const WINDOW_ORDER = ['15m', '1h', '6h', '24h'];
const PAGE_SIZE_OPTIONS = [20, 50, 100];

function histogramTone(bucket = {}) {
    const levels = bucket.levelCounts || {};
    if ((levels.ERROR || 0) > 0) return 'is-error';
    if ((levels.WARN || 0) > 0) return 'is-warn';
    if ((levels.INFO || 0) > 0) return 'is-info';
    return 'is-neutral';
}

function nextWindow(current) {
    const index = WINDOW_ORDER.indexOf(current);
    return WINDOW_ORDER[(index + 1 + WINDOW_ORDER.length) % WINDOW_ORDER.length] || '1h';
}

function prettyLevel(level) {
    const normalized = String(level || '').toUpperCase();
    if (!normalized) return 'All';
    return normalized.charAt(0) + normalized.slice(1).toLowerCase();
}

function detailJson(item) {
    const json = {};
    if (item.traceId) json.trace_id = item.traceId;
    if (item.spanId) json.span_id = item.spanId;
    if (item.pluginId) json.plugin_id = item.pluginId;
    if (item.service) json.service = item.service;
    if (item.cluster) json.cluster = item.cluster;
    if (item.instance) json.instance = item.instance;
    if (item.payload) json.payload = item.payload;
    if (item.attributes && Object.keys(item.attributes).length) json.attributes = item.attributes;
    if (item.throwable) json.throwable = item.throwable;
    if (item.meta) json.meta = item.meta;
    return escapeHtml(JSON.stringify(json, null, 2));
}

function paginationModel(page) {
    const current = page.page || 1;
    const totalPages = page.totalPages || 0;
    if (totalPages <= 0) return [];
    if (totalPages === 1) return [1];
    if (totalPages <= 3) return Array.from({ length: totalPages }, (_, index) => index + 1);
    if (current <= 2) return [1, 2, 3];
    if (current >= totalPages - 1) return [totalPages - 2, totalPages - 1, totalPages];
    return [current - 1, current, current + 1];
}

function formatCount(value) {
    return new Intl.NumberFormat('en-US').format(Number(value || 0));
}

function histogramTickLabel(epochMs) {
    if (!epochMs) return 'n/a';
    return new Date(epochMs).toLocaleTimeString([], {
        hour: '2-digit',
        minute: '2-digit',
        hour12: true
    });
}

function histogramTicks(histogram) {
    const start = Number(histogram.windowStartEpochMs || 0);
    const end = Number(histogram.windowEndEpochMs || start);
    const safeEnd = Math.max(end, start + 1);
    return Array.from({ length: 5 }, (_, index) => {
        const ratio = index / 4;
        return start + ((safeEnd - start) * ratio);
    });
}

export class PanelLogs extends KeelElement {
    hostStyles() {
        return 'height: 100%; min-height: 0;';
    }

    template() {
        return `
            <style>
                .panel-root {
                    display: flex;
                    flex-direction: column;
                    min-height: 100%;
                }
                .logs-explorer {
                    min-height: 100%;
                    overflow-y: auto;
                    overflow-x: hidden;
                    padding: 32px 48px 40px;
                    gap: 32px;
                    background: transparent;
                }
                .logs-hero {
                    display: flex;
                    justify-content: space-between;
                    align-items: flex-end;
                    gap: 24px;
                    flex-wrap: nowrap;
                }
                .logs-hero-copy {
                    display: grid;
                    gap: 0;
                    flex: 1 1 420px;
                    min-width: 0;
                }
                .logs-title {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 42px;
                    line-height: 1;
                    color: var(--ink);
                    font-weight: 500;
                }
                .logs-subtitle {
                    margin: 12px 0 0;
                    max-width: 560px;
                    font-size: 14px;
                    line-height: 1.6;
                    color: var(--muted);
                }
                .logs-hero-actions {
                    display: flex;
                    align-items: flex-end;
                    gap: 12px;
                    flex-wrap: nowrap;
                    justify-content: flex-end;
                    flex: 0 1 auto;
                    margin-left: auto;
                    min-width: 0;
                    white-space: nowrap;
                }
                .logs-search {
                    position: relative;
                    display: flex;
                    align-items: center;
                    flex: 0 0 320px;
                    min-width: 0;
                }
                .logs-search-icon,
                .logs-window-icon,
                .logs-col-meta,
                .logs-page-btn {
                    font-family: "Material Symbols Outlined", sans-serif;
                    font-weight: 400;
                    font-style: normal;
                    line-height: 1;
                    font-feature-settings: "liga";
                    -webkit-font-smoothing: antialiased;
                }
                .logs-search-icon {
                    position: absolute;
                    left: 16px;
                    top: 50%;
                    transform: translateY(-50%);
                    color: var(--muted);
                    font-size: 16px;
                    pointer-events: none;
                }
                .logs-search input {
                    width: 100%;
                    min-width: 0;
                    border: 0;
                    border-radius: 18px;
                    padding: 12px 18px 12px 42px;
                    background: #ffffff;
                    box-shadow: 0 1px 0 rgba(17, 24, 39, 0.04), 0 10px 28px rgba(26, 28, 26, 0.04);
                    color: var(--ink);
                    font-size: 14px;
                    outline: none;
                }
                .logs-search input:focus {
                    box-shadow: inset 0 0 0 2px rgba(17, 24, 39, 0.12);
                }
                .logs-window-btn,
                .logs-page-size-btn {
                    border: 0;
                    border-radius: 18px;
                    background: #ffffff;
                    box-shadow: 0 1px 0 rgba(17, 24, 39, 0.04), 0 10px 28px rgba(26, 28, 26, 0.04);
                    color: var(--ink);
                    padding: 12px 18px;
                    display: inline-flex;
                    align-items: center;
                    gap: 10px;
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.16em;
                    text-transform: uppercase;
                    cursor: pointer;
                }
                .logs-card {
                    background: var(--panel-strong);
                    border: 1px solid rgba(117, 119, 124, 0.12);
                    border-radius: 24px;
                    box-shadow: 0 12px 40px rgba(26, 28, 26, 0.03);
                    overflow: hidden;
                }
                .logs-histogram-card {
                    padding: 32px;
                    flex-shrink: 0;
                }
                .logs-list-card {
                    flex-shrink: 0;
                }
                .logs-histogram-head {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    gap: 16px;
                    margin-bottom: 24px;
                }
                .logs-histogram-head h3,
                .logs-footer-copy,
                .logs-table-head {
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.2em;
                    text-transform: uppercase;
                    color: var(--muted);
                }
                .logs-histogram-legend {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }
                .legend-dot {
                    width: 10px;
                    height: 10px;
                    border-radius: 999px;
                    display: inline-block;
                }
                .legend-dot.is-info,
                .logs-bar.is-info {
                    background: #006c49;
                }
                .legend-dot.is-warn,
                .logs-bar.is-warn {
                    background: #ffb95f;
                }
                .legend-dot.is-error,
                .logs-bar.is-error {
                    background: #ba1a1a;
                }
                .legend-dot.is-neutral,
                .logs-bar.is-neutral {
                    background: var(--color-surface-container-high, rgba(117, 119, 124, 0.18));
                }
                .logs-histogram-bars {
                    height: 128px;
                    display: grid;
                    gap: 6px;
                    align-items: end;
                    min-width: 0;
                    padding: 8px 0 0;
                }
                .logs-bar-wrap {
                    height: 100%;
                    display: flex;
                    align-items: end;
                    min-width: 0;
                }
                .logs-bar {
                    width: 100%;
                    border-radius: 4px 4px 0 0;
                    min-height: 0;
                    transition: height 180ms ease;
                }
                .logs-histogram-axis {
                    margin-top: 16px;
                    display: flex;
                    justify-content: space-between;
                    gap: 12px;
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    color: rgba(68, 71, 75, 0.72);
                }
                .logs-table-head,
                .logs-row-main {
                    display: grid;
                    grid-template-columns: 140px 80px minmax(0, 1fr) 40px;
                    gap: 16px;
                    align-items: center;
                }
                .logs-table-head {
                    padding: 16px 32px;
                    background: var(--color-surface-container-low, rgba(17, 24, 39, 0.04));
                    border-bottom: 1px solid rgba(117, 119, 124, 0.15);
                }
                .logs-table-head .is-right {
                    text-align: right;
                }
                .logs-table-body {
                    display: block;
                    min-width: 0;
                }
                .logs-row {
                    border-bottom: 1px solid rgba(117, 119, 124, 0.1);
                    cursor: pointer;
                    transition: background 180ms ease;
                }
                .logs-row:hover {
                    background: rgba(255, 255, 255, 0.64);
                }
                .logs-row.is-expanded {
                    background: rgba(255, 255, 255, 0.78);
                }
                .logs-row.is-expanded .logs-row-main {
                    border-bottom: 1px solid rgba(117, 119, 124, 0.1);
                }
                .logs-row.tone-error.is-expanded .logs-row-main {
                    background: rgba(255, 218, 214, 0.3);
                }
                .logs-row-main {
                    padding: 18px 32px;
                }
                .logs-col-timestamp {
                    font-size: 12px;
                    color: var(--muted);
                    font-family: var(--font-mono);
                }
                .logs-level-pill {
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    min-width: 62px;
                    padding: 6px 10px;
                    border-radius: 999px;
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.12em;
                    text-transform: uppercase;
                }
                .logs-level-pill.tone-error {
                    background: var(--red-soft);
                    color: var(--red);
                }
                .logs-level-pill.tone-warn {
                    background: var(--amber-soft);
                    color: var(--amber);
                }
                .logs-level-pill.tone-info,
                .logs-level-pill.tone-debug,
                .logs-level-pill.tone-neutral {
                    background: var(--green-soft);
                    color: var(--green);
                }
                .logs-col-message {
                    min-width: 0;
                    font-size: 14px;
                    font-weight: 600;
                    color: var(--ink);
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .logs-col-meta {
                    color: var(--muted);
                    font-size: 20px;
                    text-align: right;
                }
                .logs-row.is-expanded .logs-col-meta {
                    color: var(--ink);
                }
                .logs-row-detail {
                    padding: 0 32px 24px;
                }
                .logs-row-detail pre {
                    margin: 0;
                    padding: 16px 18px;
                    border-radius: 18px;
                    background: #0f172a;
                    color: #dbeafe;
                    overflow: auto;
                    white-space: pre-wrap;
                    word-break: break-word;
                    font-family: var(--font-mono);
                    font-size: 11px;
                    line-height: 1.7;
                }
                .logs-footer {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    gap: 16px;
                    padding: 18px 32px 22px;
                    background: rgba(255, 255, 255, 0.4);
                }
                .logs-pagination {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }
                .logs-page-btn {
                    min-width: 38px;
                    height: 38px;
                    border: 0;
                    border-radius: 12px;
                    background: rgba(17, 24, 39, 0.04);
                    color: var(--ink);
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    cursor: pointer;
                    font-size: 18px;
                }
                .logs-page-btn:hover:not(:disabled) {
                    background: rgba(17, 24, 39, 0.08);
                }
                .logs-page-btn.is-active {
                    background: var(--navy);
                    color: #fff;
                }
                .logs-page-btn:disabled {
                    opacity: 0.36;
                    cursor: not-allowed;
                }
                .empty,
                .logs-empty {
                    display: grid;
                    place-items: center;
                    min-height: 120px;
                    padding: 24px;
                    color: var(--muted);
                    text-align: center;
                    font-size: 13px;
                    line-height: 1.6;
                }
                .logs-empty {
                    min-height: 180px;
                }
                @media (max-width: 900px) {
                    .logs-explorer {
                        padding: 24px 20px 32px;
                    }
                    .logs-hero,
                    .logs-hero-actions,
                    .logs-footer {
                        align-items: stretch;
                    }
                    .logs-hero {
                        flex-wrap: wrap;
                    }
                    .logs-hero-actions {
                        flex-wrap: wrap;
                        white-space: normal;
                    }
                    .logs-search {
                        flex: 1 1 100%;
                        min-width: 0;
                    }
                    .logs-table-head,
                    .logs-row-main {
                        grid-template-columns: 1fr;
                        gap: 10px;
                    }
                    .logs-row-main,
                    .logs-table-head,
                    .logs-footer,
                    .logs-histogram-card {
                        padding-left: 20px;
                        padding-right: 20px;
                    }
                }
            </style>
            <div class="panel-root logs-explorer">
                <header class="logs-hero">
                    <div class="logs-hero-copy">
                        <h1 class="logs-title">Log Explorer</h1>
                        <p class="logs-subtitle">Structured runtime events, searchable in real time.</p>
                    </div>
                    <div class="logs-hero-actions">
                        <label class="logs-search">
                            <span class="logs-search-icon" aria-hidden="true">search</span>
                            <input data-ref="queryInput" placeholder="Filter log stream..." type="text">
                        </label>
                        <button class="logs-window-btn" data-ref="windowBtn" type="button">
                            <span class="logs-window-icon" aria-hidden="true">calendar_today</span>
                            <span data-ref="windowLabel">Last 1 Hour</span>
                        </button>
                        <button class="logs-page-size-btn" data-ref="pageSizeBtn" type="button">
                            <span>Rows</span>
                            <span data-ref="pageSizeLabel">20</span>
                        </button>
                    </div>
                </header>

                <section class="logs-card logs-histogram-card">
                    <div class="logs-histogram-head">
                        <h3 data-ref="histogramTitle">Event Volume (0 total)</h3>
                        <div class="logs-histogram-legend">
                            <span class="legend-dot is-info"></span>
                            <span class="legend-dot is-warn"></span>
                            <span class="legend-dot is-error"></span>
                        </div>
                    </div>
                    <div class="logs-histogram-bars" data-ref="histogram"></div>
                    <div class="logs-histogram-axis" data-ref="histogramAxis"></div>
                </section>

                <section class="logs-card logs-list-card">
                    <div class="logs-table-head">
                        <div>Timestamp</div>
                        <div>Level</div>
                        <div>Message</div>
                        <div class="is-right">Meta</div>
                    </div>
                    <div class="logs-table-body" data-ref="logTable"></div>
                    <div class="logs-footer">
                        <span class="logs-footer-copy" data-ref="footerCopy">Showing 0 of 0 entries</span>
                        <div class="logs-pagination" data-ref="pagination"></div>
                    </div>
                </section>
            </div>
        `;
    }

    afterMount() {
        this._queryTimer = null;
        this._lastQueryValue = '';

        this.refs.queryInput.addEventListener('input', () => {
            const nextValue = this.refs.queryInput.value.trim();
            if (nextValue === this._lastQueryValue) return;
            clearTimeout(this._queryTimer);
            this._queryTimer = setTimeout(() => {
                this.emitRefresh({ page: 1 });
            }, REFRESH_DEBOUNCE_MS);
        });

        this.refs.queryInput.addEventListener('keydown', (event) => {
            if (event.key === 'Escape') {
                this.refs.queryInput.value = '';
                clearTimeout(this._queryTimer);
                this.emitRefresh({ page: 1 });
                return;
            }
            if (event.key !== 'Enter') return;
            clearTimeout(this._queryTimer);
            this.emitRefresh({ page: 1 });
        });

        this.refs.windowBtn.addEventListener('click', () => {
            this.emitRefresh({
                page: 1,
                window: nextWindow(this.refs.windowBtn.dataset.window || '1h')
            });
        });

        this.refs.pageSizeBtn.addEventListener('click', () => {
            const currentSize = Number(this.refs.pageSizeBtn.dataset.pageSize || PAGE_SIZE_OPTIONS[0]);
            const index = PAGE_SIZE_OPTIONS.indexOf(currentSize);
            const nextSize = PAGE_SIZE_OPTIONS[(index + 1 + PAGE_SIZE_OPTIONS.length) % PAGE_SIZE_OPTIONS.length] || PAGE_SIZE_OPTIONS[0];
            this.emitRefresh({
                page: 1,
                pageSize: nextSize
            });
        });

        this.refs.logTable.addEventListener('click', (event) => {
            const row = event.target.closest('[data-log-key]');
            if (!row) return;
            this.emit('keel:log-select', { logKey: row.dataset.logKey });
        });

        this.refs.pagination.addEventListener('click', (event) => {
            const button = event.target.closest('[data-page]');
            if (!button || button.disabled) return;
            this.emitRefresh({ page: Number(button.dataset.page || 1) });
        });
    }

    emitRefresh(overrides = {}) {
        const query = this.refs.queryInput.value.trim();
        this._lastQueryValue = query;
        this.emit('keel:logs-refresh', {
            query,
            level: overrides.level || '',
            window: overrides.window || this.refs.windowBtn.dataset.window || '1h',
            page: overrides.page || 1,
            pageSize: overrides.pageSize || Number(this.refs.pageSizeBtn?.dataset.pageSize || PAGE_SIZE_OPTIONS[0])
        });
    }

    render(appState) {
        this.ensureInitialized();
        const logs = appState.logs || {};
        const page = logs.page || { items: [], total: 0, page: 1, totalPages: 0, hasPrev: false, hasNext: false, pageSize: PAGE_SIZE_OPTIONS[0] };
        const summary = logs.summary || { totalMatched: page.total || 0, showingCount: page.items?.length || 0, activeWindow: appState.logFilters.window };
        const histogram = logs.histogram || { buckets: [] };
        const items = page.items || [];
        const selected = selectedLog();
        const tallest = Math.max(...(histogram.buckets || []).map((bucket) => bucket.totalCount || 0), 1);
        const totalMatched = Number(summary.totalMatched || page.total || 0);
        const pageSize = Number(page.pageSize || appState.logFilters.pageSize || items.length || 1);
        const totalPages = Number(page.totalPages || (totalMatched ? Math.ceil(totalMatched / Math.max(pageSize, 1)) : 0));
        const currentPage = totalPages > 0 ? Math.min(Math.max(Number(page.page || 1), 1), totalPages) : 1;
        const showingCount = Number(items.length || summary.showingCount || 0);
        const hasPrev = totalPages > 0 && currentPage > 1;
        const hasNext = totalPages > 0 && currentPage < totalPages;
        const pageNumbers = paginationModel({ page: currentPage, totalPages });

        this.refs.queryInput.value = appState.logFilters.query || '';
        this._lastQueryValue = this.refs.queryInput.value.trim();
        this.refs.windowBtn.dataset.window = appState.logFilters.window || '1h';
        this.refs.windowLabel.textContent = WINDOW_LABELS[appState.logFilters.window] || WINDOW_LABELS['1h'];
        this.refs.pageSizeBtn.dataset.pageSize = String(pageSize);
        this.refs.pageSizeLabel.textContent = String(pageSize);
        this.refs.histogramTitle.textContent = `Event Volume (${formatCount(totalMatched)} total)`;
        this.refs.histogram.style.gridTemplateColumns = `repeat(${Math.max((histogram.buckets || []).length, 1)}, minmax(0, 1fr))`;

        this.refs.histogram.innerHTML = (histogram.buckets || []).length ? histogram.buckets.map((bucket) => {
            const height = bucket.totalCount ? Math.max(((bucket.totalCount || 0) / tallest) * 100, 12) : 0;
            return `
                <div class="logs-bar-wrap">
                    <div class="logs-bar ${histogramTone(bucket)}" style="height:${height}%;" title="${escapeHtml(String(bucket.totalCount || 0))} logs"></div>
                </div>
            `;
        }).join('') : '<div class="empty">No log rows to chart.</div>';

        this.refs.histogramAxis.innerHTML = histogramTicks(histogram)
            .map((time) => `<span>${escapeHtml(histogramTickLabel(time))}</span>`)
            .join('');

        this.refs.logTable.innerHTML = items.length ? items.map((item) => {
            const expanded = selected && logKey(item) === logKey(selected);
            return `
                <article class="logs-row ${expanded ? 'is-expanded' : ''} ${item.level === 'ERROR' ? 'tone-error' : item.level === 'WARN' ? 'tone-warn' : ''}" data-log-key="${escapeHtml(logKey(item))}">
                    <div class="logs-row-main">
                        <div class="logs-col-timestamp">${escapeHtml(formatDate(item.timestamp))}</div>
                        <div>
                            <span class="logs-level-pill tone-${escapeHtml(String(item.level || '').toLowerCase())}">${escapeHtml(prettyLevel(item.level))}</span>
                        </div>
                        <div class="logs-col-message">${escapeHtml(item.message)}</div>
                        <div class="logs-col-meta">${expanded ? 'unfold_less' : 'unfold_more'}</div>
                    </div>
                    ${expanded ? `
                        <div class="logs-row-detail">
                            <pre>${detailJson(item)}</pre>
                        </div>
                    ` : ''}
                </article>
            `;
        }).join('') : '<div class="empty logs-empty">No logs matched the current filters.</div>';

        this.refs.footerCopy.textContent = `SHOWING ${formatCount(showingCount)} OF ${formatCount(totalMatched)} ENTRIES`;
        this.refs.pagination.innerHTML = `
            <button class="logs-page-btn" type="button" aria-label="Previous page" data-page="${Math.max(currentPage - 1, 1)}" ${!hasPrev ? 'disabled' : ''}>chevron_left</button>
            ${pageNumbers.map((pageNumber) => `
                <button class="logs-page-btn ${pageNumber === currentPage ? 'is-active' : ''}" type="button" aria-label="Page ${pageNumber}" data-page="${pageNumber}">
                    ${pageNumber}
                </button>
            `).join('')}
            <button class="logs-page-btn" type="button" aria-label="Next page" data-page="${Math.min(currentPage + 1, totalPages || 1)}" ${!hasNext ? 'disabled' : ''}>chevron_right</button>
        `;
    }
}

if (!customElements.get('keel-panel-logs')) {
    customElements.define('keel-panel-logs', PanelLogs);
}
