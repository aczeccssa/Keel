import { KeelElement } from './base/KeelElement.js';
import { getJson, postJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml, formatDate, formatNumber } from '../utils.js';
import './shared/KeelDataTable.js';

export class PanelBilling extends KeelElement {
    constructor() { super(); this._hasLedgerRendered = false; this._hasUsageRendered = false; }
    hostStyles() { return ''; }

    template() {
        return `
            <style>
                .layout { display:grid; grid-template-columns:1fr 1fr; gap:18px; }
                .card { border:2px solid var(--ink); background:var(--paper); box-shadow:var(--shadow-sm); }
                .card h3 { padding:14px 18px; margin:0; background:var(--ink); color:var(--paper); font-family:var(--font-display); font-size:14px; text-transform:uppercase; letter-spacing:-0.03em; }
                .card-body { padding:16px; }
                .field label { display:block; margin-bottom:4px; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.12em; text-transform:uppercase; color:var(--muted); }
                .field input { width:100%; padding:10px 12px; border:2px solid var(--ink); font-family:var(--font-mono); font-size:13px; background:var(--paper); }
                .btn { padding:10px 16px; border:2px solid var(--ink); background:var(--ink); color:var(--paper); font-family:var(--font-mono); font-size:10px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; cursor:pointer; }
                .btn:hover { background:var(--teal); border-color:var(--teal); }
                .table-wrap { max-height:360px; overflow-y:auto; }
                table { width:100%; border-collapse:collapse; font-family:var(--font-mono); font-size:10px; }
                th,td { padding:8px 10px; text-align:left; border-bottom:1px solid var(--bg, #e5e0d8); }
                th { font-size:9px; font-weight:800; text-transform:uppercase; letter-spacing:0.1em; color:var(--muted); background:var(--bg, #ebe9e3); position:sticky; top:0; }
                .delta-pos { color:var(--green); font-weight:800; }
                .delta-neg { color:var(--red); font-weight:800; }
                .empty { padding:30px; text-align:center; font-family:var(--font-mono); font-size:12px; font-weight:800; color:var(--muted); text-transform:uppercase; }
                .redeem-result { display:none; padding:10px; background:var(--green); color:var(--paper); font-family:var(--font-mono); font-size:11px; font-weight:800; margin-top:8px; }
                .redeem-result.show { display:block; }
                .redeem-error { display:none; padding:10px; background:var(--red); color:var(--paper); font-family:var(--font-mono); font-size:11px; font-weight:800; margin-top:8px; }
                .redeem-error.show { display:block; }
                .filter-row { display:flex; gap:6px; margin-bottom:10px; }
                .chip { padding:4px 10px; border:1px solid var(--ink); font-family:var(--font-mono); font-size:9px; font-weight:800; text-transform:uppercase; cursor:pointer; background:var(--paper); }
                .chip.active { background:var(--ink); color:var(--paper); }
                .full { grid-column:1/-1; }
                @media (max-width:700px) { .layout { grid-template-columns:1fr; } }
            </style>
            <div class="layout">
                <div class="card">
                    <h3>Credit Ledger</h3>
                    <div class="card-body">
                        <div class="filter-row" data-ref="ledgerFilter"></div>
                        <div class="table-wrap" data-ref="ledgerTable"><div class="empty">Loading...</div></div>
                    </div>
                </div>
                <div class="card">
                    <h3>Redeem Code</h3>
                    <div class="card-body">
                        <div class="field"><label>Redemption Code</label></div>
                        <div style="display:flex;gap:10px;">
                            <input data-ref="redeemInput" placeholder="Enter code..." style="flex:1;" />
                            <button class="btn" data-ref="redeemBtn">Redeem</button>
                        </div>
                        <div class="redeem-result" data-ref="redeemOk"></div>
                        <div class="redeem-error" data-ref="redeemErr"></div>
                    </div>
                </div>
                <div class="card full">
                    <h3>Usage Records</h3>
                    <div class="card-body">
                        <div class="table-wrap" data-ref="usageTable"><div class="empty">Loading...</div></div>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._ledgerFilter = '';
        this.refs.redeemBtn.addEventListener('click', () => this._redeem());
        this.refs.redeemInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') this._redeem(); });
    }

    async refresh() {
        try {
            const [ledger, usage] = await Promise.all([
                getJson(`${API.customerPortal}/v1/customer/credits/ledger`),
                getJson(`${API.customerPortal}/v1/customer/usage?limit=50`),
            ]);
            this._ledger = ledger.entries || [];
            this._usage = usage.records || [];
            this._renderLedger();
            this._renderUsage();
        } catch (e) {
            this.refs.ledgerTable.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`;
            this.refs.usageTable.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`;
        }
    }

    _renderLedger() {
        const reasons = [...new Set(this._ledger.map(e => e.reason))];
        this.refs.ledgerFilter.innerHTML = reasons.map(r =>
            `<button class="chip${this._ledgerFilter === r ? ' active' : ''}" data-filter="${r}">${r}</button>`).join('');
        this.shadowRoot.querySelectorAll('[data-filter]').forEach(btn => {
            btn.addEventListener('click', () => {
                this._ledgerFilter = this._ledgerFilter === btn.dataset.filter ? '' : btn.dataset.filter;
                this._hasLedgerRendered = false;
                this._renderLedger();
            });
        });
        const filtered = this._ledgerFilter
            ? this._ledger.filter(e => e.reason === this._ledgerFilter)
            : this._ledger;
        if (!filtered.length) { this.refs.ledgerTable.innerHTML = '<div class="empty">No ledger entries.</div>'; return; }
        const headers = ['Date', 'Reason', 'Delta', 'Balance'];
        const rows = filtered.map(e => [
            formatDate(e.createdAt),
            escapeHtml(e.reason),
            `<span class="${e.deltaCredits >= 0 ? 'delta-pos' : 'delta-neg'}">${e.deltaCredits >= 0 ? '+' : ''}${formatNumber(e.deltaCredits)}</span>`,
            formatNumber(e.balanceAfterCredits),
        ]);
        let table = this.refs.ledgerTable.querySelector('keel-data-table');
        if (!table) { table = document.createElement('keel-data-table'); this.refs.ledgerTable.innerHTML = ''; this.refs.ledgerTable.appendChild(table); }
        table.render({ silent: !!this._hasLedgerRendered && !this._ledgerFilter, headers, rows, emptyHtml: '<div class="empty">No ledger entries.</div>' });
        this._hasLedgerRendered = true;
    }

    _renderUsage() {
        if (!this._usage.length) { this.refs.usageTable.innerHTML = '<div class="empty">No usage yet.</div>'; return; }
        const headers = ['Time', 'Model', 'Group', 'In', 'Out', 'Credits', 'Request ID'];
        const rows = this._usage.map(r => [
            formatDate(r.createdAt),
            escapeHtml(r.model),
            escapeHtml(r.groupId),
            formatNumber(r.inputTokens),
            formatNumber(r.outputTokens),
            `<span class="delta-neg">${formatNumber(r.creditCost)}</span>`,
            `<span style="font-size:8px;">${escapeHtml((r.requestId || '').slice(0, 16))}</span>`,
        ]);
        let table = this.refs.usageTable.querySelector('keel-data-table');
        if (!table) { table = document.createElement('keel-data-table'); this.refs.usageTable.innerHTML = ''; this.refs.usageTable.appendChild(table); }
        table.render({ silent: !!this._hasUsageRendered, headers, rows, emptyHtml: '<div class="empty">No usage yet.</div>' });
        this._hasUsageRendered = true;
    }

    async _redeem() {
        const code = this.refs.redeemInput.value.trim();
        if (!code) return;
        this.refs.redeemBtn.disabled = true;
        this.refs.redeemOk.classList.remove('show');
        this.refs.redeemErr.classList.remove('show');
        try {
            const result = await postJson(`${API.customerPortal}/v1/customer/credits/redeem`, { code });
            this.refs.redeemOk.textContent = `+${formatNumber(result.amountCredits)} credits! New balance: ${formatNumber(result.newBalanceCredits)}`;
            this.refs.redeemOk.classList.add('show');
            this.refs.redeemInput.value = '';
            this.refresh();
        } catch (e) {
            this.refs.redeemErr.textContent = e.message;
            this.refs.redeemErr.classList.add('show');
        }
        this.refs.redeemBtn.disabled = false;
    }
}

customElements.define('customer-panel-billing', PanelBilling);
