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
                .auto-grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 20px; }
                .bottom-grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(420px, 1fr)); gap: 20px; align-items: start; }
                .health-grid { display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
                .health-tile { padding:16px; border:2px solid var(--ink); background:var(--paper); }
                .health-tile.ok { background: var(--green-soft); }
                .health-tile.warn { background: var(--amber-soft); }
                .health-tile.danger { background: var(--red-soft); }
                .health-label { font-family:var(--font-mono); font-size:10px; font-weight:800; color:var(--muted); text-transform:uppercase; letter-spacing:.1em; }
                .health-value { margin-top:6px; font-family:var(--font-headline); font-size:30px; line-height:1; }
                .health-note { margin-top:12px; font-family:var(--font-mono); font-size:10px; font-weight:700; color:var(--muted); text-transform:uppercase; letter-spacing:.06em; }
                .recent-scroll { overflow:auto; height: 232px; border:2px solid var(--ink); }
                table.recent { border-collapse:collapse; width:max-content; min-width:100%; font-family:var(--font-mono); font-size:11px; }
                table.recent thead th { position:sticky; top:0; z-index:1; text-align:left; white-space:nowrap; font-size:10px; font-weight:800; letter-spacing:.12em; text-transform:uppercase; color:var(--on-accent); background:var(--surface-accent); padding:9px 12px; }
                table.recent tbody td { padding:8px 12px; white-space:nowrap; border-bottom:1px solid var(--ink); color:var(--ink); }
                table.recent tbody tr:nth-child(even) td { background: var(--color-surface-container-low, #ebe9e3); }
                .empty { text-align:center; color:var(--muted); padding:32px; font-size:13px; }
            </style>
            <div class="ops" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>

                <div class="toolbar">
                    <span class="toolbar-label">Window</span>
                    ${['1h', '24h', '7d', '30d'].map(w => `<button class="window-btn" data-window="${w}">${w}</button>`).join('')}
                </div>

                <keel-stat-grid data-ref="overviewStats"></keel-stat-grid>

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

                <div class="bottom-grid">
                    <div class="section-card"><h3 class="section-title">Channel Health</h3><div class="health-grid" data-ref="healthGrid"></div><div class="health-note" data-ref="healthNote"></div></div>
                    <div class="section-card"><div class="chart-header"><h3 class="section-title">Recent Request Stream</h3><span class="chart-hint">latest 5</span></div><div class="recent-scroll" data-ref="recentScroll"></div></div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._window = '24h';
        this._hasRendered = false;
        this.refs.hero.render({ label: 'Operations', title: 'Dashboard', metaHtml: '' });
        this.refs.root.addEventListener('click', (event) => {
            const btn = event.target.closest('[data-window]');
            if (!btn) return;
            this._window = btn.dataset.window;
            this._paintWindowButtons();
            this.refresh();
        });
        this._paintWindowButtons();
    }

    // Global Live toggle hook. The shell drives refresh cadence; this is for visual reaction only.
    setLiveMode() { /* dashboard has no panel-local live visuals */ }

    _paintWindowButtons() {
        this.shadowRoot.querySelectorAll('[data-window]').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.window === this._window);
        });
    }

    async refresh() {
        try {
            const [stats, records] = await Promise.all([
                requestJson(`${API.airelay}/admin/stats/dashboard?window=${encodeURIComponent(this._window)}`),
                requestJson(`${API.token}/admin/usage/records?limit=5`).catch(() => ({ records: [] })),
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
            ['Avg Latency', `${o.avgLatencyMs || 0}ms`, 'mean'],
            ['P50', `${o.p50LatencyMs || 0}ms`, 'latency'],
            ['P95', `${o.p95LatencyMs || 0}ms`, 'latency'],
            ['P99', `${o.p99LatencyMs || 0}ms`, 'latency'],
        ]});

        this._labelMode = t.bucketLabelMode || ((this._window === '7d' || this._window === '30d') ? 'day' : 'time');
        const totalChannels = (o.healthyChannels || 0) + (o.cooldownChannels || 0) + (o.disabledChannels || 0);
        this.refs.healthGrid.innerHTML = [
            this._healthTile('Healthy', o.healthyChannels || 0, 'ok'),
            this._healthTile('Cooldown', o.cooldownChannels || 0, 'warn'),
            this._healthTile('Disabled', o.disabledChannels || 0, 'danger'),
            this._healthTile('Total', totalChannels, ''),
        ].join('');
        this.refs.healthNote.textContent = (o.cooldownChannels || 0) + (o.disabledChannels || 0) > 0
            ? `${(o.cooldownChannels || 0) + (o.disabledChannels || 0)} channel(s) need attention`
            : 'All channels healthy';

        const reqTrend = t.requests || t.requestsByHour || [];
        this.refs.reqHint.textContent = `${this._window} · ${t.bucketGranularity || ''}`;
        this.refs.requestChart.render({ type: 'bar', color: 'var(--teal, #0d9488)', data: reqTrend.map(p => p.requests), labels: this._thinLabels(reqTrend.map(p => this._bucketLabel(p.timestamp))), height: 110, emptyText: 'No request data yet' });
        const tokenTrend = t.tokens || t.tokensByHour || [];
        this.refs.tokenChart.render({ type: 'bar', color: 'var(--indigo, #6366f1)', data: tokenTrend.map(p => (p.promptTokens || 0) + (p.completionTokens || 0) + (p.cacheWriteTokens || 0) + (p.cacheReadTokens || 0)), labels: this._thinLabels(tokenTrend.map(p => this._bucketLabel(p.timestamp))), height: 110, emptyText: 'No token data yet' });
        const latencyTrend = t.latency || t.latencyByHour || [];
        this.refs.latencyChart.render({ type: 'sparkline', data: latencyTrend.map(p => p.p95 || 0), emptyText: 'No latency data yet' });

        this.refs.modelChart.render({ type: 'donut', data: (d.modelDistribution || []).map(x => x.requests), labels: (d.modelDistribution || []).map(x => x.model), emptyText: 'No model data yet' });
        this.refs.channelChart.render({ type: 'bar', data: (d.channelDistribution || []).map(x => x.requests), labels: (d.channelDistribution || []).map(x => x.channelName || x.channelId), height: 110, emptyText: 'No channel data yet' });
        this.refs.groupChart.render({ type: 'bar', data: (d.groupDistribution || []).map(x => x.requests), labels: (d.groupDistribution || []).map(x => x.groupName || x.groupId), height: 110, emptyText: 'No group data yet' });
        this.refs.errorChart.render({ type: 'donut', data: (d.errorDistribution || []).map(x => x.count), labels: (d.errorDistribution || []).map(x => x.errorType), emptyText: 'No errors in this window' });

        const rows = records.slice(0, 5).map(r => {
            const u = r.usage || {};
            const tokenTotal = (u.promptTokens || 0) + (u.completionTokens || 0) + (u.cacheReadInputTokens || 0) + (u.cacheCreationInputTokens || 0);
            const status = r.status >= 400 ? `<span style="color:var(--red);font-weight:800;">${r.status}</span>` : `<span style="color:var(--green);font-weight:800;">${r.status || 200}</span>`;
            return `<tr>
                <td>${escapeHtml((r.createdAt || '').replace('T', ' ').slice(0, 19))}</td>
                <td><code>${escapeHtml(r.model || '')}</code></td>
                <td><code>${escapeHtml(r.channelName || r.channelId || r.upstreamKeyId || '—')}</code></td>
                <td>${status}</td>
                <td>${r.latencyMs || 0}ms</td>
                <td>${this._fmt(tokenTotal)}</td>
                <td>$${((r.cost || {}).totalCostUsd || r.totalCostUsd || 0).toFixed(4)}</td>
            </tr>`;
        }).join('');
        this.refs.recentScroll.innerHTML = records.length === 0
            ? '<div class="empty">No requests recorded yet.</div>'
            : `<table class="recent"><thead><tr><th>Time</th><th>Model</th><th>Channel</th><th>Status</th><th>Latency</th><th>Tokens</th><th>Cost</th></tr></thead><tbody>${rows}</tbody></table>`;
    }

    _healthTile(label, value, tone) {
        return `<div class="health-tile ${tone}"><div class="health-label">${label}</div><div class="health-value">${value}</div></div>`;
    }

    // Keep day/wide windows readable: show at most ~10 labels, blank the rest.
    _thinLabels(labels) {
        const max = 10;
        if (labels.length <= max) return labels;
        const step = Math.ceil(labels.length / max);
        return labels.map((label, i) => (i % step === 0 ? label : ''));
    }

    _bucketLabel(timestamp) {
        const d = new Date(timestamp);
        if (this._labelMode === 'day') return d.toLocaleDateString('en', { month: 'short', day: 'numeric' });
        return d.toLocaleTimeString('en', { hour: '2-digit', minute: '2-digit' });
    }

    _fmt(n) {
        const value = Number(n || 0);
        if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
        if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
        return value.toLocaleString();
    }
}

customElements.define('ai-panel-dashboard', PanelDashboard);
