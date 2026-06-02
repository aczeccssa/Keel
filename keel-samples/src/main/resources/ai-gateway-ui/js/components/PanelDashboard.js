import { KeelElement } from '/js/components/base/KeelElement.js';
import { requestJson } from '../api.js';
import { API } from '../config.js';

export class PanelDashboard extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .dashboard { display: flex; flex-direction: column; gap: 32px; }
                .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
                .section-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 28px;
                    box-shadow: var(--shadow-sm);
                    border: 1px solid rgba(17, 24, 39, 0.04);
                }
                .section-title {
                    font-family: var(--font-headline);
                    font-size: 20px;
                    font-weight: 500;
                    margin: 0 0 20px;
                    color: var(--ink);
                }
                .onboarding-banner {
                    background: linear-gradient(135deg, rgba(15, 118, 110, 0.08), rgba(197, 106, 27, 0.06));
                    border-radius: var(--radius-lg);
                    padding: 28px 32px;
                    line-height: 1.7;
                    font-size: 13px;
                    color: var(--ink);
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
                    background: rgba(15, 23, 42, 0.08);
                    padding: 1px 6px;
                    border-radius: 4px;
                    font-family: var(--font-mono);
                    font-size: 11px;
                }
                .empty { text-align: center; color: var(--muted); padding: 40px; font-size: 13px; }
            </style>
            <div class="dashboard" data-ref="root">
                <div class="onboarding-banner">
                    <h3>Welcome to AI Proxy</h3>
                    <p>Your unified AI Gateway for routing, rate limiting, and cost tracking.</p>
                    <ol>
                        <li><strong>Create an API Key</strong> in the <em>API Keys</em> panel &mdash; you'll get a <code>sk-keel-*</code> virtual key</li>
                        <li><strong>Send requests</strong> via OpenAI Chat, OpenAI Responses, or Anthropic Messages protocol in <em>Playground</em></li>
                        <li><strong>Monitor costs</strong> here on this Dashboard, and check <em>Pool Chains</em> for upstream health</li>
                    </ol>
                </div>
                <keel-stat-grid data-ref="stats"></keel-stat-grid>
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

    async refresh() {
        try {
            const data = await requestJson(`${API.token}/admin/usage/global`);
            this._render(data);
        } catch (e) {
            this.refs.stats.render({ entries: [['Status', 'Error', e.message]] });
        }
    }

    _render(data) {
        this.refs.stats.render({
            entries: [
                ['Total Requests', String(data.totalRequests || 0), 'all-time'],
                ['Total Cost', `$${(data.totalCostUsd || 0).toFixed(4)}`, 'USD'],
                ['Total Tokens', (data.totalTokens || 0).toLocaleString(), 'across all models'],
                ['Top Model', data.topModels?.[0]?.model || 'n/a', `${data.topModels?.[0]?.requests || 0} requests`],
            ]
        });

        const models = data.topModels || [];
        this.refs.modelsTable.render({
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
            headers: ['User ID', 'Requests', 'Tokens', 'Cost'],
            rows: users.map(u => [
                `<code>${u.userId}</code>`,
                String(u.requests),
                u.totalTokens.toLocaleString(),
                `$${u.totalCostUsd.toFixed(4)}`
            ]),
            emptyHtml: '<div class="empty">No user data yet.</div>'
        });

        const recent = data.recentRequests || [];
        this.refs.recentTable.render({
            headers: ['Time', 'Model', 'Provider', 'Status', 'Tokens', 'Cost', 'Latency'],
            rows: recent.slice(0, 20).map(r => [
                `<span style="font-size:11px;">${(r.createdAt || '').slice(0, 19)}</span>`,
                `<code>${r.model || ''}</code>`,
                r.provider || '',
                r.status >= 400
                    ? `<span style="color:var(--red);font-weight:700;">${r.status}</span>`
                    : `<span style="color:var(--green);font-weight:700;">${r.status}</span>`,
                (r.totalTokens || 0).toLocaleString(),
                `$${(r.totalCostUsd || 0).toFixed(6)}`,
                `${r.latencyMs || 0}ms`
            ]),
            emptyHtml: '<div class="empty">No requests recorded yet. Create an API key and send a request in Playground.</div>'
        });
    }
}

customElements.define('ai-panel-dashboard', PanelDashboard);
