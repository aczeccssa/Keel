import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

const STATUS_STYLES = {
    HEALTHY:  { bg: 'rgba(19,130,79,0.12)', color: 'var(--green)', label: 'Healthy' },
    COOLDOWN: { bg: 'var(--amber-soft)', color: 'var(--amber)', label: 'Cooldown' },
    DEGRADED: { bg: 'var(--amber-soft)', color: 'var(--amber)', label: 'Degraded' },
    DISABLED: { bg: 'var(--red-soft)', color: 'var(--red)', label: 'Disabled' },
};

export class PanelPools extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .panel-layout { display: flex; flex-direction: column; gap: 28px; }
                .chain-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    box-shadow: var(--shadow-sm);
                    border: 2px solid var(--ink);
                    overflow: hidden;
                }
                .chain-header {
                    padding: 24px 28px;
                    background: var(--color-surface-container-low, #f3f1ed);
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                }
                .chain-name {
                    font-family: var(--font-headline);
                    font-size: 22px;
                    font-weight: 500;
                    margin: 0;
                    color: var(--ink);
                }
                .chain-aliases {
                    font-size: 12px;
                    color: var(--muted);
                    margin-top: 4px;
                    font-weight: 600;
                }
                .level-section {
                    border-top: 1px solid var(--line);
                }
                .level-header {
                    padding: 18px 28px;
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    background: rgba(255,255,255,0.5);
                }
                .level-title {
                    font-size: 14px;
                    font-weight: 800;
                    color: var(--ink);
                    letter-spacing: 0.04em;
                }
                .level-meta {
                    font-size: 11px;
                    color: var(--muted);
                    margin-left: 12px;
                    font-weight: 600;
                }
                .level-chips { display: flex; gap: 8px; }
                .chip {
                    display: inline-flex;
                    align-items: center;
                    gap: 4px;
                    padding: 4px 10px;
                    border-radius: 0;
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.06em;
                }
                .btn-reset {
                    padding: 6px 14px;
                    border: 1px solid var(--line-strong);
                    border-radius: 0;
                    background: transparent;
                    color: var(--ink);
                    font-size: 10px;
                    font-weight: 700;
                    letter-spacing: 0.08em;
                    text-transform: uppercase;
                    cursor: pointer;
                    transition: all 150ms ease;
                }
                .btn-reset:hover { background: var(--panel-strong); }
                .empty { text-align: center; color: var(--muted); padding: 48px; font-size: 13px; }
            </style>
            <div class="panel-layout" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>
                <div data-ref="chains"></div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label: 'Upstream Management', title: 'Pool Chains', metaHtml: '' });
    }

    async refresh() {
        try {
            const [health, config] = await Promise.all([
                requestJson(`${API.airelay}/admin/pools`),
                requestJson(`${API.airelay}/admin/config`)
            ]);
            this._render(health.chains || [], config);
        } catch (e) {
            this.refs.chains.innerHTML = `<div class="empty">Failed to load: ${e.message}</div>`;
        }
    }

    _render(chains, config) {
        this.refs.hero.render({
            label: 'Upstream Management',
            title: 'Pool Chains',
            metaHtml: `<span style="font-size:11px;font-weight:700;color:var(--muted);">${chains.length} chain${chains.length !== 1 ? 's' : ''}</span>`
        });

        if (chains.length === 0) {
            this.refs.chains.innerHTML = '<div class="empty">No pool chains configured.</div>';
            return;
        }

        let html = '';

        // Provider configuration section
        if (config && config.chains) {
            html += `<div class="chain-card" style="margin-bottom:28px;">
                <div class="chain-header">
                    <div>
                        <h3 class="chain-name">Provider Configuration</h3>
                        <div class="chain-aliases">Runtime configuration loaded from defaults (Phase 1)</div>
                    </div>
                </div>
                ${config.chains.map(chain => `
                    <div class="level-section">
                        <div class="level-header">
                            <div>
                                <span class="level-title">${escapeHtml(chain.chainId)}</span>
                                <span class="level-meta">Models: ${(chain.modelAliases || []).join(', ')}</span>
                            </div>
                        </div>
                        <keel-data-table data-config-chain="${chain.chainId}"></keel-data-table>
                    </div>
                `).join('')}
            </div>`;
        }

        // Health section
        html += chains.map(chain => `
            <div class="chain-card">
                <div class="chain-header">
                    <div>
                        <h3 class="chain-name">${escapeHtml(chain.chainId)}</h3>
                        <div class="chain-aliases">${(chain.modelAliases || []).map(a => `<code>${a}</code>`).join(', ')}</div>
                    </div>
                </div>
                ${(chain.levels || []).map(level => `
                    <div class="level-section">
                        <div class="level-header">
                            <div>
                                <span class="level-title">Level ${level.levelIndex}: ${escapeHtml(level.levelId)}</span>
                                <span class="level-meta">${escapeHtml(level.providerId)} &middot; ${level.protocol}</span>
                            </div>
                            <div class="level-chips">
                                ${level.healthyKeys > 0 ? `<span class="chip" style="background:${STATUS_STYLES.HEALTHY.bg};color:${STATUS_STYLES.HEALTHY.color};">${level.healthyKeys} healthy</span>` : ''}
                                ${level.cooldownKeys > 0 ? `<span class="chip" style="background:${STATUS_STYLES.COOLDOWN.bg};color:${STATUS_STYLES.COOLDOWN.color};">${level.cooldownKeys} cooldown</span>` : ''}
                                ${level.degradedKeys > 0 ? `<span class="chip" style="background:${STATUS_STYLES.DEGRADED.bg};color:${STATUS_STYLES.DEGRADED.color};">${level.degradedKeys} degraded</span>` : ''}
                                ${level.disabledKeys > 0 ? `<span class="chip" style="background:${STATUS_STYLES.DISABLED.bg};color:${STATUS_STYLES.DISABLED.color};">${level.disabledKeys} disabled</span>` : ''}
                            </div>
                        </div>
                        <keel-data-table data-chain="${chain.chainId}" data-level="${level.levelId}"></keel-data-table>
                    </div>
                `).join('')}
            </div>
        `).join('');

        this.refs.chains.innerHTML = html;

        // Render config tables
        if (config && config.chains) {
            config.chains.forEach(chain => {
                const table = this.shadowRoot.querySelector(`keel-data-table[data-config-chain="${chain.chainId}"]`);
                if (!table) return;
                const rows = [];
                (chain.levels || []).forEach(level => {
                    (level.keys || []).forEach(key => {
                        rows.push([
                            `L${level.levelIndex}`,
                            `<code>${level.provider?.providerId || ''}</code>`,
                            `<code>${level.provider?.protocol || ''}</code>`,
                            `<code>${level.provider?.baseUrl || ''}</code>`,
                            `<code>${key.keyId}</code>`,
                            String(key.maxConcurrency || 10),
                            String(key.weight || 100)
                        ]);
                    });
                });
                table.render({
                    headers: ['Level', 'Provider', 'Protocol', 'Base URL', 'Key ID', 'Max Concurrency', 'Weight'],
                    rows,
                    emptyHtml: '<div class="empty">No keys configured.</div>'
                });
            });
        }

        chains.forEach(chain => {
            (chain.levels || []).forEach(level => {
                const table = this.shadowRoot.querySelector(`keel-data-table[data-chain="${chain.chainId}"][data-level="${level.levelId}"]`);
                if (!table) return;
                const keys = level.keys || [];
                table.render({
                    headers: ['Key ID', 'Status', 'Requests', 'Failures', 'Concurrency', 'Last Error', 'Action'],
                    rows: keys.map(k => {
                        const s = STATUS_STYLES[k.status] || STATUS_STYLES.HEALTHY;
                        return [
                            `<code style="font-size:11px;">${k.keyId}</code>`,
                            `<span class="chip" style="background:${s.bg};color:${s.color};">${s.label}</span>`,
                            String(k.totalRequests || 0),
                            String(k.totalFailures || 0),
                            String(k.currentConcurrency || 0),
                            `<span style="font-size:11px;color:var(--muted);">${k.lastError || 'none'}</span>`,
                            k.status !== 'HEALTHY' ? `<button class="btn-reset" data-reset-chain="${chain.chainId}" data-reset-key="${k.keyId}">Reset</button>` : ''
                        ];
                    }),
                    emptyHtml: '<div class="empty">No keys in this level.</div>'
                });
            });
        });

        this.shadowRoot.querySelectorAll('[data-reset-key]').forEach(btn => {
            btn.addEventListener('click', async () => {
                try {
                    await postJson(`${API.airelay}/admin/pools/${btn.dataset.resetChain}/keys/${btn.dataset.resetKey}/reset`, {});
                    this.refresh();
                } catch (e) { alert(e.message); }
            });
        });
    }
}

customElements.define('ai-panel-pools', PanelPools);
