import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson, putJson, deleteJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

const PROTOCOLS = [
    { value: 'ANTHROPIC_MESSAGES', label: 'Anthropic Messages' },
    { value: 'OPENAI_CHAT', label: 'OpenAI Chat Completions' },
    { value: 'OPENAI_RESPONSES', label: 'OpenAI Responses' },
];

const STATUS_STYLE = {
    HEALTHY: { bg: 'var(--green-soft)', color: 'var(--green)', label: 'Healthy' },
    DEGRADED: { bg: 'var(--amber-soft)', color: 'var(--amber)', label: 'Degraded' },
    DISABLED: { bg: 'var(--red-soft)', color: 'var(--red)', label: 'Disabled' },
};

/**
 * Provider/Channel management. Lists configured upstream channels, lets the user add/edit/delete
 * one, toggle enabled, and fire a live Test against the upstream before relying on it. This is the
 * entry point for pointing the gateway at a provider (e.g. http://127.0.0.1:15721) at runtime.
 */
export class PanelProviders extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .panel-layout { display: flex; flex-direction: column; gap: 28px; }
                .toolbar { display: flex; justify-content: space-between; align-items: center; }
                .section-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 28px;
                    box-shadow: var(--shadow-sm);
                    border: 2px solid var(--ink);
                }
                .section-title {
                    font-family: var(--font-headline);
                    font-size: 20px; font-weight: 500; margin: 0 0 20px; color: var(--ink);
                }
                .btn-primary {
                    padding: 12px 24px; border: 0; border-radius: 0;
                    background: var(--navy); color: #f8fafc; font-size: 11px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; cursor: pointer; transition: background 200ms ease;
                }
                .btn-primary:hover { background: var(--navy-2); }
                .btn-ghost {
                    padding: 8px 16px; border: 1px solid var(--line-strong); border-radius: 0;
                    background: transparent; color: var(--ink); font-size: 10px; font-weight: 700;
                    letter-spacing: 0.08em; text-transform: uppercase; cursor: pointer; transition: all 150ms ease;
                }
                .btn-ghost:hover { background: var(--panel-strong); }
                .channel-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 20px; }
                .channel-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    border: 2px solid var(--ink);
                    box-shadow: var(--shadow-sm);
                    overflow: hidden;
                    display: flex; flex-direction: column;
                    transition: box-shadow 200ms ease, transform 200ms ease;
                }
                .channel-card:hover { box-shadow: var(--shadow-md); transform: translateY(-2px); }
                .cc-head { padding: 20px 22px 16px; border-bottom: 2px solid var(--ink); }
                .cc-name-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
                .cc-name { font-family: var(--font-headline); font-size: 19px; font-weight: 600; color: var(--ink); margin: 0; }
                .chip { display: inline-flex; align-items: center; gap: 5px; padding: 4px 10px; border-radius: 0; font-size: 9px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.07em; }
                .cc-meta { margin-top: 8px; font-size: 11px; color: var(--muted); font-family: var(--font-mono); word-break: break-all; }
                .cc-proto { margin-top: 10px; display: inline-block; font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; color: var(--teal); }
                .cc-body { padding: 16px 22px; flex: 1; }
                .cc-models { display: flex; flex-wrap: wrap; gap: 6px; }
                .model-tag { font-size: 10px; font-family: var(--font-mono); background: rgba(15,23,42,0.05); padding: 3px 8px; border-radius: 0; color: var(--ink); }
                .cc-latency { margin-top: 14px; font-size: 11px; color: var(--muted); font-weight: 700; }
                .cc-actions { padding: 14px 22px; background: var(--color-surface-container-low, #f3f1ed); display: flex; gap: 8px; flex-wrap: wrap; }
                .btn-mini { padding: 6px 12px; border-radius: 0; border: 1px solid var(--line-strong); background: transparent; color: var(--ink); font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; cursor: pointer; transition: all 150ms ease; }
                .btn-mini:hover { background: var(--panel-strong); }
                .btn-mini.test { border-color: var(--teal); color: var(--teal); }
                .btn-mini.test:hover { background: var(--teal-soft); }
                .btn-mini.danger { border-color: var(--red); color: var(--red); }
                .btn-mini.danger:hover { background: var(--red-soft); }
                .empty { text-align: center; color: var(--muted); padding: 48px; font-size: 13px; }
                /* Modal */
                .overlay { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.32); display: none; align-items: center; justify-content: center; z-index: 900; backdrop-filter: blur(2px); }
                .overlay.open { display: flex; }
                .modal { background: var(--panel-strong); border-radius: var(--radius-xl); width: min(620px, 92vw); max-height: 88vh; overflow-y: auto; padding: 36px; box-shadow: var(--shadow-lg); }
                .modal h3 { font-family: var(--font-headline); font-size: 26px; font-style: italic; font-weight: 700; margin: 0 0 24px; letter-spacing: -0.02em; }
                .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
                .field { display: flex; flex-direction: column; }
                .field.full { grid-column: 1 / -1; }
                .field label { font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px; }
                .field input, .field select, .field textarea {
                    width: 100%; padding: 11px 13px; border: 0; border-radius: var(--radius-sm); font-size: 13px;
                    background: var(--color-surface-container-high, #e4e2dc); color: var(--ink); transition: all 150ms ease;
                }
                .field textarea { font-family: var(--font-mono); min-height: 90px; resize: vertical; }
                .field input:focus, .field select:focus, .field textarea:focus { outline: none; box-shadow: var(--shadow-sm); background: var(--color-surface-container-lowest, #fff); }
                .field .hint { font-size: 10px; color: var(--muted); margin-top: 6px; }
                .modal-actions { display: flex; gap: 12px; margin-top: 8px; }
                .test-banner { margin: 0 0 18px; padding: 12px 16px; border-radius: var(--radius-sm); font-size: 12px; font-weight: 600; display: none; }
                .test-banner.ok { display: block; background: var(--green-soft); color: var(--green); }
                .test-banner.err { display: block; background: var(--red-soft); color: var(--red); }
            </style>
            <div class="panel-layout">
                <div class="toolbar">
                    <keel-hero data-ref="hero"></keel-hero>
                    <button class="btn-primary" data-ref="addBtn">Add Channel</button>
                </div>
                <div data-ref="grid"></div>
            </div>

            <div class="overlay" data-ref="overlay">
                <div class="modal" data-ref="modal">
                    <h3 data-ref="modalTitle">Add Channel</h3>
                    <div class="test-banner" data-ref="testBanner"></div>
                    <div class="form-grid">
                        <div class="field"><label>Name</label><input data-ref="fName" placeholder="My Anthropic backup"></div>
                        <div class="field"><label>Group</label><select data-ref="fGroup"></select></div>
                        <div class="field"><label>Protocol</label>
                            <select data-ref="fProtocol">${PROTOCOLS.map(p => `<option value="${p.value}">${p.label}</option>`).join('')}</select>
                        </div>
                        <div class="field full"><label>Base URL</label><input data-ref="fBaseUrl" placeholder="http://127.0.0.1:15721"></div>
                        <div class="field full"><label>API Key</label><input data-ref="fApiKey" type="password" placeholder="leave blank to keep existing"><div class="hint">Stored encrypted. For env-based keys, set "API Key Env" instead.</div></div>
                        <div class="field"><label>API Key Env</label><input data-ref="fApiKeyEnv" placeholder="ANTHROPIC_AUTH_TOKEN"></div>
                        <div class="field"><label>Priority</label><input data-ref="fPriority" type="number" value="0"></div>
                        <div class="field"><label>Weight</label><input data-ref="fWeight" type="number" value="100"></div>
                        <div class="field"><label>Max Concurrency</label><input data-ref="fMaxConc" type="number" value="10"></div>
                        <div class="field full"><label>Models (one per line: publicName[=upstreamName])</label>
                            <textarea data-ref="fModels" placeholder="claude-sonnet-4-20250514&#10;claude-opus-4-20250514=claude-opus-4"></textarea>
                            <div class="hint">These are the model names clients can request through this channel.</div>
                        </div>
                    </div>
                    <div class="modal-actions">
                        <button class="btn-primary" data-ref="saveBtn">Save</button>
                        <button class="btn-ghost" data-ref="testInModalBtn">Test connection</button>
                        <button class="btn-ghost" data-ref="cancelBtn">Cancel</button>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label: 'Channel Management', title: 'Channels', metaHtml: '' });
        this._editingId = null;
        this._groups = [];
        this.refs.addBtn.addEventListener('click', () => this._openModal(null));
        this.refs.cancelBtn.addEventListener('click', () => this._closeModal());
        this.refs.overlay.addEventListener('click', (e) => { if (e.target === this.refs.overlay) this._closeModal(); });
        this.refs.saveBtn.addEventListener('click', () => this._save());
        this.refs.testInModalBtn.addEventListener('click', () => this._testFromForm());
    }

    async refresh() {
        try {
            const [data, groups] = await Promise.all([
                requestJson(`${API.airelay}/admin/channels`),
                requestJson(`${API.airelay}/admin/groups`).catch(() => ({ groups: [] })),
            ]);
            this._groups = groups.groups || [];
            this._render(data.channels || []);
        } catch (e) {
            this.refs.grid.innerHTML = `<div class="empty">Failed to load channels: ${escapeHtml(e.message)}</div>`;
        }
    }

    _render(channels) {
        this.refs.hero.render({
            label: 'Channel Management',
            title: 'Channels',
            metaHtml: `<span style="font-size:11px;font-weight:700;color:var(--muted);">${channels.length} channel${channels.length !== 1 ? 's' : ''}</span>`
        });

        if (channels.length === 0) {
            this.refs.grid.innerHTML = `<div class="section-card"><div class="empty">No channels configured yet.<br>Click <strong>Add Channel</strong> to point the gateway at an upstream (e.g. a local Anthropic endpoint).</div></div>`;
            return;
        }

        this.refs.grid.innerHTML = `<div class="channel-grid">${channels.map(c => this._cardHtml(c)).join('')}</div>`;
        this._bindCardActions(channels);
        this._animateCards();
    }

    _cardHtml(c) {
        const s = STATUS_STYLE[c.enabled ? c.status : 'DISABLED'] || STATUS_STYLE.HEALTHY;
        const models = (c.models || []).filter(m => m.enabled)
            .map(m => `<span class="model-tag">${escapeHtml(m.publicModelName)}</span>`).join('') || '<span class="cc-meta">No models</span>';
        const latency = c.lastTestLatencyMs != null
            ? `Last test: ${c.lastTestError ? `<span style="color:var(--red)">failed</span>` : `${c.lastTestLatencyMs}ms OK`}`
            : 'Not tested yet';
        const group = escapeHtml(c.groupId || 'default');
        const priority = c.priority ?? 0;
        const weight = c.weight ?? 100;
        return `
            <div class="channel-card" data-card="${c.channelId}">
                <div class="cc-head">
                    <div class="cc-name-row">
                        <h4 class="cc-name">${escapeHtml(c.name)}</h4>
                        <span class="chip" style="background:${s.bg};color:${s.color};">${s.label}</span>
                    </div>
                    <div class="cc-meta">${escapeHtml(c.baseUrl)}</div>
                    <span class="cc-proto">${escapeHtml(c.protocol)} · GROUP ${group} · P${priority} · W${weight}</span>
                </div>
                <div class="cc-body">
                    <div class="cc-models">${models}</div>
                    <div class="cc-latency">${latency}</div>
                </div>
                <div class="cc-actions">
                    <button class="btn-mini test" data-test="${c.channelId}">Test</button>
                    <button class="btn-mini" data-edit="${c.channelId}">Edit</button>
                    <button class="btn-mini" data-toggle="${c.channelId}" data-enabled="${c.enabled}">${c.enabled ? 'Disable' : 'Enable'}</button>
                    <button class="btn-mini danger" data-delete="${c.channelId}">Delete</button>
                </div>
            </div>`;
    }

    _bindCardActions(channels) {
        const byId = Object.fromEntries(channels.map(c => [c.channelId, c]));
        this.shadowRoot.querySelectorAll('[data-test]').forEach(btn => btn.addEventListener('click', () => this._test(btn.dataset.test, btn)));
        this.shadowRoot.querySelectorAll('[data-edit]').forEach(btn => btn.addEventListener('click', () => this._openModal(byId[btn.dataset.edit])));
        this.shadowRoot.querySelectorAll('[data-toggle]').forEach(btn => btn.addEventListener('click', () => this._toggle(btn.dataset.toggle, btn.dataset.enabled !== 'true')));
        this.shadowRoot.querySelectorAll('[data-delete]').forEach(btn => btn.addEventListener('click', () => this._delete(btn.dataset.delete)));
    }

    _animateCards() {
        const gsap = window.gsap;
        const cards = this.shadowRoot.querySelectorAll('.channel-card');
        if (!gsap || cards.length === 0) return;
        gsap.fromTo(cards, { autoAlpha: 0, y: 18 }, { autoAlpha: 1, y: 0, duration: 0.45, ease: 'power2.out', stagger: 0.06 });
    }

    _openModal(channel) {
        this._editingId = channel ? channel.channelId : null;
        this.refs.modalTitle.textContent = channel ? 'Edit Channel' : 'Add Channel';
        this.refs.testBanner.className = 'test-banner';
        const groupOptions = (this._groups && this._groups.length ? this._groups : [{ groupId: 'default', name: 'Default' }])
            .map(g => `<option value="${escapeHtml(g.groupId)}">${escapeHtml(g.name || g.groupId)}</option>`).join('');
        this.refs.fGroup.innerHTML = groupOptions;
        this.refs.fGroup.value = channel?.groupId || 'default';
        this.refs.fName.value = channel?.name || '';
        this.refs.fProtocol.value = channel?.protocol || 'ANTHROPIC_MESSAGES';
        this.refs.fBaseUrl.value = channel?.baseUrl || '';
        this.refs.fApiKey.value = '';
        this.refs.fApiKey.placeholder = channel ? 'leave blank to keep existing' : 'sk-...';
        this.refs.fApiKeyEnv.value = channel?.apiKeyEnv || '';
        this.refs.fPriority.value = channel?.priority ?? 0;
        this.refs.fWeight.value = channel?.weight ?? 100;
        this.refs.fMaxConc.value = channel?.maxConcurrency ?? 10;
        this.refs.fModels.value = (channel?.models || [])
            .map(m => m.upstreamModelName ? `${m.publicModelName}=${m.upstreamModelName}` : m.publicModelName)
            .join('\n');
        this.refs.overlay.classList.add('open');
        const gsap = window.gsap;
        if (gsap) gsap.fromTo(this.refs.modal, { autoAlpha: 0, y: 20, scale: 0.98 }, { autoAlpha: 1, y: 0, scale: 1, duration: 0.3, ease: 'power2.out' });
    }

    _closeModal() {
        this.refs.overlay.classList.remove('open');
    }

    _collectForm() {
        const models = this.refs.fModels.value.split('\n').map(l => l.trim()).filter(Boolean).map(line => {
            const [pub, up] = line.split('=').map(s => s.trim());
            return { publicModelName: pub, upstreamModelName: up || '', enabled: true };
        });
        return {
            name: this.refs.fName.value.trim(),
            protocol: this.refs.fProtocol.value,
            baseUrl: this.refs.fBaseUrl.value.trim(),
            apiKey: this.refs.fApiKey.value,
            apiKeyEnv: this.refs.fApiKeyEnv.value.trim() || null,
            enabled: true,
            priority: parseInt(this.refs.fPriority.value) || 0,
            weight: parseInt(this.refs.fWeight.value) || 100,
            maxConcurrency: parseInt(this.refs.fMaxConc.value) || 10,
            timeoutMs: 60000,
            groupId: this.refs.fGroup.value || 'default',
            models,
        };
    }

    async _save() {
        const body = this._collectForm();
        if (!body.name || !body.baseUrl) { this._banner(false, 'Name and Base URL are required.'); return; }
        try {
            if (this._editingId) {
                await putJson(`${API.airelay}/admin/channels/${this._editingId}`, body);
            } else {
                await postJson(`${API.airelay}/admin/channels`, body);
            }
            this._closeModal();
            this.refresh();
        } catch (e) { this._banner(false, e.message); }
    }

    async _testFromForm() {
        // Save first (so the channel exists), then test. For a brand-new unsaved channel we
        // require a save to obtain an id; guide the user.
        if (!this._editingId) { this._banner(false, 'Save the provider first, then Test.'); return; }
        await this._test(this._editingId, this.refs.testInModalBtn, true);
    }

    async _test(channelId, btn, inModal = false) {
        const original = btn.textContent;
        btn.textContent = 'Testing…';
        btn.disabled = true;
        try {
            const res = await postJson(`${API.airelay}/admin/channels/${channelId}/test`, {});
            if (inModal) this._banner(res.ok, res.ok ? `Connected in ${res.latencyMs}ms` : (res.error || 'Test failed'));
            else this.refresh();
        } catch (e) {
            if (inModal) this._banner(false, e.message);
        } finally {
            btn.textContent = original;
            btn.disabled = false;
        }
    }

    async _toggle(channelId, enabled) {
        try { await postJson(`${API.airelay}/admin/channels/${channelId}/enabled/${enabled}`, {}); this.refresh(); }
        catch (e) { alert(e.message); }
    }

    async _delete(channelId) {
        if (!confirm('Delete this provider? Clients using its models will lose access.')) return;
        try { await deleteJson(`${API.airelay}/admin/channels/${channelId}`); this.refresh(); }
        catch (e) { alert(e.message); }
    }

    _banner(ok, msg) {
        this.refs.testBanner.className = `test-banner ${ok ? 'ok' : 'err'}`;
        this.refs.testBanner.textContent = msg;
    }
}

customElements.define('ai-panel-providers', PanelProviders);
