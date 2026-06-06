import { KeelElement } from './base/KeelElement.js';
import { getJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml, formatNumber } from '../utils.js';
import './shared/KeelDataTable.js';

/**
 * Read-only "Rates" page: shows every model + its pricing in USD per 1M tokens.
 * Lets the customer know the cost of each request before they make it.
 */
export class PanelPricing extends KeelElement {
    hostStyles() { return 'height:100%;'; }
    constructor() { super(); this._hasRendered = false; }

    template() {
        return `
            <style>
                .layout { display: flex; flex-direction: column; gap: 24px; }
                .hero {
                    border: 2px solid var(--ink); background: var(--ink); color: var(--paper);
                    padding: 32px; box-shadow: var(--shadow-lg);
                }
                .hero h1 { font-family: var(--font-headline); font-size: clamp(36px, 5vw, 64px); line-height: 0.9; letter-spacing: -0.04em; text-transform: uppercase; margin: 0; }
                .hero p { margin: 12px 0 0; color: var(--paper); font-family: var(--font-mono); font-size: 12px; font-weight: 700; letter-spacing: 0.06em; opacity: 0.8; }
                .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
                .card { border: 2px solid var(--ink); background: var(--paper); padding: 18px; }
                .card h3 { margin: 0 0 12px; font-family: var(--font-display); font-size: 15px; text-transform: uppercase; letter-spacing: -0.03em; }
                .empty { text-align: center; color: var(--muted); padding: 30px; font-family: var(--font-mono); font-size: 12px; }
                .table-card { background: var(--panel-strong); border: 2px solid var(--ink); overflow: auto; }
                .info { padding: 14px 18px; background: var(--teal-soft); border: 2px solid var(--ink); font-family: var(--font-mono); font-size: 11px; line-height: 1.7; }
                .info code { background: var(--ink); color: var(--paper); padding: 2px 6px; font-family: var(--font-mono); }
            </style>
            <div class="layout">
                <div class="hero">
                    <h1>Model Rates</h1>
                    <p>Pricing for every model you can call. Credits are charged per request size and model multiplier.</p>
                </div>
                <div class="info">
                    <strong>How to read this:</strong> Each rate is USD per 1,000,000 tokens. Credits are charged separately using request-size buckets plus model or alias multipliers. Cache reads are dramatically cheaper; cache writes (Anthropic prompt caching) cost slightly more than regular input. Reasoning tokens (when supported) are billed as output.
                </div>
                <div class="table-card" data-ref="tableWrap"></div>
            </div>
        `;
    }

    async refresh() {
        try {
            const data = await getJson(`${API.customerPortal}/v1/customer/pricing`);
            this._summaries = data.summaries || [];
            this._render();
        } catch (e) {
            this.refs.tableWrap.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`;
        }
    }

    _rateHtml(s) {
        const fmt = v => Number(v ?? 0).toFixed(4);
        if (s.tiers?.length) {
            return s.tiers.map(t => {
                const end = t.endTokensExclusive == null ? '∞' : formatNumber(t.endTokensExclusive);
                const cache = t.cacheCreationCostPerUnit != null || t.cacheReadCostPerUnit != null ? ` · cache W/R $${fmt(t.cacheCreationCostPerUnit)}/$${fmt(t.cacheReadCostPerUnit)}` : '';
                const reason = t.reasoningOutputCostPerUnit != null ? ` · reason $${fmt(t.reasoningOutputCostPerUnit)}` : '';
                return `<code>${formatNumber(t.startTokensInclusive)}-${end}</code> unit ${formatNumber(t.billingUnitTokens)} · in/out $${fmt(t.inputCostPerUnit)}/$${fmt(t.outputCostPerUnit)}${cache}${reason}`;
            }).join('<br>');
        }
        return `flat · in/out $${fmt(s.inputCostPerMTok)}/$${fmt(s.outputCostPerMTok)}${s.cacheCreationCostPerMTok != null || s.cacheReadCostPerMTok != null ? ` · cache W/R $${fmt(s.cacheCreationCostPerMTok)}/$${fmt(s.cacheReadCostPerMTok)}` : ''}${s.reasoningOutputCostPerMTok != null ? ` · reason $${fmt(s.reasoningOutputCostPerMTok)}` : ''}`;
    }

    _render() {
        if (this._summaries.length === 0) {
            this.refs.tableWrap.innerHTML = '<div class="empty">// NO PRICING AVAILABLE YET</div>';
            return;
        }
        const headers = ['Model', 'Variant', 'Unit', 'Rates / Tiers'];
        const rows = this._summaries.map(s => [
            `<code>${escapeHtml(s.model)}</code>`,
            s.variantKey ? `<code>${escapeHtml(s.variantKey)}</code>` : '—',
            `${Number(s.billingUnitTokens || 1_000_000).toLocaleString('en-US')}`,
            this._rateHtml(s),
        ]);
        let table = this.refs.tableWrap.querySelector('keel-data-table');
        if (!table) {
            table = document.createElement('keel-data-table');
            this.refs.tableWrap.innerHTML = '';
            this.refs.tableWrap.appendChild(table);
        }
        table.render({ silent: !!this._hasRendered, headers, rows, emptyHtml: '<div class="empty">// NO PRICING</div>', keyColumn: 1 });
        this._hasRendered = true;
    }
}

customElements.define('customer-panel-pricing', PanelPricing);
