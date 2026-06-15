import { KeelElement } from './base/KeelElement.js';
import { requestJson } from '../api.js';
import { API } from '../config.js';

export class PanelDashboard extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .dashboard { display: flex; flex-direction: column; gap: 32px; }
                .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
                .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 24px; }
                .section-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 28px;
                    box-shadow: var(--shadow-sm);
                    border: 2px solid var(--ink);
                }
                .hero-meta {
                    display: grid;
                    justify-items: end;
                    gap: 8px;
                    padding: 4px 0;
                }
                .hero-chip {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    padding: 7px 10px;
                    border: 1px solid rgba(235, 231, 223, 0.18);
                    background: rgba(11, 11, 11, 0.18);
                    color: var(--on-accent);
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.08em;
                    text-transform: uppercase;
                }
                .section-title {
                    font-family: var(--font-headline);
                    font-size: 18px;
                    font-weight: 500;
                    margin: 0 0 16px;
                    color: var(--ink);
                    text-transform: uppercase;
                    letter-spacing: -0.02em;
                }
                .onboarding-banner {
                    background: var(--surface-muted);
                    border-radius: var(--radius-lg);
                    padding: 24px 28px;
                    line-height: 1.7;
                    font-size: 13px;
                    color: var(--ink);
                    border: 2px solid var(--ink);
                }
                .onboarding-banner h3 {
                    font-family: var(--font-headline);
                    font-size: 22px;
                    margin: 0 0 12px;
                }
                .onboarding-banner ol {
                    margin: 12px 0 0;
                    padding-left: 20px;
                }
                .onboarding-banner li { margin-bottom: 6px; }
                .onboarding-banner code {
                    background: var(--surface-accent);
                    color: var(--on-accent);
                    padding: 2px 6px;
                    border-radius: 0;
                    font-family: var(--font-mono);
                    font-size: 11px;
                }
                .empty { text-align: center; color: var(--muted); padding: 40px; font-size: 13px; }
                .chart-header {
                    display: flex; align-items: center; justify-content: space-between;
                    margin-bottom: 12px;
                }
                .chart-header .section-title { margin: 0; }
                .chart-hint {
                    font-family: var(--font-mono); font-size: 10px; font-weight: 700;
                    color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em;
                }
                @media (max-width: 900px) {
                    .grid-2 { grid-template-columns: 1fr; }
                    .grid-3 { grid-template-columns: 1fr; }
                }
            </style>
            <div class="dashboard" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="onboarding-banner">
                    <h3>Welcome to AI Proxy</h3>
                    <p>Your unified AI Gateway for routing, rate limiting, and cost tracking.</p>
                    <ol>
                        <li><strong>Create an API Key</strong> in the <em>API Keys</em> panel &mdash; you'll get a <code>sk-keel-*</code> virtual key</li>
                        <li><strong>Send requests</strong> via OpenAI Chat, OpenAI Responses, or Anthropic Messages protocol in <em>Playground</em></li>
                        <li><strong>Monitor costs</strong> here on this Dashboard, and check <em>Groups</em> for upstream health</li>
                    </ol>
                </div>
                <keel-stat-grid data-ref="stats"></keel-stat-grid>
                <div class="grid-3">
                    <div class="section-card">
                        <div class="chart-header">
                            <h3 class="section-title">Request Volume</h3>
                            <span class="chart-hint">7 Days</span>
                        </div>
                        <keel-chart data-ref="volumeChart"></keel-chart>
                    </div>
                    <div class="section-card">
                        <div class="chart-header">
                            <h3 class="section-title">Model Split</h3>
                            <span class="chart-hint">By Requests</span>
                        </div>
                        <keel-chart data-ref="modelChart"></keel-chart>
                    </div>
                    <div class="section-card">
                        <div class="chart-header">
                            <h3 class="section-title">Latency</h3>
                            <span class="chart-hint">Recent P50</span>
                        </div>
                        <keel-chart data-ref="latencyChart"></keel-chart>
                    </div>
                </div>
                <div class="grid-2">
                    <div class="section-card">
                        <h3 class="section-title">Top Models</h3>
                        <keel-data-table data-ref="modelsTable"></keel-data-table>
                    </div>
                    <div class="section-card">
                        <h3 class="section-title">Top Users</h3>
                        <keel-data-table data-ref="usersTable"></keel-data-table>
                    </div>
                </div>
                <div class="section-card">
                    <h3 class="section-title">Recent Requests</h3>
                    <keel-data-table data-ref="recentTable"></keel-data-table>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._liveMode = true;
        this._sse = null;
        this._pollTimer = null;
        this.refs.hero.render({ label: 'Telemetry Overview', title: 'Dashboard', metaHtml: '' });
    }

    connectedCallback() {
        super.connectedCallback();
        // Start live updates after mount
        setTimeout(() => this._startLive(), 500);
    }

    disconnectedCallback() {
        this._stopLive();
    }

    /** Called by app.js live indicator toggle */
    setLiveMode(on) {
        this._liveMode = on;
        if (on) this._startLive();
        else this._stopLive();
    }

    _startLive() {
        this._stopLive();
        if (!this._liveMode) return;
        this.refresh();
        // Try SSE first, fall back to polling
        try {
            const base = API.airelay || API.token;
            this._sse = new EventSource(`${base}/usage/stream`);
            this._sse.onmessage = (e) => {
                try {
                    const payload = JSON.parse(e.data);
                    if ((!payload._records || payload._records.length === 0) && this._lastDetailedRecords?.length) {
                        payload._records = this._lastDetailedRecords;
                    }
                    this._render(payload);
                } catch {}
            };
            this._sse.onerror = () => {
                this._sse.close();
                this._sse = null;
                this._startPolling();
            };
        } catch {
            this._startPolling();
        }
    }

    _startPolling() {
        this._stopPolling();
        this._pollTimer = setInterval(() => this.refresh(), 15000);
    }

    _stopPolling() {
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
    }

    _stopLive() {
        if (this._sse) { this._sse.close(); this._sse = null; }
        this._stopPolling();
    }

    async refresh() {
        // Use the records endpoint which returns the full per-row TokenUsage + CostBreakdown.
        try {
            const records = await requestJson(`${API.token}/admin/usage/records?limit=200`);
            const data = await requestJson(`${API.token}/admin/usage/global`);
            this._lastDetailedRecords = records.records || records;
            this._render({ ...data, _records: this._lastDetailedRecords });
        } catch (e) {
            this.refs.stats.render({ entries: [['Status', 'Error', e.message]] });
        }
    }

    _render(data) {
        const silent = !!this._hasRendered;
        this._hasRendered = true;

        const records = data._records || data.recentRequests || [];
        if (data._records?.length) this._lastDetailedRecords = data._records;
        const sums = this._aggregate(records);

        this.refs.stats.render({
            silent,
            entries: [
                ['Total Requests', String(data.totalRequests || 0), 'all-time'],
                ['Total Cost', `$${(data.totalCostUsd || 0).toFixed(4)}`, 'USD'],
                ['Input', (sums.inputTokens || 0).toLocaleString(), 'tokens'],
                ['Output', (sums.outputTokens || 0).toLocaleString(), 'tokens'],
                ['Cache Read', (sums.cacheReadInputTokens || 0).toLocaleString(), 'tokens'],
                ['Cache Write', (sums.cacheCreationInputTokens || 0).toLocaleString(), 'tokens'],
                ['Reasoning', (sums.reasoningTokens || 0).toLocaleString(), 'tokens'],
                ['Cache Hit', sums.cacheHitRate != null ? `${(sums.cacheHitRate * 100).toFixed(1)}%` : '—', 'rate'],
            ]
        });
        this.refs.hero.render({
            label: 'Telemetry Overview',
            title: 'Dashboard',
            metaHtml: `
                <div class="hero-meta">
                    <span class="hero-chip">${data.totalRequests || 0} requests</span>
                    <span class="hero-chip">$${(data.totalCostUsd || 0).toFixed(4)} total cost</span>
                </div>
            `
        });

        // ── Charts ──
        const recent = data.recentRequests || records.slice(0, 20);
        const dayBuckets = this._bucketByDay(records, 7);
        this.refs.volumeChart.render({
            type: 'bar',
            data: dayBuckets.map(d => d.count),
            labels: dayBuckets.map(d => d.label),
            height: 100,
            emptyText: 'No request data yet'
        });

        const models = data.topModels || [];
        this.refs.modelChart.render({
            type: 'donut',
            data: models.slice(0, 6).map(m => m.requests),
            labels: models.slice(0, 6).map(m => m.model),
            emptyText: 'No model data yet'
        });

        const latencies = recent.slice(0, 30).map(r => r.latencyMs || 0).reverse();
        this.refs.latencyChart.render({
            type: 'sparkline',
            data: latencies,
            emptyText: 'No latency data yet'
        });

        // ── Tables ──
        this.refs.modelsTable.render({
            silent,
            headers: ['Model', 'Requests', 'Tokens', 'Cost'],
            rows: models.map(m => [
                `<code>${m.model}</code>`,
                String(m.requests),
                m.totalTokens.toLocaleString(),
                `$${m.totalCostUsd.toFixed(4)}`
            ]),
            emptyHtml: '<div class="empty">No model data yet.</div>'
        });

        const users = data.topUsers || [];
        this.refs.usersTable.render({
            silent,
            headers: ['User ID', 'Requests', 'Tokens', 'Cost'],
            rows: users.map(u => [
                `<code>${u.userId}</code>`,
                String(u.requests),
                u.totalTokens.toLocaleString(),
                `$${u.totalCostUsd.toFixed(4)}`
            ]),
            emptyHtml: '<div class="empty">No user data yet.</div>'
        });

        this.refs.recentTable.render({
            silent,
            headers: ['Time', 'Model', 'In', 'Out', 'CR', 'CW', 'Reason', 'In$', 'Out$', 'CW$', 'CR$', 'Total$', 'Hit', 'Latency'],
            rows: recent.slice(0, 20).map(r => {
                const u = r.usage || {};
                const c = r.cost || {};
                return [
                    `<span style="font-size:11px;">${(r.createdAt || '').slice(0, 19)}</span>`,
                    `<code>${r.model || ''}</code>`,
                    this._num(u.promptTokens),
                    this._num(u.completionTokens),
                    this._num(u.cacheReadInputTokens),
                    this._num(u.cacheCreationInputTokens),
                    this._num(u.reasoningTokens),
                    `$${(c.inputCostUsd || 0).toFixed(5)}`,
                    `$${(c.outputCostUsd || 0).toFixed(5)}`,
                    `$${(c.cacheWriteCostUsd || 0).toFixed(5)}`,
                    `$${(c.cacheReadCostUsd || 0).toFixed(5)}`,
                    `$${(c.totalCostUsd || 0).toFixed(5)}`,
                    c.cacheHitRate != null ? `${(c.cacheHitRate * 100).toFixed(0)}%` : '—',
                    `${r.latencyMs || 0}ms`
                ];
            }),
            emptyHtml: '<div class="empty">No requests recorded yet. Create an API key and send a request in Playground.</div>'
        });
    }

    _num(n) {
        if (n == null) return '0';
        if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
        return String(n);
    }

    _aggregate(records) {
        return records.reduce((acc, r) => {
            const u = r.usage || {};
            acc.inputTokens += u.promptTokens || 0;
            acc.outputTokens += u.completionTokens || 0;
            acc.cacheReadInputTokens += u.cacheReadInputTokens || 0;
            acc.cacheCreationInputTokens += u.cacheCreationInputTokens || 0;
            acc.reasoningTokens += u.reasoningTokens || 0;
            if (r.cost?.cacheHitRate != null) {
                acc._hitSamples += 1;
                acc._hitSum += r.cost.cacheHitRate;
            }
            return acc;
        }, { inputTokens: 0, outputTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0, reasoningTokens: 0, _hitSamples: 0, _hitSum: 0, get cacheHitRate() { return this._hitSamples > 0 ? this._hitSum / this._hitSamples : null; } });
    }

    _bucketByDay(requests, days) {
        const now = new Date();
        const buckets = [];
        for (let i = days - 1; i >= 0; i--) {
            const d = new Date(now);
            d.setDate(d.getDate() - i);
            const key = d.toISOString().slice(0, 10);
            const label = d.toLocaleDateString('en', { weekday: 'short' });
            const count = requests.filter(r => (r.createdAt || '').startsWith(key)).length;
            buckets.push({ key, label, count });
        }
        return buckets;
    }
}

customElements.define('ai-panel-dashboard', PanelDashboard);
