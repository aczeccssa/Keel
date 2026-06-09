import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

function esc(v) { return escapeHtml(String(v ?? '')); }
function tag(text, cls = '') { return `<span class="tag ${cls}">${esc(text)}</span>`; }

const ST = {
    HEALTHY:  { cls: 'st-ok',   label: 'Healthy' },
    COOLDOWN: { cls: 'st-warn', label: 'Cooldown' },
    DEGRADED: { cls: 'st-warn', label: 'Degraded' },
    DISABLED: { cls: 'st-bad',  label: 'Disabled' },
};

export class PanelPools extends KeelElement {
    hostStyles() { return 'height:100%'; }

    template() {
        return `
        <style>
            :host { font-family: var(--font-body); color: var(--ink); }
            .layout { display: grid; gap: 24px; }
            /* hero right side */
            .hero-stat {
                display: grid; align-items: center; justify-content: center;
                padding: 12px 24px; min-width: 160px;
                font-family: var(--font-headline); font-size: 48px; line-height: 1;
                letter-spacing: -0.04em; color: var(--red);
            }
            /* card */
            .card {
                background: var(--paper); border: 2px solid var(--ink);
                overflow: hidden;
            }
            .card-head {
                display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px;
                padding: 16px 20px;
                background: var(--ink); color: var(--paper);
            }
            .card-title {
                font-family: var(--font-headline); font-size: 24px; font-weight: 900;
                line-height: 1; letter-spacing: -0.03em; text-transform: uppercase; margin: 0;
            }
            .card-head-right {
                display: flex; gap: 8px; flex-wrap: wrap;
            }
            .tag {
                display: inline-block; padding: 3px 8px;
                font-family: var(--font-mono); font-size: 10px; font-weight: 800;
                letter-spacing: 0.06em; text-transform: uppercase;
                border: 1px solid var(--ink); background: var(--paper); color: var(--ink);
            }
            .card-body { padding: 0; }
            /* level */
            .level { border-top: 1px solid var(--ink); }
            .level-top {
                display: grid; grid-template-columns: 52px 1fr auto; align-items: center;
            }
            .lvl-idx {
                display: flex; align-items: center; justify-content: center;
                height: 100%; min-height: 56px;
                background: var(--bg); border-right: 1px solid var(--ink);
                font-family: var(--font-headline); font-size: 18px; font-weight: 900; color: var(--red);
            }
            .lvl-main { padding: 14px 16px; min-width: 0; }
            .lvl-name {
                font-family: var(--font-headline); font-size: 16px; font-weight: 900;
                text-transform: uppercase; letter-spacing: -0.02em; margin: 0; line-height: 1;
            }
            .lvl-meta {
                display: flex; flex-wrap: wrap; gap: 6px 14px; margin-top: 6px;
                font-family: var(--font-mono); font-size: 11px; font-weight: 700;
                color: var(--muted); text-transform: uppercase; letter-spacing: 0.04em;
            }
            .lvl-meta code {
                background: var(--bg); border: 1px solid var(--ink); padding: 1px 6px;
                font-family: var(--font-mono); font-size: 11px; font-weight: 800; color: var(--ink);
            }
            .lvl-chips {
                display: flex; gap: 6px; padding: 0 16px; flex-shrink: 0;
            }
            .st {
                display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px;
                border: 1px solid var(--ink); background: var(--paper); color: var(--ink);
                font-family: var(--font-mono); font-size: 10px; font-weight: 800;
                text-transform: uppercase; letter-spacing: 0.06em; white-space: nowrap;
            }
            .st-ok { background: #d8ecdd; }
            .st-warn { background: #f7e6cf; }
            .st-bad { background: var(--red); color: var(--paper); }
            /* keys table inside level */
            .keys-table {
                border-top: 1px solid var(--ink);
            }
            .keys-table table {
                width: 100%; border-collapse: collapse;
                font-family: var(--font-mono); font-size: 11px;
            }
            .keys-table th {
                text-align: left; padding: 8px 12px;
                font-size: 9px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                background: var(--paper); border-bottom: 1px solid var(--ink); color: var(--muted);
            }
            .keys-table td {
                padding: 9px 12px; border-bottom: 1px solid var(--ink); vertical-align: middle;
            }
            .keys-table tr:last-child td { border-bottom: 0; }
            .keys-table code {
                font-family: var(--font-mono); font-size: 10.5px; font-weight: 800;
                background: var(--ink); color: var(--paper); padding: 2px 6px;
                text-transform: uppercase; letter-spacing: 0.04em;
            }
            .keys-table samp {
                font-family: var(--font-mono); font-size: 10px; color: var(--muted);
                text-transform: uppercase; letter-spacing: 0.04em;
            }
            .btn-reset {
                padding: 5px 10px; border: 1px solid var(--ink); background: var(--paper); color: var(--ink);
                font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                text-transform: uppercase; letter-spacing: 0.08em; cursor: pointer;
            }
            .btn-reset:hover { background: var(--red); color: var(--paper); border-color: var(--red); }
            /* config section */
            .config-section { border-top: 2px solid var(--ink); }
            .config-head {
                display: flex; align-items: center; justify-content: space-between;
                padding: 12px 20px; background: #ebe9e3;
                font-family: var(--font-mono); font-size: 11px; font-weight: 800;
                text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted);
            }
            .config-table table {
                width: 100%; border-collapse: collapse;
                font-family: var(--font-mono); font-size: 11px;
            }
            .config-table th {
                text-align: left; padding: 8px 12px;
                font-size: 9px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                background: var(--paper); border-bottom: 1px solid var(--ink); color: var(--muted);
            }
            .config-table td {
                padding: 8px 12px; border-bottom: 1px solid var(--ink); vertical-align: middle;
            }
            .config-table tr:last-child td { border-bottom: 0; }
            .config-table code {
                font-family: var(--font-mono); font-size: 10.5px; font-weight: 800;
                background: var(--ink); color: var(--paper); padding: 2px 6px;
                text-transform: uppercase; letter-spacing: 0.04em;
            }
            .empty {
                padding: 48px 20px; text-align: center;
                font-family: var(--font-mono); font-size: 12px; font-weight: 700;
                color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em;
            }
        </style>
        <div class="layout" data-ref="root">
            <keel-hero data-ref="hero"></keel-hero>
            <div data-ref="chains"></div>
        </div>`;
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
            this.refs.chains.innerHTML = `<div class="empty">Failed to load: ${esc(e.message)}</div>`;
        }
    }

    _render(chains, config) {
        this.refs.hero.render({
            label: 'Upstream Management',
            title: 'Pool Chains',
            metaHtml: `<div class="hero-stat">${chains.length}</div>`
        });

        if (!chains.length && !config?.chains?.length) {
            this.refs.chains.innerHTML = '<div class="empty">No pool chains configured.</div>';
            return;
        }

        let html = '';

        // Config overview
        if (config?.chains?.length) {
            const allKeys = config.chains.flatMap(c => (c.levels || []).flatMap(l => l.keys || []));
            html += `<div class="card">
                <header class="card-head">
                    <h3 class="card-title">Provider Configuration</h3>
                    <div class="card-head-right">
                        ${tag(`${config.chains.length} chains`)}
                        ${tag(`${allKeys.length} keys`)}
                    </div>
                </header>`;
            config.chains.forEach(chain => {
                html += `<div class="config-section">
                    <div class="config-head">
                        <span>${esc(chain.chainId)}</span>
                        <span>Models: ${(chain.modelAliases || []).join(', ') || '—'}</span>
                    </div>
                    <div class="config-table" data-cfg="${esc(chain.chainId)}"></div>
                </div>`;
            });
            html += `</div>`;
        }

        // Live health chains
        chains.forEach(chain => {
            const levels = chain.levels || [];
            const allKeys = levels.flatMap(l => l.keys || []);
            const healthy = allKeys.filter(k => k.status === 'HEALTHY').length;

            html += `<div class="card">
                <header class="card-head">
                    <h3 class="card-title">${esc(chain.chainId)}</h3>
                    <div class="card-head-right">
                        ${(chain.modelAliases || []).map(a => tag(a)).join('') || tag('no aliases')}
                        ${tag(`${levels.length} levels`)}
                        ${tag(`${allKeys.length} keys`)}
                    </div>
                </header>
                <div class="card-body">`;

            if (!levels.length) {
                html += `<div class="empty">No levels configured.</div>`;
            }

            levels.forEach(level => {
                const chips = [];
                if (level.healthyKeys > 0) chips.push(`<span class="st st-ok">${level.healthyKeys} healthy</span>`);
                if (level.cooldownKeys > 0) chips.push(`<span class="st st-warn">${level.cooldownKeys} cooldown</span>`);
                if (level.degradedKeys > 0) chips.push(`<span class="st st-warn">${level.degradedKeys} degraded</span>`);
                if (level.disabledKeys > 0) chips.push(`<span class="st st-bad">${level.disabledKeys} disabled</span>`);

                html += `<div class="level">
                    <div class="level-top">
                        <div class="lvl-idx">L${level.levelIndex}</div>
                        <div class="lvl-main">
                            <div class="lvl-name">${esc(level.levelId)}</div>
                            <div class="lvl-meta">
                                <span>Provider: <code>${esc(level.providerId)}</code></span>
                                <span>Protocol: <code>${esc(level.protocol)}</code></span>
                            </div>
                        </div>
                        <div class="lvl-chips">${chips.join('')}</div>
                    </div>
                    <div class="keys-table" data-hl="${esc(chain.chainId)}-${esc(level.levelId)}"></div>
                </div>`;
            });

            html += `</div></div>`;
        });

        this.refs.chains.innerHTML = html;

        // Render config tables
        if (config?.chains?.length) {
            config.chains.forEach(chain => {
                const el = this.shadowRoot.querySelector(`[data-cfg="${CSS.escape(chain.chainId)}"]`);
                if (!el) return;
                const rows = (chain.levels || []).flatMap(level =>
                    (level.keys || []).map(key => [
                        tag(`L${level.levelIndex}`),
                        `<code>${esc(level.provider?.providerId || '')}</code>`,
                        `<code>${esc(level.provider?.protocol || '')}</code>`,
                        `<samp>${esc(level.provider?.baseUrl || '')}</samp>`,
                        `<code>${esc(key.keyId)}</code>`,
                        `<span>${key.maxConcurrency || 10}</span>`,
                        `<span>${key.weight || 100}</span>`,
                    ])
                );
                el.innerHTML = rows.length
                    ? `<table>
                        <thead><tr><th>Level</th><th>Provider</th><th>Protocol</th><th>Base URL</th><th>Key ID</th><th>Max Concurrency</th><th>Weight</th></tr></thead>
                        <tbody>${rows.map(r => `<tr>${r.map(c => `<td>${c}</td>`).join('')}</tr>`).join('')}</tbody>
                    </table>`
                    : '<div class="empty">No keys configured.</div>';
            });
        }

        // Render health key tables
        chains.forEach(chain => {
            (chain.levels || []).forEach(level => {
                const el = this.shadowRoot.querySelector(`[data-hl="${CSS.escape(chain.chainId)}-${CSS.escape(level.levelId)}"]`);
                if (!el) return;
                const keys = level.keys || [];
                if (!keys.length) {
                    el.innerHTML = '<div class="empty">No keys.</div>';
                    return;
                }
                el.innerHTML = `<table>
                    <thead><tr><th>Key ID</th><th>Status</th><th>Requests</th><th>Failures</th><th>Concurrency</th><th>Last Error</th><th></th></tr></thead>
                    <tbody>${keys.map(k => {
                        const s = ST[k.status] || ST.HEALTHY;
                        return `<tr>
                            <td><code>${esc(k.keyId)}</code></td>
                            <td><span class="st ${s.cls}">${s.label}</span></td>
                            <td><span>${k.totalRequests || 0}</span></td>
                            <td><span>${k.totalFailures || 0}</span></td>
                            <td><span>${k.currentConcurrency || 0}</span></td>
                            <td><samp>${esc(k.lastError || 'none')}</samp></td>
                            <td>${k.status !== 'HEALTHY'
                                ? `<button class="btn-reset" data-rc="${esc(chain.chainId)}" data-rk="${esc(k.keyId)}">Reset</button>`
                                : ''}</td>
                        </tr>`;
                    }).join('')}</tbody>
                </table>`;
            });
        });

        // Reset buttons
        this.shadowRoot.querySelectorAll('[data-rk]').forEach(btn => {
            btn.addEventListener('click', async () => {
                try {
                    await postJson(`${API.airelay}/admin/pools/${btn.dataset.rc}/keys/${btn.dataset.rk}/reset`, {});
                    this.refresh();
                } catch (e) { alert(e.message); }
            });
        });
    }
}

customElements.define('ai-panel-pools', PanelPools);
