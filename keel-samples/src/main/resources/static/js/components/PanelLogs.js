import './shared/KeelHero.js';
import './LogDetail.js';

import { KeelElement } from './base/KeelElement.js';
import { logKey, selectedLog } from '../state.js';
import { renderHistogram } from '../render.js';
import { escapeHtml, formatTime } from '../utils.js';
import { healthTone } from '../formatters.js';

export class PanelLogs extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <div class="panel-root">
                <keel-hero data-ref="hero"></keel-hero>

                <div class="card" style="margin-bottom: 18px; flex-shrink: 0; padding-bottom: 0;">
                    <div class="filters">
                        <label>Query<input data-ref="queryInput" placeholder="trace id, message, throwable"></label>
                        <label>Level
                            <select data-ref="levelSelect">
                                <option value="">All</option>
                                <option value="DEBUG">DEBUG</option>
                                <option value="INFO">INFO</option>
                                <option value="WARN">WARN</option>
                                <option value="ERROR">ERROR</option>
                            </select>
                        </label>
                        <label>Source<input data-ref="sourceInput" placeholder="auth, kernel, inventory"></label>
                        <label>Window
                            <select data-ref="windowSelect">
                                <option value="15m">Last 15m</option>
                                <option value="1h">Last 1h</option>
                                <option value="6h">Last 6h</option>
                                <option value="24h">Last 24h</option>
                            </select>
                        </label>
                        <button data-ref="refreshBtn" type="button">Refresh Logs</button>
                    </div>
                    <div class="histogram" data-ref="histogram"></div>
                </div>

                <div class="layout-grid logs">
                    <div class="card">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Stream</div>
                                <h3>Structured Records</h3>
                            </div>
                            <span class="badge neutral mono" data-ref="pageChip">limit 80</span>
                        </div>
                        <div class="log-table scroll-panel" data-ref="logTable"></div>
                    </div>

                    <div class="card">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Expanded Record</div>
                                <h3 data-ref="detailTitle">No record selected</h3>
                            </div>
                            <span class="badge neutral" data-ref="detailLevel">idle</span>
                        </div>
                        <keel-log-detail data-ref="detail"></keel-log-detail>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.shadowRoot.addEventListener('click', (event) => {
            const button = event.target.closest('[data-download]');
            if (!button) return;
            this.emit(button.dataset.download === 'json' ? 'keel:download-logs-json' : 'keel:download-logs-text');
        });

        this.refs.refreshBtn.addEventListener('click', () => {
            this.emit('keel:logs-refresh', {
                query: this.refs.queryInput.value.trim(),
                level: this.refs.levelSelect.value,
                source: this.refs.sourceInput.value.trim(),
                window: this.refs.windowSelect.value
            });
        });

        this.refs.logTable.addEventListener('click', (event) => {
            const row = event.target.closest('[data-log-key]');
            if (!row) return;
            this.emit('keel:log-select', { logKey: row.dataset.logKey });
        });
    }

    render(appState) {
        this.ensureInitialized();
        this.refs.hero.render({
            label: 'Operational Intelligence',
            title: 'Log Explorer',
            metaHtml: `
                <span class="status-pill">${appState.logs.total || 0} entries</span>
                <button class="action-btn" type="button" data-download="json">Download JSON</button>
                <button class="action-btn" type="button" data-download="text">Download Raw Text</button>
            `
        });


        this.refs.queryInput.value = appState.logFilters.query;
        this.refs.levelSelect.value = appState.logFilters.level;
        this.refs.sourceInput.value = appState.logFilters.source;
        this.refs.windowSelect.value = appState.logFilters.window;
        this.refs.pageChip.textContent = `limit ${appState.logs.limit || 80}`;
        const logItems = appState.logs.items || [];
        this.refs.histogram.innerHTML = renderHistogram(logItems);

        const selected = selectedLog();
        this.refs.logTable.innerHTML = logItems.length ? logItems.map((item) => `
            <div class="table-row log-row ${selected && logKey(item) === logKey(selected) ? 'is-selected' : ''}" data-log-key="${escapeHtml(logKey(item))}">
                <div class="mono muted">${formatTime(item.timestamp)}</div>
                <div><span class="badge ${healthTone(item.level)}">${escapeHtml(item.level)}</span></div>
                <div>
                    <div class="row-title"><strong>${escapeHtml(item.message)}</strong></div>
                    <div class="row-subtitle">${escapeHtml(item.source)}${item.traceId ? ` · ${escapeHtml(item.traceId)}` : ''}</div>
                </div>
            </div>
        `).join('') : '<div class="empty">No logs matched the current filters.</div>';

        this.refs.detailTitle.textContent = selected ? selected.source : 'No record selected';
        this.refs.detailLevel.className = `badge ${healthTone(selected && selected.level)}`;
        this.refs.detailLevel.textContent = selected ? selected.level : 'idle';
        this.refs.detail.render({ selected });
    }
}

if (!customElements.get('keel-panel-logs')) {
    customElements.define('keel-panel-logs', PanelLogs);
}
