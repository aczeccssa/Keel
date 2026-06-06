import { KeelElement } from './base/KeelElement.js';
import { escapeHtml } from '../utils.js';

export class PanelAiGateway extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <style>
                .panel-root {
                    display: flex;
                    flex-direction: column;
                    height: 100%;
                    overflow-y: auto;
                    padding: 32px 48px;
                    background: transparent;
                }
                .panel-header {
                    margin-bottom: 32px;
                }
                .panel-header h1 {
                    font-family: "Newsreader", serif;
                    font-size: 36px;
                    margin: 8px 0;
                    color: var(--ink);
                    font-weight: 400;
                }
                .panel-header p {
                    color: var(--muted);
                    margin: 0;
                    max-width: 600px;
                    line-height: 1.5;
                    font-size: 14px;
                }
                .bento-grid {
                    display: grid;
                    grid-template-columns: repeat(12, 1fr);
                    gap: 24px;
                    margin-bottom: 48px;
                }
                .bento-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 32px;
                    box-shadow: var(--shadow-sm);
                    border: 1px solid var(--line);
                    display: flex;
                    flex-direction: column;
                }
                .bento-card.col-3 { grid-column: span 3; }
                .bento-card.col-4 { grid-column: span 4; }
                .bento-card.col-6 { grid-column: span 6; }
                .bento-card.col-8 { grid-column: span 8; }
                .bento-card.col-12 { grid-column: span 12; }
                .card-title {
                    font-family: "Newsreader", serif;
                    font-size: 20px;
                    color: var(--ink);
                    margin: 0 0 4px 0;
                }
                .card-subtitle {
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.1em;
                    color: var(--muted);
                    margin: 0 0 24px 0;
                }
                .stat-value {
                    font-size: 36px;
                    font-weight: 300;
                    color: var(--ink);
                }
                .stat-value span {
                    font-size: 16px;
                    margin-left: 4px;
                }
                .stat-sub {
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.05em;
                    color: var(--teal);
                    margin-top: 8px;
                }
                .table-section {
                    border-radius: var(--radius-lg);
                    border: 1px solid var(--line);
                    margin-bottom: 24px;
                }
                .table-section-header {
                    padding: 24px 32px;
                    border-bottom: 1px solid var(--line);
                    background: var(--bg);
                }
                .table-section-title {
                    font-family: "Newsreader", serif;
                    font-size: 20px;
                    color: var(--ink);
                    margin: 0;
                    font-weight: 500;
                }
                .table-wrap {
                    width: 100%;
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    text-align: left;
                }
                th {
                    padding: 12px 24px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.1em;
                    color: var(--muted);
                    background: var(--bg);
                    border-bottom: 1px solid var(--line);
                }
                th.right { text-align: right; }
                td {
                    padding: 14px 24px;
                    font-size: 13px;
                    border-bottom: 1px solid var(--line);
                    color: var(--ink);
                }
                td.right { text-align: right; font-weight: 500; font-family: ui-monospace, monospace; font-size: 12px; }
                tr:last-child td { border-bottom: none; }
                tr:hover { background: var(--line); }
                .badge {
                    display: inline-block;
                    padding: 2px 8px;
                    border-radius: 4px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.05em;
                }
                .badge.healthy { background: rgba(16,185,129,0.12); color: #047857; }
                .badge.cooldown { background: rgba(245,158,11,0.12); color: #b45309; }
                .badge.degraded { background: rgba(245,158,11,0.18); color: #92400e; }
                .badge.disabled { background: rgba(239,68,68,0.12); color: #b91c1c; }
                .empty { text-align: center; color: var(--muted); padding: 48px; font-size: 14px; }
                @media (max-width: 1024px) {
                    .bento-card.col-3,
                    .bento-card.col-4,
                    .bento-card.col-6,
                    .bento-card.col-8,
                    .bento-card.col-12 { grid-column: span 12; }
                }
            </style>
            <div class="panel-root">
                <header class="panel-header">
                    <h1>AI Gateway</h1>
                    <p>Real-time cost, pool health, rate limits, and usage across the AI relay system.</p>
                </header>
                <div class="bento-grid" data-ref="statCards"></div>
                <div data-ref="poolSection"></div>
                <div data-ref="rateLimitSection"></div>
                <div data-ref="usageSection"></div>
            </div>
        `;
    }

    render(appState) {
        this.ensureInitialized();
        const snap = appState.aiGateway;
        if (!snap) {
            this.refs.statCards.innerHTML = '<div class="empty" style="grid-column:1/-1;">AI Gateway snapshot not loaded yet.</div>';
            this.refs.poolSection.innerHTML = '';
            this.refs.rateLimitSection.innerHTML = '';
            this.refs.usageSection.innerHTML = '';
            return;
        }
        const cost = snap.costSummary || {};
        this.refs.statCards.innerHTML = [
            ['Total Requests', String(cost.totalRequests || 0), 'all-time'],
            ['Last 1h Cost', `$${(cost.last1hUsd || 0).toFixed(4)}`, 'USD'],
            ['Last 24h Cost', `$${(cost.last24hUsd || 0).toFixed(4)}`, 'USD'],
            ['Avg Latency', `${cost.avgLatencyMs || 0}ms`, 'end-to-end']
        ].map(([label, value, hint]) => `
            <div class="bento-card col-3">
                <div class="card-title">${escapeHtml(label)}</div>
                <div class="stat-value">${escapeHtml(value)}</div>
                <div class="stat-sub">${escapeHtml(hint)}</div>
            </div>
        `).join('');

        const pools = snap.poolHealth || [];
        this.refs.poolSection.innerHTML = pools.length === 0
            ? '<div class="empty">No pool chains configured.</div>'
            : pools.map(chain => {
                const allKeys = (chain.levels || []).flatMap(l => l.keys || []);
                const rows = allKeys.map(key => `
                    <tr>
                        <td>${escapeHtml(chain.chainId)}</td>
                        <td><code>${escapeHtml(key.keyId)}</code></td>
                        <td><span class="badge ${badgeClass(key.status)}">${escapeHtml(key.status)}</span></td>
                        <td class="right">${key.totalRequests || 0}</td>
                        <td class="right">${key.totalFailures || 0}</td>
                        <td class="right">${key.currentConcurrency || 0}</td>
                        <td>${escapeHtml(key.lastError || 'none')}</td>
                    </tr>
                `).join('');
                return `
                    <div class="table-section">
                        <div class="table-section-header">
                            <h3 class="table-section-title">${escapeHtml(chain.chainId)}: ${(chain.modelAliases || []).join(', ')}</h3>
                        </div>
                        <div class="table-wrap">
                            <table>
                                <thead><tr><th>Chain</th><th>Key</th><th>Status</th><th class="right">Requests</th><th class="right">Failures</th><th class="right">Concurrency</th><th>Last Error</th></tr></thead>
                                <tbody>${rows}</tbody>
                            </table>
                        </div>
                    </div>
                `;
            }).join('');

        const rl = snap.rateLimitSnapshot || {};
        const buckets = rl.topBuckets || [];
        this.refs.rateLimitSection.innerHTML = `
            <div class="table-section">
                <div class="table-section-header">
                    <h3 class="table-section-title">Rate Limit Buckets (${rl.bucketCount || 0} active, ${rl.ruleCount || 0} rules)</h3>
                </div>
                <div class="table-wrap">
                    <table>
                        <thead><tr><th>Rule</th><th>Dimension</th><th>Value</th><th class="right">Allowed</th><th class="right">Rejected</th><th class="right">Remaining</th><th class="right">Capacity</th></tr></thead>
                        <tbody>${buckets.length === 0
                            ? '<tr><td colspan="7" class="empty">No active buckets.</td></tr>'
                            : buckets.map(b => `
                                <tr>
                                    <td><code>${escapeHtml(b.ruleId)}</code></td>
                                    <td>${escapeHtml(b.dimension)}</td>
                                    <td><code>${escapeHtml(b.value)}</code></td>
                                    <td class="right">${b.totalAllowed || 0}</td>
                                    <td class="right">${b.totalRejected || 0}</td>
                                    <td class="right">${b.remainingTokens ?? '-'}</td>
                                    <td class="right">${b.capacity ?? '-'}</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        `;

        const usage = snap.usage || {};
        const recent = usage.recentRequests || [];
        this.refs.usageSection.innerHTML = recent.length === 0
            ? '<div class="empty">No usage records yet.</div>'
            : `
                <div class="table-section">
                    <div class="table-section-header">
                        <h3 class="table-section-title">Recent Usage (${usage.totalRequests || 0} total, $${(usage.totalCostUsd || 0).toFixed(4)} spent)</h3>
                    </div>
                    <div class="table-wrap">
                        <table>
                            <thead><tr><th>Time</th><th>Model</th><th>Provider</th><th class="right">Tokens</th><th class="right">Cost</th><th class="right">Latency</th><th>Status</th></tr></thead>
                            <tbody>${recent.slice(0, 20).map(r => `
                                <tr>
                                    <td style="font-size:11px;">${escapeHtml(r.createdAt || '')}</td>
                                    <td><code>${escapeHtml(r.model)}</code></td>
                                    <td>${escapeHtml(r.provider)}</td>
                                    <td class="right">${r.totalTokens || 0}</td>
                                    <td class="right">$${(r.totalCostUsd || 0).toFixed(6)}</td>
                                    <td class="right">${r.latencyMs || 0}ms</td>
                                    <td><span class="badge ${r.status >= 400 ? 'disabled' : 'healthy'}">${r.status || 0}</span></td>
                                </tr>
                            `).join('')}</tbody>
                        </table>
                    </div>
                </div>
            `;
    }
}

function badgeClass(status) {
    const s = String(status || '').toUpperCase();
    if (s === 'HEALTHY') return 'healthy';
    if (s === 'COOLDOWN') return 'cooldown';
    if (s === 'DEGRADED') return 'degraded';
    if (s === 'DISABLED') return 'disabled';
    return 'cooldown';
}

if (!customElements.get('keel-panel-ai-gateway')) {
    customElements.define('keel-panel-ai-gateway', PanelAiGateway);
}
