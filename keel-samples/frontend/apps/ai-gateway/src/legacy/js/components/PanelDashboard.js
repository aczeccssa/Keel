import { KeelElement } from './base/KeelElement.js';
import { requestJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

/**
 * Ops dashboard: window-scoped overview, latency percentiles, trends, distributions,
 * channel health and a live request stream. Distinct from the lightweight Overview tab.
 */
export class PanelDashboard extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .ops { display: flex; flex-direction: column; gap: 24px; }
                .toolbar { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
                .toolbar-label { font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: .14em; text-transform: uppercase; color: var(--muted); }
                .window-btn { padding: 8px 16px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); cursor: pointer; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
                .window-btn.active { background: var(--surface-accent); color: var(--on-accent); box-shadow: 3px 3px 0 var(--teal); }
                .auto-refresh { margin-left:auto; display:inline-flex; align-items:center; gap:8px; font-family:var(--font-mono); font-size:10px; font-weight:800; color:var(--muted); text-transform:uppercase; }
                .section-card { background: var(--panel-strong); border-radius: var(--radius-lg); padding: 24px; box-shadow: var(--shadow-sm); border: 2px solid var(--ink); min-width: 0; }
                .section-title { font-family: var(--font-headline); font-size: 16px; font-weight: 500; margin: 0 0 16px; color: var(--ink); text-transform: uppercase; letter-spacing: -0.02em; }
                .chart-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:12px; }
                .chart-header .section-title { margin:0; }
                .chart-hint { font-family: var(--font-mono); font-size: 10px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em; }
                .auto-grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 20px; }
                .health-grid { display:grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
                .health-tile { padding:16px; border:2px solid var(--ink); background:var(--paper); }
                .health-tile.ok { background: var(--green-soft); }
                .health-tile.warn { background: var(--amber-soft); }
                .health-tile.danger { background: var(--red-soft); }
                .health-label { font-family:var(--font-mono); font-size:10px; font-weight:800; color:var(--muted); text-transform:uppercase; letter-spacing:.1em; }
                .health-value { margin-top:6px; font-family:var(--font-headline); font-size:30px; line-height:1; }
                .empty { text-align:center; color:var(--muted); padding:32px; font-size:13px; }
            </style>
            <div class="ops" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>

                <div class="toolbar">
                    <span class="toolbar-label">Window</span>
                    ${['1h', '24h', '7d', '30d'].map(w => `<button class="window-btn" data-window="${w}">${w}</button>`).join('')}
                    <label class="auto-refresh"><input type="checkbox" data-ref="autoRefresh"> Auto refresh 30s</label>
                </div>

                <keel-stat-grid data-ref="overviewStats"></keel-stat-grid>
                <keel-stat-grid data-ref="latencyStats"></keel-stat-grid>

                <div class="auto-grid">
                    <div class="section-card"><div class="chart-header"><h3 class="section-title">Requests</h3><span class="chart-hint" data-ref="reqHint"></span></div><keel-chart data-ref="requestChart"></keel-chart></div>
                    <div class="section-card"><div class="chart-header"><h3 class="section-title">Tokens</h3><span class="chart-hint">in + out + cache</span></div><keel-chart data-ref="tokenChart"></keel-chart></div>
                    <div class="section-card"><div class="chart-header"><h3 class="section-title">Latency P95</h3><span class="chart-hint">trend</span></div><keel-chart data-ref="latencyChart"></keel-chart></div>
                </div>

                <div class="auto-grid">
                    <div class="section-card"><h3 class="section-title">Model Distribution</h3><keel-chart data-ref="modelChart"></keel-chart></div>
                    <div class="section-card"><h3 class="section-title">Channel Distribution</h3><keel-chart data-ref="channelChart"></keel-chart></div>
                    <div class="section-card"><h3 class="section-title">Group Distribution</h3><keel-chart data-ref="groupChart"></keel-chart></div>
                    <div class="section-card"><h3 class="section-title">Error Distribution</h3><keel-chart data-ref="errorChart"></keel-chart></div>
                </div>

                <div class="auto-grid">
                    <div class="section-card"><h3 class="section-title">Channel Health</h3><div class="health-grid" data-ref="healthGrid"></div></div>
                    <div class="section-card"><h3 class="section-title">Recent Request Stream</h3><keel-data-table data-ref="recentTable"></keel-data-table></div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._window = '24h';
        this._pollTimer = null;
        this._hasRendered = false;
        this.refs.hero.render({ label: 'Operations', title: 'Dashboard', metaHtml: '' });
        this.refs.root.addEventListener('click', (event) => {
            const btn = event.target.closest('[data-window]');
            if (!btn) return;
            this._window = btn.dataset.window;
            this._paintWindowButtons();
            this.refresh();
        });
        this.refs.autoRefresh.addEventListener('change', () => this._configurePolling());
        this._paintWindowButtons();
    }

    disconnectedCallback() {
        super.disconnectedCallback?.();
        this._stopPolling();
    }

    _configurePolling() {
        this._stopPolling();
        if (this.refs.autoRefresh.checked) {
            this._pollTimer = setInterval(() => this.refresh(), 30000);
        }
    }

    _stopPolling() {
        if (this._pollTimer) clearInterval(this._pollTimer);
        this._pollTimer = null;
    }

    _paintWindowButtons() {
        this.shadowRoot.querySelectorAll('[data-window]').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.window === this._window);
        });
    }

    async refresh() {
        try {
            const [stats, records] = await Promise.all([
                requestJson(`${API.airelay}/admin/stats/dashboard?window=${encodeURIComponent(this._window)}`),
                requestJson(`${API.token}/admin/usage/records?limit=200`).catch(() => ({ records: [] })),
            ]);
            this._render(stats || {}, records.records || []);
        } catch (e) {
            this.refs.overviewStats.render({ entries: [['Status', 'Error', e.message]] });
        }
    }

    _render(stats, records) {
        const silent = this._hasRendered;
        this._hasRendered = true;
        const o = stats.overview || {};
        const d = stats.distributions || {};
        const t = stats.trends || {};

        this.refs.hero.render({
            label: 'Operations',
            title: 'Dashboard',
            metaHtml: `<div style="display:grid;justify-items:end;gap:8px;"><span style="display:inline-flex;padding:7px 10px;border:1px solid rgba(235,231,223,0.18);background:rgba(11,11,11,0.18);color:var(--on-accent);font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;">${(o.totalRequests || 0).toLocaleString()} requests · ${this._window}</span></div>`
        });
        this.refs.overviewStats.render({ silent, entries: [
            ['Requests', (o.totalRequests || 0).toLocaleString(), this._window],
            ['Success', o.successRate != null ? `${(o.successRate * 100).toFixed(1)}%` : '—', 'rate'],
            ['Cost', `$${(o.totalCostUsd || 0).toFixed(4)}`, 'USD'],
            ['Tokens', this._fmt(o.totalTokens || 0), 'total'],
            ['Cache Hit', o.cacheHitRate != null ? `${(o.cacheHitRate * 100).toFixed(1)}%` : '—', 'rate'],
        ]});
        this.refs.latencyStats.render({ silent, entries: [
            ['Avg Latency', `${o.avgLatencyMs || 0}ms`, 'mean'],
            ['P50', `${o.p50LatencyMs || 0}ms`, 'latency'],
            ['P95', `${o.p95LatencyMs || 0}ms`, 'latency'],
            ['P99', `${o.p99LatencyMs || 0}ms`, 'latency'],
        ]});

        this.refs.healthGrid.innerHTML = [
            this._healthTile('Healthy', o.healthyChannels || 0, 'ok'),
            this._healthTile('Cooldown', o.cooldownChannels || 0, 'warn'),
            this._healthTile('Disabled', o.disabledChannels || 0, 'danger'),
        ].join('');

        const reqTrend = t.requestsByHour || [];
        this.refs.reqHint.textContent = this._window;
        this.refs.requestChart.render({ type: 'bar', data: reqTrend.map(p => p.requests), labels: reqTrend.map(p => this._bucketLabel(p.timestamp)), height: 110, emptyText: 'No request data yet' });
        const tokenTrend = t.tokensByHour || [];
        this.refs.tokenChart.render({ type: 'bar', data: tokenTrend.map(p => (p.promptTokens || 0) + (p.completionTokens || 0) + (p.cacheWriteTokens || 0) + (p.cacheReadTokens || 0)), labels: tokenTrend.map(p => this._bucketLabel(p.timestamp)), height: 110, emptyText: 'No token data yet' });
        const latencyTrend = t.latencyByHour || [];
        this.refs.latencyChart.render({ type: 'sparkline', data: latencyTrend.map(p => p.p95 || 0), emptyText: 'No latency data yet' });

        this.refs.modelChart.render({ type: 'donut', data: (d.modelDistribution || []).map(x => x.requests), labels: (d.modelDistribution || []).map(x => x.model), emptyText: 'No model data yet' });
        this.refs.channelChart.render({ type: 'bar', data: (d.channelDistribution || []).map(x => x.requests), labels: (d.channelDistribution || []).map(x => x.channelName || x.channelId), height: 110, emptyText: 'No channel data yet' });
        this.refs.groupChart.render({ type: 'bar', data: (d.groupDistribution || []).map(x => x.requests), labels: (d.groupDistribution || []).map(x => x.groupName || x.groupId), height: 110, emptyText: 'No group data yet' });
        this.refs.errorChart.render({ type: 'donut', data: (d.errorDistribution || []).map(x => x.count), labels: (d.errorDistribution || []).map(x => x.errorType), emptyText: 'No errors in this window' });

        this.refs.recentTable.render({ silent, headers: ['Time', 'Model', 'Channel', 'Status', 'Latency', 'Tokens', 'Cost'], rows: records.slice(0, 20).map(r => {
            const u = r.usage || {};
            const tokenTotal = (u.promptTokens || 0) + (u.completionTokens || 0) + (u.cacheReadInputTokens || 0) + (u.cacheCreationInputTokens || 0);
            return [
                `<span style="font-size:11px;">${escapeHtml((r.createdAt || '').replace('T', ' ').slice(0, 19))}</span>`,
                `<code>${escapeHtml(r.model || '')}</code>`,
                `<code>${escapeHtml(r.channelName || r.channelId || r.upstreamKeyId || '—')}</code>`,
                r.status >= 400 ? `<span style="color:var(--red);font-weight:800;">${r.status}</span>` : `<span style="color:var(--green);font-weight:800;">${r.status || 200}</span>`,
                `${r.latencyMs || 0}ms`,
                this._fmt(tokenTotal),
                `$${((r.cost || {}).totalCostUsd || r.totalCostUsd || 0).toFixed(4)}`,
            ];
        }), emptyHtml: '<div class="empty">No requests recorded yet.</div>' });
    }

    _healthTile(label, value, tone) {
        return `<div class="health-tile ${tone}"><div class="health-label">${label}</div><div class="health-value">${value}</div></div>`;
    }

    _bucketLabel(timestamp) {
        const d = new Date(timestamp);
        if (this._window === '1h' || this._window === '24h') return d.toLocaleTimeString('en', { hour: '2-digit', minute: '2-digit' });
        return d.toLocaleDateString('en', { month: 'short', day: 'numeric' });
    }

    _fmt(n) {
        const value = Number(n || 0);
        if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
        if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
        return value.toLocaleString();
    }
}

customElements.define('ai-panel-dashboard', PanelDashboard);
