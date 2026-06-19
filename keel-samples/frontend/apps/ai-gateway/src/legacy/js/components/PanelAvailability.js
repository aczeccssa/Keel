import { KeelElement } from './base/KeelElement.js';
import { requestJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

/**
 * Channel availability board. One card per channel showing 7-day availability,
 * conversation latency, endpoint ping, and a sparkline of the most recent tests.
 */
export class PanelAvailability extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .wrap { display:flex; flex-direction:column; gap:24px; }
                .grid { display:grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap:20px; }
                .card {
                    background: var(--panel-strong);
                    border: 2px solid var(--ink);
                    border-radius: var(--radius-lg);
                    box-shadow: var(--shadow-sm);
                    display:flex;
                    flex-direction:column;
                    transition: box-shadow 180ms ease, transform 180ms ease;
                }
                .card:hover { box-shadow: var(--shadow-md); transform: translateY(-2px); }
                .card-head {
                    display:flex;
                    align-items:flex-start;
                    gap:14px;
                    padding:20px 22px 18px;
                    border-bottom:2px solid var(--ink);
                }
                .signal {
                    width:44px;
                    height:44px;
                    border:2px solid var(--ink);
                    background:var(--paper);
                    color:var(--ink);
                    display:flex;
                    align-items:center;
                    justify-content:center;
                    flex-shrink:0;
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:800;
                    letter-spacing:.08em;
                    text-transform:uppercase;
                }
                .signal.ok { background:var(--green-soft); color:var(--green); }
                .signal.err { background:var(--red-soft); color:var(--red); }
                .head-main { flex:1; min-width:0; }
                .name {
                    font-family:var(--font-headline);
                    font-size:20px;
                    font-weight:500;
                    line-height:.95;
                    letter-spacing:-.04em;
                    color:var(--ink);
                    margin:0;
                    overflow:hidden;
                    text-overflow:ellipsis;
                    white-space:nowrap;
                    text-transform:uppercase;
                }
                .sub { margin-top:10px; display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
                .proto-tag {
                    border:1px solid var(--teal);
                    color:var(--teal);
                    background:transparent;
                    padding:3px 7px;
                    font-family:var(--font-mono);
                    font-size:9px;
                    font-weight:800;
                    letter-spacing:.09em;
                    text-transform:uppercase;
                }
                .model-name {
                    font-family:var(--font-mono);
                    font-size:11px;
                    font-weight:700;
                    color:var(--muted);
                    line-height:1.35;
                    overflow:visible;
                    white-space:normal;
                    word-break:break-word;
                    text-transform:uppercase;
                }
                .status-badge {
                    border:2px solid currentColor;
                    padding:5px 10px;
                    flex-shrink:0;
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:800;
                    letter-spacing:.08em;
                    text-transform:uppercase;
                }
                .status-badge.ok { color:var(--green); background:var(--green-soft); }
                .status-badge.warn { color:var(--amber); background:var(--amber-soft); }
                .status-badge.err { color:var(--red); background:var(--red-soft); }
                .card-body { display:grid; gap:16px; padding:18px 22px 22px; }
                .metrics { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
                .metric {
                    background:var(--paper);
                    border:2px solid var(--ink);
                    padding:14px 14px 13px;
                }
                .metric-label {
                    display:flex;
                    align-items:center;
                    gap:7px;
                    font-family:var(--font-mono);
                    font-size:9px;
                    font-weight:800;
                    color:var(--muted);
                    letter-spacing:.12em;
                    text-transform:uppercase;
                }
                .metric-label::before { content: ">"; color:var(--teal); }
                .metric-value {
                    margin-top:9px;
                    font-family:var(--font-headline);
                    font-size:30px;
                    font-weight:500;
                    line-height:.9;
                    letter-spacing:-.04em;
                    color:var(--ink);
                    font-feature-settings:'tnum';
                }
                .metric-value .unit {
                    font-family:var(--font-mono);
                    font-size:11px;
                    font-weight:800;
                    color:var(--muted);
                    margin-left:4px;
                    letter-spacing:.03em;
                }
                .avail-box {
                    display:grid;
                    grid-template-columns:minmax(0, 1fr) auto;
                    align-items:end;
                    gap:16px;
                    background:var(--paper);
                    border:2px solid var(--ink);
                    padding:16px;
                }
                .avail-label {
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:800;
                    color:var(--muted);
                    letter-spacing:.14em;
                    text-transform:uppercase;
                }
                .avail-extra {
                    margin-top:8px;
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:700;
                    color:var(--muted);
                    letter-spacing:.05em;
                    text-transform:uppercase;
                }
                .avail-value {
                    font-family:var(--font-headline);
                    font-size:clamp(36px, 5vw, 52px);
                    font-weight:500;
                    line-height:.82;
                    letter-spacing:-.06em;
                    font-feature-settings:'tnum';
                }
                .avail-value .pct {
                    font-family:var(--font-mono);
                    font-size:16px;
                    font-weight:800;
                    margin-left:4px;
                    letter-spacing:.02em;
                }
                .spark-card {
                    background:var(--surface-muted);
                    border:2px solid var(--ink);
                    padding:14px;
                }
                .spark-head {
                    display:flex;
                    align-items:center;
                    justify-content:space-between;
                    gap:12px;
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:800;
                    color:var(--muted);
                    margin-bottom:10px;
                    letter-spacing:.1em;
                    text-transform:uppercase;
                }
                .spark { display:flex; align-items:flex-end; gap:2px; height:30px; }
                .spark .bar {
                    flex:1;
                    min-width:2px;
                    max-width:7px;
                    height:22px;
                    border:1px solid transparent;
                }
                .spark .bar.ok { background:var(--green); }
                .spark .bar.fail { background:var(--red); }
                .spark .bar.idle {
                    background:var(--surface-strong);
                    border-color:var(--line);
                    height:7px;
                    align-self:center;
                }
                .spark-foot {
                    display:flex;
                    justify-content:space-between;
                    font-family:var(--font-mono);
                    font-size:9px;
                    font-weight:800;
                    color:var(--muted);
                    margin-top:8px;
                    text-transform:uppercase;
                    letter-spacing:.16em;
                }
                .empty {
                    background:var(--panel-strong);
                    border:2px solid var(--ink);
                    box-shadow:var(--shadow-sm);
                    text-align:center;
                    color:var(--muted);
                    padding:48px;
                    font-family:var(--font-mono);
                    font-size:12px;
                    font-weight:700;
                    letter-spacing:.06em;
                    text-transform:uppercase;
                }
                @media (max-width: 720px) {
                    .grid { grid-template-columns:1fr; }
                    .metrics, .avail-box { grid-template-columns:1fr; }
                    .avail-value { justify-self:start; }
                }
            </style>
            <div class="wrap">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="grid" data-ref="grid"></div>
            </div>
        `;
    }

    afterMount() {
        this._cardEls = new Map();
        this._layoutKey = '';
        this._heroCount = null;
        this.refs.hero.render({ label: 'Reliability', title: 'Availability', metaHtml: '' });
    }

    async refresh() {
        try {
            const [data, pools] = await Promise.all([
                requestJson(`${API.airelay}/admin/channels`),
                requestJson(`${API.airelay}/admin/pools`).catch(() => ({ chains: [] })),
            ]);
            const channels = data.channels || [];
            const items = channels.map((channel, index) => ({ key: this._channelKey(channel, index), channel }));
            const runtimeByChannel = this._runtimeStatusByChannel(pools);
            this._renderHeroCount(channels.length);
            if (channels.length === 0) {
                this._layoutKey = '';
                this._cardEls.clear();
                if (this.refs.grid.innerHTML !== '<div class="empty">No channels configured yet.</div>') {
                    this.refs.grid.innerHTML = '<div class="empty">No channels configured yet.</div>';
                }
                return;
            }
            this._ensureGrid(items);
            items.forEach(({ key, channel }) => this._patchCard(key, channel, null, runtimeByChannel.get(channel.channelId)));
            await Promise.all(items.map(async ({ key, channel }) => {
                if (!channel.channelId) {
                    this._patchCard(key, channel, null);
                    return;
                }
                let stats = null;
                try { stats = await requestJson(`${API.airelay}/admin/channels/${channel.channelId}/stats?window=7d`); } catch { stats = null; }
                this._patchCard(key, channel, stats, runtimeByChannel.get(channel.channelId));
            }));
        } catch (e) {
            this.refs.grid.innerHTML = `<div class="empty">Failed to load channels: ${escapeHtml(e.message)}</div>`;
        }
    }

    _ensureGrid(items) {
        const layoutKey = items.map(({ key }) => key).join('|');
        if (layoutKey === this._layoutKey && this._cardEls.size === items.length) return;
        this._layoutKey = layoutKey;
        this.refs.grid.innerHTML = items.map(({ key }) => this._cardShellHtml(key)).join('');
        this._cardEls = new Map(items.map(({ key }) => [key, this.refs.grid.querySelector(`[data-card="${key}"]`)]));
    }

    _cardShellHtml(key) {
        return `
            <div class="card" data-card="${escapeHtml(key)}">
                <div class="card-head">
                    <div class="signal" data-field="signal"></div>
                    <div class="head-main">
                        <h3 class="name" data-field="name"></h3>
                        <div class="sub">
                            <span class="proto-tag" data-field="protocol"></span>
                        </div>
                    </div>
                    <span class="status-badge" data-field="status"></span>
                </div>

                <div class="card-body">
                    <div class="metrics">
                        <div class="metric">
                            <div class="metric-label">Conversation latency</div>
                            <div class="metric-value"><span data-field="convLatency"></span><span class="unit">ms</span></div>
                        </div>
                        <div class="metric">
                            <div class="metric-label">Endpoint ping</div>
                            <div class="metric-value"><span data-field="pingLatency"></span><span class="unit">ms</span></div>
                        </div>
                    </div>

                    <div class="avail-box">
                        <div>
                            <div class="avail-label">Availability · 7d</div>
                            <div class="avail-extra" data-field="extraModels" hidden></div>
                        </div>
                        <span class="avail-value" data-field="availabilityValue"><span data-field="availability"></span><span class="pct" data-field="availabilityPct"></span></span>
                    </div>

                    <div class="spark-card">
                        <div class="spark-head">
                            <span data-field="sparkCount"></span>
                            <span>Live refresh</span>
                        </div>
                        <div class="spark" data-field="spark"></div>
                        <div class="spark-foot"><span>Past</span><span>Now</span></div>
                    </div>
                </div>
            </div>
        `;
    }

    _patchCard(key, c, stats, runtime) {
        const card = this._cardEls.get(key);
        if (!card) return;
        const state = this._cardState(c, stats, runtime);
        const field = (name) => card.querySelector(`[data-field="${name}"]`);

        this._setText(field('signal'), state.signalText);
        this._setClass(field('signal'), 'signal', state.signalClass);
        this._setText(field('name'), state.name);
        this._setText(field('protocol'), state.protocol);
        this._setText(field('model'), state.model);
        this._setText(field('status'), state.statusLabel);
        this._setClass(field('status'), 'status-badge', state.statusClass);
        this._setText(field('convLatency'), state.convLatency);
        this._setText(field('pingLatency'), state.pingLatency);
        this._setText(field('availability'), state.availability);
        this._setText(field('availabilityPct'), state.availabilityPct);
        this._setStyle(field('availabilityValue'), 'color', state.availabilityColor);
        this._setText(field('extraModels'), state.extraModels);
        this._setHidden(field('extraModels'), !state.extraModels);
        this._setText(field('sparkCount'), state.sparkCountLabel);
        this._setHtml(field('spark'), state.sparkHtml);
    }

    _cardState(c, stats, runtime = null) {
        const enabled = c.enabled !== false;
        const status = enabled ? (runtime?.status || c.status || 'HEALTHY') : 'DISABLED';
        const ok = enabled && status === 'HEALTHY' && !c.lastTestError;
        const models = (c.models || []).filter(m => m.enabled).map(m => m.publicModelName).filter(Boolean);
        const primaryModel = models.join(', ') || '—';
        const extraModels = '';
        const convLatency = stats?.avgLatencyMs ?? c.lastTestLatencyMs ?? 0;
        const pingLatency = c.lastTestLatencyMs ?? 0;
        const hasAvailability = typeof stats?.successRate7d === 'number';
        const avail = hasAvailability ? (stats.successRate7d * 100) : null;
        const availColor = avail == null ? 'var(--muted)' : avail >= 95 ? 'var(--green)' : avail >= 50 ? 'var(--amber)' : 'var(--red)';
        const tests = stats?.recentTests || [];
        const signalClass = ok ? 'ok' : 'err';
        const signalText = ok ? 'UP' : 'ERR';
        return {
            signalClass,
            signalText,
            name: c.name || '—',
            protocol: this._protoLabel(c.protocol),
            model: primaryModel,
            statusClass: this._statusClass(status, ok),
            statusLabel: this._statusLabel(status),
            convLatency: String(convLatency),
            pingLatency: String(pingLatency),
            availability: avail == null ? '—' : avail.toFixed(2),
            availabilityPct: avail == null ? '' : '%',
            availabilityColor: availColor,
            extraModels,
            sparkCountLabel: `Last ${tests.length || 60} checks`,
            sparkHtml: this._sparkHtml(tests),
        };
    }

    _runtimeStatusByChannel(pools) {
        const map = new Map();
        (pools?.chains || []).forEach(chain => {
            (chain.levels || []).forEach(level => {
                (level.keys || []).forEach(key => {
                    if (!key.keyId) return;
                    const current = map.get(key.keyId);
                    if (!current || this._statusRank(key.status) > this._statusRank(current.status)) {
                        map.set(key.keyId, key);
                    }
                });
            });
        });
        return map;
    }

    _statusRank(status) {
        const ranks = { HEALTHY: 0, DEGRADED: 1, COOLDOWN: 2, DISABLED: 3 };
        return ranks[String(status || '').toUpperCase()] ?? 1;
    }

    _sparkHtml(tests) {
        const slots = 60;
        const recent = (tests || []).slice(0, slots).reverse();
        const pad = slots - recent.length;
        const cells = [];
        for (let i = 0; i < pad; i++) cells.push('<span class="bar idle"></span>');
        recent.forEach(t => cells.push(`<span class="bar ${t.ok ? 'ok' : 'fail'}" title="${escapeHtml(new Date(t.timestamp).toLocaleString())}: ${t.ok ? 'OK' : 'FAIL'}${t.latencyMs ? ` (${t.latencyMs}ms)` : ''}"></span>`));
        return cells.join('');
    }

    _protoLabel(protocol) {
        const p = String(protocol || '').toUpperCase();
        if (p.includes('ANTHROPIC')) return 'Anthropic';
        if (p.includes('OPENAI_RESPONSES')) return 'OpenAI Responses';
        if (p.includes('OPENAI')) return 'OpenAI';
        return protocol || '—';
    }

    _statusLabel(status) {
        const labels = {
            HEALTHY: 'Healthy',
            COOLDOWN: 'Cooldown',
            DEGRADED: 'Degraded',
            DISABLED: 'Disabled',
        };
        return labels[String(status || '').toUpperCase()] || 'Error';
    }

    _statusClass(status, ok) {
        if (ok) return 'ok';
        const normalized = String(status || '').toUpperCase();
        if (normalized === 'COOLDOWN' || normalized === 'DEGRADED') return 'warn';
        return 'err';
    }

    _renderHeroCount(count) {
        if (this._heroCount === count) return;
        this._heroCount = count;
        this.refs.hero.render({
            label: 'Reliability',
            title: 'Availability',
            metaHtml: `<span style="display:inline-flex;padding:7px 10px;border:1px solid rgba(235,231,223,0.18);background:rgba(11,11,11,0.18);color:var(--on-accent);font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;">${count} channels</span>`
        });
    }

    _channelKey(channel, index) {
        return channel.channelId || channel.name || `channel-${index}`;
    }

    _setText(el, value) {
        if (!el) return;
        const next = String(value ?? '');
        if (el.textContent !== next) el.textContent = next;
    }

    _setHtml(el, value) {
        if (!el) return;
        if (el.innerHTML !== value) el.innerHTML = value;
    }

    _setHidden(el, hidden) {
        if (!el) return;
        if (el.hidden !== hidden) el.hidden = hidden;
    }

    _setClass(el, baseClass, toneClass) {
        if (!el) return;
        const next = `${baseClass} ${toneClass}`.trim();
        if (el.className !== next) el.className = next;
    }

    _setStyle(el, prop, value) {
        if (!el) return;
        if (el.style[prop] !== value) el.style[prop] = value;
    }
}

customElements.define('ai-panel-availability', PanelAvailability);
