import { KeelElement } from './base/KeelElement.js';
import { getJson, postJson } from '../api.js';
import { API } from '../config.js';
import { formatDate, formatNumber, escapeHtml } from '../utils.js';
import './shared/KeelChart.js';

function dayKey(iso) {
    if (!iso) return '';
    return iso.slice(0, 10);
}

function build30DayUsage(records) {
    const buckets = new Map();
    records.forEach((record) => {
        const key = dayKey(record.createdAt || record.occurredAt);
        if (!key) return;
        const current = buckets.get(key) || { tokens: 0, credits: 0 };
        current.tokens += (record.inputTokens || 0) + (record.outputTokens || 0);
        current.credits += record.creditCost || 0;
        buckets.set(key, current);
    });

    const days = [];
    const now = new Date();
    for (let i = 29; i >= 0; i -= 1) {
        const date = new Date(now.getTime() - i * 86_400_000);
        const key = date.toISOString().slice(0, 10);
        const bucket = buckets.get(key) || { tokens: 0, credits: 0 };
        days.push({
            label: key.slice(5, 10),
            tokens: bucket.tokens,
            credits: bucket.credits,
        });
    }
    return days;
}

export class PanelDashboard extends KeelElement {
    hostStyles() { return ''; }

    template() {
        return `
            <style>
                .layout { display:grid; gap:24px; }
                .balance-card {
                    border:2px solid var(--ink); box-shadow:var(--shadow-lg); background:var(--paper);
                    padding:28px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:16px;
                }
                .balance-num {
                    font-family:var(--font-display); font-size:clamp(40px,8vw,72px); line-height:0.9; letter-spacing:-0.05em;
                }
                .balance-label {
                    font-family:var(--font-mono); font-size:10px; font-weight:800; text-transform:uppercase; letter-spacing:0.14em; color:var(--muted);
                }
                .actions { display:flex; gap:10px; flex-wrap:wrap; }
                .btn {
                    padding:11px 18px; border:2px solid var(--ink); background:var(--ink); color:var(--paper);
                    font-family:var(--font-mono); font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; cursor:pointer;
                    transition: background 120ms, color 120ms;
                }
                .btn:hover { background:var(--teal); border-color:var(--teal); }
                .btn-outline {
                    padding:11px 18px; border:2px solid var(--ink); background:var(--paper); color:var(--ink);
                    font-family:var(--font-mono); font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; cursor:pointer;
                    transition: background 120ms, color 120ms;
                }
                .btn-outline:hover { background:var(--ink); color:var(--paper); }
                .grid { display:grid; grid-template-columns:1fr 1fr; gap:18px; }
                .grid-3 { display:grid; grid-template-columns:1fr 1fr 1fr; gap:18px; }
                .card {
                    border:2px solid var(--ink); background:var(--paper); padding:20px;
                }
                .card h3 {
                    margin:0 0 14px; font-family:var(--font-display); font-size:15px; text-transform:uppercase; letter-spacing:-0.03em;
                }
                .chart-header {
                    display: flex; align-items: center; justify-content: space-between;
                    margin-bottom: 12px;
                }
                .chart-header h3 { margin: 0; }
                .chart-hint {
                    font-family: var(--font-mono); font-size: 10px; font-weight: 700;
                    color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em;
                }
                .stat-row { display:flex; gap:18px; margin-top:10px; }
                .stat-item { flex:1; }
                .stat-val { font-family:var(--font-display); font-size:24px; line-height:1; }
                .stat-label { font-family:var(--font-mono); font-size:10px; font-weight:800; text-transform:uppercase; letter-spacing:0.1em; color:var(--muted); margin-top:4px; }
                .recent { margin-top:14px; font-family:var(--font-mono); font-size:10px; }
                .recent .row {
                    display:flex; gap:8px; padding:6px 0; border-bottom:1px solid var(--bg, #ebe9e3);
                }
                .recent .row .col { flex:1; }
                .empty { padding:30px; text-align:center; font-family:var(--font-mono); font-size:12px; font-weight:800; color:var(--muted); text-transform:uppercase; letter-spacing:0.08em; }
                .modal-overlay { position:fixed; inset:0; background:rgba(11,11,11,0.55); display:none; align-items:center; justify-content:center; z-index:900; }
                .modal-overlay.open { display:flex; }
                .modal { width:min(400px,90vw); border:2px solid var(--ink); background:var(--paper); box-shadow:var(--shadow-lg); }
                .modal-head { padding:14px 18px; background:var(--ink); color:var(--paper); font-family:var(--font-display); font-size:16px; text-transform:uppercase; letter-spacing:-0.04em; display:flex; justify-content:space-between; }
                .modal-body { padding:18px; display:grid; gap:12px; }
                .modal-body .field label { display:block; margin-bottom:4px; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.12em; text-transform:uppercase; color:var(--muted); }
                .modal-body .field input { width:100%; padding:10px 12px; border:2px solid var(--ink); font-family:var(--font-mono); font-size:13px; background:var(--paper); color:var(--ink); }
                .modal-error { display:none; padding:8px 14px; background:var(--red); color:var(--paper); font-family:var(--font-mono); font-size:10px; font-weight:800; }
                .modal-error.show { display:block; }
                @media (max-width:700px) { .grid, .grid-3 { grid-template-columns:1fr; } }
            </style>
            <div class="layout">
                <div class="balance-card">
                    <div>
                        <div class="balance-label">Credit Balance</div>
                        <div class="balance-num" data-ref="balance">0</div>
                        <div style="font-family:var(--font-mono);font-size:10px;color:var(--muted);margin-top:4px;">credits</div>
                    </div>
                    <div class="actions">
                        <button class="btn" data-ref="redeemBtn">Redeem Code</button>
                        <button class="btn-outline" data-ref="keysBtn">Create Key</button>
                    </div>
                </div>
                <div class="grid-3">
                    <div class="card">
                        <div class="chart-header">
                            <h3>30-Day Usage</h3>
                            <span class="chart-hint">Tokens</span>
                        </div>
                        <keel-chart data-ref="usageChart"></keel-chart>
                        <div style="margin-top:10px;font-family:var(--font-mono);font-size:10px;color:var(--muted);">
                            <span data-ref="monthTokens">0</span> tokens · <span data-ref="monthCost">0</span> credits
                        </div>
                    </div>
                    <div class="card">
                        <div class="chart-header">
                            <h3>Credit Spend</h3>
                            <span class="chart-hint">Trend</span>
                        </div>
                        <keel-chart data-ref="spendChart"></keel-chart>
                    </div>
                    <div class="card">
                        <div class="chart-header">
                            <h3>Model Usage</h3>
                            <span class="chart-hint">Split</span>
                        </div>
                        <keel-chart data-ref="modelChart"></keel-chart>
                    </div>
                </div>
                <div class="card" data-ref="recentCard">
                    <h3>Recent Activity</h3>
                    <div class="recent" data-ref="recent"></div>
                </div>
            </div>
            <div class="modal-overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span>Redeem Code</span><button class="btn-outline" style="color:var(--paper);border-color:var(--paper);" data-ref="modalClose">&#10005;</button></div>
                    <div class="modal-error" data-ref="modalError"></div>
                    <div class="modal-body">
                        <div class="field"><label>Code</label><input data-ref="codeInput" placeholder="XXXX-XXXX-XXXX" /></div>
                        <button class="btn" data-ref="redeemSubmit">Redeem</button>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.redeemBtn.addEventListener('click', () => { this.refs.overlay.classList.add('open'); });
        this.refs.modalClose.addEventListener('click', () => { this.refs.overlay.classList.remove('open'); });
        this.refs.overlay.addEventListener('click', (e) => { if (e.target === this.refs.overlay) this.refs.overlay.classList.remove('open'); });
        this.refs.redeemSubmit.addEventListener('click', () => this._redeem());
        this.refs.keysBtn.addEventListener('click', () => { window.location.hash = 'keys'; window.dispatchEvent(new HashChangeEvent('hashchange')); });
    }

    async refresh() {
        try {
            const [balance, usage, keys] = await Promise.all([
                getJson(`${API.customerPortal}/v1/customer/credits`),
                getJson(`${API.customerPortal}/v1/customer/usage?limit=50`),
                getJson(`${API.customerPortal}/v1/customer/keys`),
            ]);
            this.refs.balance.textContent = formatNumber(balance.balanceCredits);
            this.refs.keyCount && (this.refs.keyCount.textContent = keys.keys.filter(k => k.status === 'active').length);

            const records = usage.records || [];
            const silent = !!this._hasRendered;
            this._hasRendered = true;

            // ── Usage bar chart (last 30 days) ──
            const usageByDay = build30DayUsage(records);
            this.refs.usageChart.render({
                type: 'bar',
                data: usageByDay.map(r => r.tokens),
                labels: usageByDay.map(r => r.label),
                height: 80,
                emptyText: 'No usage yet'
            });

            // ── Credit spend sparkline ──
            this.refs.spendChart.render({
                type: 'sparkline',
                data: usageByDay.map(r => r.credits),
                emptyText: 'No spend yet'
            });

            // ── Model usage donut ──
            const modelMap = {};
            records.forEach(r => {
                const m = r.model || 'unknown';
                modelMap[m] = (modelMap[m] || 0) + (r.inputTokens + r.outputTokens);
            });
            const modelEntries = Object.entries(modelMap).sort((a, b) => b[1] - a[1]).slice(0, 6);
            this.refs.modelChart.render({
                type: 'donut',
                data: modelEntries.map(e => e[1]),
                labels: modelEntries.map(e => e[0]),
                emptyText: 'No model data yet'
            });

            // ── Stats ──
            const monthTotal = usageByDay.reduce((s, r) => s + r.tokens, 0);
            const monthCredits = usageByDay.reduce((s, r) => s + r.credits, 0);
            this.refs.monthTokens.textContent = formatNumber(monthTotal);
            this.refs.monthCost.textContent = formatNumber(monthCredits);

            // ── Recent activity: full breakdown per request ──
            this.refs.recent.innerHTML = records.slice(0, 8).map(r => `
                <div class="row" style="grid-template-columns: 1.4fr 1fr 1fr 1fr 0.8fr;">
                    <div class="col" style="font-weight:800;">${escapeHtml(r.model)}</div>
                    <div class="col" style="color:var(--muted);">In:${formatNumber(r.inputTokens)} Out:${formatNumber(r.outputTokens)}</div>
                    <div class="col" style="color:var(--muted);">CR:${formatNumber(r.cacheReadInputTokens)} CW:${formatNumber(r.cacheCreationInputTokens)}</div>
                    <div class="col" style="color:var(--muted);">${r.cacheHitRate != null ? `Hit:${Math.round(r.cacheHitRate * 100)}%` : ''}</div>
                    <div class="col" style="text-align:right;font-weight:800;">${r.creditCost} cr</div>
                </div>
            `).join('') || '<div class="empty">No usage yet</div>';
        } catch (e) {
            this.refs.recent.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`;
        }
    }

    async _redeem() {
        const code = this.refs.codeInput.value.trim();
        if (!code) return;
        this.refs.redeemSubmit.disabled = true;
        try {
            await postJson(`${API.customerPortal}/v1/customer/credits/redeem`, { code });
            this.refs.modalError.classList.remove('show');
            this.refs.overlay.classList.remove('open');
            this.refs.codeInput.value = '';
            this.refresh();
        } catch (e) {
            this.refs.modalError.textContent = e.message;
            this.refs.modalError.classList.add('show');
        }
        this.refs.redeemSubmit.disabled = false;
    }
}

customElements.define('customer-panel-dashboard', PanelDashboard);
