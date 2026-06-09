import { KeelElement } from './base/KeelElement.js';
import { requestJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

/**
 * Detail view: per-request records with full token + cost breakdown.
 * Auto-refreshes silently every 15s.
 */
export class PanelUsage extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .layout { display: flex; flex-direction: column; gap: 24px; height: 100%; }
                .toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
                .toolbar .field { display: flex; flex-direction: column; gap: 4px; }
                .toolbar label {
                    font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted);
                }
                .toolbar input, .toolbar select {
                    padding: 8px 10px; border: 2px solid var(--ink);
                    background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 11px; min-width: 160px;
                }
                .toolbar input:focus, .toolbar select:focus { outline: none; box-shadow: var(--shadow-sm); }
                .summary {
                    display: grid; grid-template-columns: repeat(7, 1fr); gap: 2px;
                    background: var(--ink); border: 2px solid var(--ink);
                }
                .summary .cell {
                    background: var(--paper); padding: 14px 12px;
                    font-family: var(--font-mono);
                }
                .summary .label {
                    font-size: 9px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase;
                    color: var(--muted);
                }
                .summary .val {
                    margin-top: 4px; font-family: var(--font-headline);
                    font-size: 18px; line-height: 1; letter-spacing: -0.03em;
                    font-feature-settings: 'tnum';
                }
                .table-card {
                    background: var(--panel-strong); border: 2px solid var(--ink);
                    overflow: hidden; flex: 1; min-height: 0;
                }
                .table-wrap { max-height: 70vh; overflow: auto; }
                .model-route { display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; }
                .model-route code { font-size: 10px; }
                .model-route .arrow { color: var(--muted); font-family: var(--font-mono); font-size: 10px; font-weight: 800; }
                .empty { text-align: center; color: var(--muted); padding: 30px; font-family: var(--font-mono); font-size: 12px; }
                @media (max-width: 1100px) {
                    .summary { grid-template-columns: repeat(3, 1fr); }
                }
            </style>
            <div class="layout" data-ref="root">
                <div class="toolbar">
                    <div class="field">
                        <label>Filter model</label>
                        <input data-ref="modelFilter" placeholder="e.g. claude-sonnet-4" />
                    </div>
                    <div class="field">
                        <label>Status</label>
                        <select data-ref="statusFilter">
                            <option value="">All</option>
                            <option value="ok">OK (2xx/3xx)</option>
                            <option value="err">Errors (4xx/5xx)</option>
                        </select>
                    </div>
                    <div class="field">
                        <label>Limit</label>
                        <select data-ref="limitFilter">
                            <option>50</option>
                            <option selected>200</option>
                        </select>
                    </div>
                </div>
                <div class="summary" data-ref="summary"></div>
                <div class="table-card">
                    <div class="table-wrap" data-ref="tableWrap"></div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._records = [];
        this._hasRendered = false;
        this.refs.modelFilter.addEventListener('input', () => this._renderTable(true));
        this.refs.statusFilter.addEventListener('change', () => this._renderTable(true));
        this.refs.limitFilter.addEventListener('change', () => this.refresh());
    }

    async refresh() {
        try {
            const limit = parseInt(this.refs.limitFilter.value) || 200;
            const data = await requestJson(`${API.token}/admin/usage/records?limit=${limit}`);
            this._records = data.records || [];
            this._renderTable(this._hasRendered);
            this._hasRendered = true;
        } catch (e) {
            this.refs.tableWrap.innerHTML = `<div class="empty">Error: ${e.message}</div>`;
        }
    }

    _filtered() {
        const modelQ = (this.refs.modelFilter.value || '').toLowerCase();
        const status = this.refs.statusFilter.value;
        return this._records.filter(r => {
            if (modelQ && !(r.model || '').toLowerCase().includes(modelQ)) return false;
            if (status === 'ok' && r.status >= 400) return false;
            if (status === 'err' && r.status < 400) return false;
            return true;
        });
    }

    _renderTable(silent) {
        const filtered = this._filtered();
        // Summary
        const sums = filtered.reduce((acc, r) => {
            const u = r.usage || {};
            const c = r.cost || {};
            acc.input += u.promptTokens || 0;
            acc.output += u.completionTokens || 0;
            acc.cr += u.cacheReadInputTokens || 0;
            acc.cw += u.cacheCreationInputTokens || 0;
            acc.reason += u.reasoningTokens || 0;
            acc.cost += c.totalCostUsd || 0;
            if (c.cacheHitRate != null) { acc.hitN += 1; acc.hitSum += c.cacheHitRate; }
            return acc;
        }, { input: 0, output: 0, cr: 0, cw: 0, reason: 0, cost: 0, hitN: 0, hitSum: 0 });
        const hit = sums.hitN > 0 ? (sums.hitSum / sums.hitN * 100).toFixed(1) + '%' : '—';
        this.refs.summary.innerHTML = [
            this._sumCell('Records', filtered.length.toString()),
            this._sumCell('Input', sums.input.toLocaleString()),
            this._sumCell('Output', sums.output.toLocaleString()),
            this._sumCell('Cache Read', sums.cr.toLocaleString()),
            this._sumCell('Cache Write', sums.cw.toLocaleString()),
            this._sumCell('Reasoning', sums.reason.toLocaleString()),
            this._sumCell('Total Cost', '$' + sums.cost.toFixed(4)),
        ].join('');

        // Table
        if (filtered.length === 0) {
            this.refs.tableWrap.innerHTML = '<div class="empty">// NO RECORDS MATCH FILTERS</div>';
            return;
        }
        const headers = ['Time', 'Model', 'In', 'Out', 'CR', 'CW', 'CP', 'Reason', 'In$', 'Out$', 'CW$', 'CR$', 'Total$', 'Hit', 'Status', 'Latency'];
        const rows = filtered.slice(0, 200).map(r => {
            const u = r.usage || {};
            const c = r.cost || {};
            return [
                `<span style="font-size:10px;">${(r.createdAt || '').slice(0, 19)}</span>`,
                this._modelCell(r.model),
                this._fmt(u.promptTokens),
                this._fmt(u.completionTokens),
                this._fmt(u.cacheReadInputTokens),
                this._fmt(u.cacheCreationInputTokens),
                this._fmt(u.cachedPromptTokens),
                this._fmt(u.reasoningTokens),
                `$${(c.inputCostUsd || 0).toFixed(5)}`,
                `$${(c.outputCostUsd || 0).toFixed(5)}`,
                `$${(c.cacheWriteCostUsd || 0).toFixed(5)}`,
                `$${(c.cacheReadCostUsd || 0).toFixed(5)}`,
                `$${(c.totalCostUsd || 0).toFixed(5)}`,
                c.cacheHitRate != null ? `${(c.cacheHitRate * 100).toFixed(0)}%` : '—',
                r.status >= 400
                    ? `<span style="color:var(--red);font-weight:800;">${r.status}</span>`
                    : `<span style="color:var(--green);font-weight:800;">${r.status || 200}</span>`,
                `${r.latencyMs || 0}ms`
            ];
        });
        // Reuse keel-data-table for in-place updates
        let table = this.refs.tableWrap.querySelector('keel-data-table');
        if (!table) {
            table = document.createElement('keel-data-table');
            this.refs.tableWrap.innerHTML = '';
            this.refs.tableWrap.appendChild(table);
        }
        table.render({ silent, headers, rows, emptyHtml: '<div class="empty">// NO DATA</div>' });
    }

    _sumCell(label, val) {
        return `<div class="cell"><div class="label">${label}</div><div class="val">${val}</div></div>`;
    }

    _fmt(n) {
        if (n == null) return '0';
        if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
        return String(n);
    }

    _modelCell(model) {
        const text = String(model || '');
        const parts = text.split(' -> ');
        if (parts.length < 2) return `<code>${escapeHtml(text)}</code>`;
        const alias = parts.shift();
        const real = parts.join(' -> ');
        return `<span class="model-route"><code>${escapeHtml(alias)}</code><span class="arrow">-></span><code>${escapeHtml(real)}</code></span>`;
    }
}

customElements.define('ai-panel-usage', PanelUsage);
