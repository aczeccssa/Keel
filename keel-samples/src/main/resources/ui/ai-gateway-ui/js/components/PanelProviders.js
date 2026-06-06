import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson, putJson, deleteJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml, copyText } from '../utils.js';

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
    constructor() {
        super();
        this._editingId = null;
        this._groups = [];
        this._channels = [];
        this._hasRendered = false;
    }

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
                .field input:focus, .field select:focus, .field textarea:focus { outline: none; box-shadow: var(--shadow-sm); background: var(--color-surface-container-lowest, #fff); }
                .field .hint { font-size: 10px; color: var(--muted); margin-top: 6px; }
                .modal-actions { display: flex; gap: 12px; margin-top: 8px; }
                .test-banner { margin: 0 0 18px; padding: 12px 16px; border-radius: var(--radius-sm); font-size: 12px; font-weight: 600; display: none; }
                .test-banner.ok { display: block; background: var(--green-soft); color: var(--green); }
                .test-banner.err { display: block; background: var(--red-soft); color: var(--red); }
                .auth-preview { padding: 10px 12px; background: var(--color-surface-container-lowest, #fff); border: 1px dashed var(--ink); font-family: var(--font-mono); font-size: 11px; }
                .model-toolbar { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:10px; }
                .model-rows { display:grid; gap:10px; }
                .model-row { display:grid; grid-template-columns: 1.2fr 1.2fr auto auto; gap:8px; align-items:end; }
                .model-row .field { margin:0; }
                .model-row .field label { font-size:9px; margin-bottom:6px; }
                .model-row .btn-ghost, .model-row .btn-mini { align-self:stretch; }
                .model-row .btn-mini {
                    padding: 8px 10px; border: 1px solid var(--line-strong); background: transparent; color: var(--ink); font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; cursor: pointer;
                }
                .model-row .btn-mini:hover { background: var(--panel-strong); }
                .membership-toolbar { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:10px; }
                .membership-rows { display:grid; gap:10px; }
                .membership-row { display:grid; grid-template-columns: 1.2fr 0.7fr 0.7fr auto; gap:8px; align-items:end; }
                .membership-row .field { margin:0; }
                .membership-row .field label { font-size:9px; margin-bottom:6px; }
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
                        <div class="field"><label>Protocol</label>
                            <select data-ref="fProtocol">${PROTOCOLS.map(p => `<option value="${p.value}">${p.label}</option>`).join('')}</select>
                        </div>
                        <div class="field"><label>Status</label><div class="auth-preview" data-ref="membershipSummary">1 membership</div></div>
                        <div class="field full"><label>Base URL</label><input data-ref="fBaseUrl" placeholder="http://127.0.0.1:15721"></div>
                        <div class="field full"><label>API Key</label><input data-ref="fApiKey" type="password" placeholder="leave blank to keep existing"><div class="hint">Stored encrypted. For env-based keys, set "API Key Env" instead.</div></div>
                        <div class="field"><label>API Key Env</label><input data-ref="fApiKeyEnv" placeholder="ANTHROPIC_AUTH_TOKEN"></div>
                        <div class="field"><label>Auth Preview</label><div class="auth-preview" data-ref="authPreview">x-api-key: $KEY</div></div>
                        <div class="field"><label>Max Concurrency</label><input data-ref="fMaxConc" type="number" value="10"></div>
                        <div class="field full">
                            <div class="membership-toolbar">
                                <label>Memberships</label>
                                <button class="btn-ghost" data-ref="addMembershipBtn">+ Add Membership</button>
                            </div>
                            <div class="membership-rows" data-ref="membershipRows"></div>
                            <div class="hint">A channel can belong to multiple groups with different priority/weight values.</div>
                        </div>
                        <div class="field full">
                            <div class="model-toolbar">
                                <label>Models</label>
                                <div style="display:flex;gap:8px;">
                                    <button class="btn-ghost" data-ref="fetchModelsBtn">Fetch Models</button>
                                    <button class="btn-ghost" data-ref="addModelBtn">+ Add Model</button>
                                </div>
                            </div>
                            <div class="model-rows" data-ref="modelRows"></div>
                            <div class="hint">These are the model names clients can request through this channel. Fetch will add upstream ids as both public and upstream names.</div>
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
        this.refs.addBtn.addEventListener('click', () => this._openModal(null));
        this.refs.cancelBtn.addEventListener('click', () => this._closeModal());
        this.refs.overlay.addEventListener('click', (e) => { if (e.target === this.refs.overlay) this._closeModal(); });
        this.refs.saveBtn.addEventListener('click', () => this._save());
        this.refs.testInModalBtn.addEventListener('click', () => this._testFromForm());
        this.refs.addModelBtn.addEventListener('click', () => this._addModelRow());
        this.refs.addMembershipBtn.addEventListener('click', () => this._addMembershipRow());
        this.refs.fetchModelsBtn.addEventListener('click', () => this._fetchModels());
        this.refs.fProtocol.addEventListener('change', () => this._applyProtocolHints());
        this.refs.grid.addEventListener('click', (event) => this._handleGridAction(event));
    }

    async refresh() {
        try {
            const [data, groups] = await Promise.all([
                requestJson(`${API.airelay}/admin/channels`),
                requestJson(`${API.airelay}/admin/groups`).catch(() => ({ groups: [] })),
            ]);
            this._groups = groups.groups || [];
            this._channels = data.channels || [];
            this._render(this._channels, { silent: this._hasRendered });
            this._hasRendered = true;
        } catch (e) {
            this.refs.grid.innerHTML = `<div class="empty">Failed to load channels: ${escapeHtml(e.message)}</div>`;
        }
    }

    _render(channels, { silent = false } = {}) {
        this.refs.hero.render({
            label: 'Channel Management',
            title: 'Channels',
            metaHtml: `<span style="font-size:11px;font-weight:700;color:var(--muted);">${channels.length} channel${channels.length !== 1 ? 's' : ''}</span>`
        });

        if (channels.length === 0) {
            this.refs.grid.innerHTML = `<div class="section-card"><div class="empty">No channels configured yet.<br>Click <strong>Add Channel</strong> to point the gateway at an upstream (e.g. a local Anthropic endpoint).</div></div>`;
            return;
        }

        const grid = this.refs.grid.querySelector('.channel-grid');
        if (!silent || !grid) {
            this.refs.grid.innerHTML = `<div class="channel-grid">${channels.map(c => this._cardHtml(c)).join('')}</div>`;
            if (!this._hasRendered) this._animateCards();
            return;
        }

        this._patchCards(grid, channels);
    }

    _cardHtml(c) {
        const s = STATUS_STYLE[c.enabled ? c.status : 'DISABLED'] || STATUS_STYLE.HEALTHY;
        const models = (c.models || []).filter(m => m.enabled)
            .map(m => `<span class="model-tag">${escapeHtml(m.publicModelName)}</span>`).join('') || '<span class="cc-meta">No models</span>';
        const latency = c.lastTestLatencyMs != null
            ? `Last test: ${c.lastTestError ? `<span style="color:var(--red)">failed</span>` : `${c.lastTestLatencyMs}ms OK`}`
            : 'Not tested yet';
        const memberships = (c.memberships && c.memberships.length ? c.memberships : [{ groupId: c.groupId || 'default', priority: c.priority ?? 0, weight: c.weight ?? 100, enabled: c.enabled }]);
        const membershipText = memberships.map(m => `${m.groupId}:P${m.priority}/W${m.weight}`).join(' · ');
        return `
            <div class="channel-card" data-card="${c.channelId}">
                <div class="cc-head">
                    <div class="cc-name-row">
                        <h4 class="cc-name">${escapeHtml(c.name)}</h4>
                        <span class="chip" style="background:${s.bg};color:${s.color};">${s.label}</span>
                    </div>
                    <div class="cc-meta">${escapeHtml(c.baseUrl)}</div>
                    <span class="cc-proto">${escapeHtml(c.protocol)} · ${escapeHtml(membershipText)}</span>
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

    _handleGridAction(event) {
        const testBtn = event.target.closest('[data-test]');
        if (testBtn) {
            this._test(testBtn.dataset.test, testBtn);
            return;
        }
        const editBtn = event.target.closest('[data-edit]');
        if (editBtn) {
            const channel = this._channels.find(c => c.channelId === editBtn.dataset.edit);
            if (channel) this._openModal(channel);
            return;
        }
        const toggleBtn = event.target.closest('[data-toggle]');
        if (toggleBtn) {
            this._toggle(toggleBtn.dataset.toggle, toggleBtn.dataset.enabled !== 'true');
            return;
        }
        const deleteBtn = event.target.closest('[data-delete]');
        if (deleteBtn) this._delete(deleteBtn.dataset.delete);
    }

    _patchCards(grid, channels) {
        const existing = new Map(Array.from(grid.querySelectorAll('.channel-card')).map(card => [card.dataset.card, card]));
        const cardsInOrder = channels.map(channel => {
            const snapshot = this._cardSnapshot(channel);
            let card = existing.get(channel.channelId);
            if (!card) {
                card = this._createCard(channel);
            } else if (card.dataset.snapshot !== snapshot) {
                const replacement = this._createCard(channel);
                card.replaceWith(replacement);
                card = replacement;
            }
            return card;
        });

        cardsInOrder.forEach((card, index) => {
            if (grid.children[index] !== card) {
                grid.insertBefore(card, grid.children[index] || null);
            }
        });
        existing.forEach((card, id) => {
            if (!channels.some(channel => channel.channelId === id) && card.isConnected) {
                card.remove();
            }
        });
    }

    _createCard(channel) {
        const wrapper = document.createElement('div');
        wrapper.innerHTML = this._cardHtml(channel).trim();
        const card = wrapper.firstElementChild;
        card.dataset.snapshot = this._cardSnapshot(channel);
        return card;
    }

    _cardSnapshot(channel) {
        return JSON.stringify({
            channelId: channel.channelId,
            name: channel.name,
            protocol: channel.protocol,
            baseUrl: channel.baseUrl,
            enabled: channel.enabled,
            status: channel.status,
            lastTestLatencyMs: channel.lastTestLatencyMs,
            lastTestError: channel.lastTestError,
            models: (channel.models || []).map(model => ({
                publicModelName: model.publicModelName,
                upstreamModelName: model.upstreamModelName,
                enabled: model.enabled,
            })),
            memberships: (channel.memberships || []).map(membership => ({
                groupId: membership.groupId,
                priority: membership.priority,
                weight: membership.weight,
                enabled: membership.enabled,
            })),
        });
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
        this.refs.fName.value = channel?.name || '';
        this.refs.fProtocol.value = channel?.protocol || 'ANTHROPIC_MESSAGES';
        this.refs.fBaseUrl.value = channel?.baseUrl || '';
        this.refs.fApiKey.value = '';
        this.refs.fApiKey.placeholder = channel ? 'leave blank to keep existing' : 'sk-...';
        this.refs.fApiKeyEnv.value = channel?.apiKeyEnv || '';
        this.refs.fMaxConc.value = channel?.maxConcurrency ?? 10;
        this.refs.membershipRows.innerHTML = '';
        const memberships = channel?.memberships?.length
            ? channel.memberships
            : [{ groupId: channel?.groupId || 'default', priority: channel?.priority ?? 0, weight: channel?.weight ?? 100, enabled: true }];
        memberships.forEach(m => this._addMembershipRow(m));
        this.refs.modelRows.innerHTML = '';
        const models = channel?.models?.length ? channel.models : [{ publicModelName: '', upstreamModelName: '', enabled: true, creditMultiplier: '' }];
        models.forEach(m => this._addModelRow(m));
        this._applyProtocolHints();
        this._renderMembershipSummary();
        this.refs.overlay.classList.add('open');
        const gsap = window.gsap;
        if (gsap) gsap.fromTo(this.refs.modal, { autoAlpha: 0, y: 20, scale: 0.98 }, { autoAlpha: 1, y: 0, scale: 1, duration: 0.3, ease: 'power2.out' });
    }

    _closeModal() {
        this.refs.overlay.classList.remove('open');
    }

    _applyProtocolHints() {
        const protocol = this.refs.fProtocol.value;
        if (protocol === 'ANTHROPIC_MESSAGES') {
            this.refs.fApiKeyEnv.placeholder = 'ANTHROPIC_API_KEY';
            this.refs.authPreview.textContent = 'x-api-key: $KEY';
        } else {
            this.refs.fApiKeyEnv.placeholder = 'OPENAI_API_KEY';
            this.refs.authPreview.textContent = 'Authorization: Bearer $KEY';
        }
    }

    _addModelRow(model = { publicModelName: '', upstreamModelName: '', enabled: true, creditMultiplier: '' }) {
        const row = document.createElement('div');
        row.className = 'model-row';
        row.innerHTML = `
            <div class="field">
                <label>Public Name</label>
                <input data-role="public" value="${escapeHtml(model.publicModelName || '')}" placeholder="claude-sonnet-4-20250514">
            </div>
            <div class="field">
                <label>Upstream Name</label>
                <input data-role="upstream" value="${escapeHtml(model.upstreamModelName || '')}" placeholder="claude-sonnet-4-20250514">
            </div>
            <div class="field">
                <label>Credit ×</label>
                <input data-role="credit" type="number" step="0.1" min="0" value="${escapeHtml(model.creditMultiplier ?? '')}" placeholder="1.0">
            </div>
            <div class="field" style="flex-direction:row;align-items:center;gap:6px;">
                <label style="margin:0;"><input type="checkbox" data-role="model-enabled" ${model.enabled !== false ? 'checked' : ''} /> On</label>
            </div>
            <div style="display:flex;gap:6px;">
                <button class="btn-mini" data-role="test-model">Test</button>
                <button class="btn-mini" data-role="remove-model">Remove</button>
            </div>
            <div class="field full" data-role="test-result" style="display:none;margin-top:4px;">
                <div class="test-banner" style="margin:0;"></div>
            </div>
        `;
        row.querySelector('[data-role="remove-model"]').addEventListener('click', () => {
            row.remove();
            if (!this.refs.modelRows.children.length) this._addModelRow();
        });
        row.querySelector('[data-role="test-model"]').addEventListener('click', () => this._testModelRow(row));
        this.refs.modelRows.appendChild(row);
    }

    _addMembershipRow(membership = { groupId: 'default', priority: 0, weight: 100, enabled: true }) {
        const groupOptions = (this._groups && this._groups.length ? this._groups : [{ groupId: 'default', name: 'Default' }])
            .map(g => `<option value="${escapeHtml(g.groupId)}"${g.groupId === membership.groupId ? ' selected' : ''}>${escapeHtml(g.name || g.groupId)}</option>`).join('');
        const row = document.createElement('div');
        row.className = 'membership-row';
        row.innerHTML = `
            <div class="field">
                <label>Group</label>
                <select data-role="group">${groupOptions}</select>
            </div>
            <div class="field">
                <label>Priority</label>
                <input data-role="priority" type="number" value="${Number(membership.priority ?? 0)}">
            </div>
            <div class="field">
                <label>Weight</label>
                <input data-role="weight" type="number" value="${Number(membership.weight ?? 100)}">
            </div>
            <button class="btn-mini" data-role="remove-membership">Remove</button>
        `;
        row.querySelector('[data-role="remove-membership"]').addEventListener('click', () => {
            row.remove();
            if (!this.refs.membershipRows.children.length) this._addMembershipRow();
            this._renderMembershipSummary();
        });
        row.querySelectorAll('select,input').forEach(el => el.addEventListener('change', () => this._renderMembershipSummary()));
        this.refs.membershipRows.appendChild(row);
        this._renderMembershipSummary();
    }

    _readMembershipRows() {
        return Array.from(this.refs.membershipRows.querySelectorAll('.membership-row')).map(row => ({
            groupId: row.querySelector('[data-role="group"]').value.trim() || 'default',
            priority: parseInt(row.querySelector('[data-role="priority"]').value, 10) || 0,
            weight: parseInt(row.querySelector('[data-role="weight"]').value, 10) || 100,
            enabled: true,
        }));
    }

    _renderMembershipSummary() {
        const memberships = this._readMembershipRows();
        this.refs.membershipSummary.textContent = `${memberships.length} membership${memberships.length === 1 ? '' : 's'}`;
    }

    _readModelRows() {
        return Array.from(this.refs.modelRows.querySelectorAll('.model-row'))
            .map(row => {
                const publicModelName = row.querySelector('[data-role="public"]').value.trim();
                const upstreamModelName = row.querySelector('[data-role="upstream"]').value.trim();
                const creditMultiplierRaw = row.querySelector('[data-role="credit"]').value.trim();
                const enabled = row.querySelector('[data-role="model-enabled"]')?.checked ?? true;
                return publicModelName ? { publicModelName, upstreamModelName, creditMultiplier: creditMultiplierRaw ? parseFloat(creditMultiplierRaw) : null, enabled } : null;
            })
            .filter(Boolean);
    }

    _collectForm() {
        const models = this._readModelRows();
        return {
            name: this.refs.fName.value.trim(),
            protocol: this.refs.fProtocol.value,
            baseUrl: this.refs.fBaseUrl.value.trim(),
            apiKey: this.refs.fApiKey.value,
            apiKeyEnv: this.refs.fApiKeyEnv.value.trim() || null,
            enabled: true,
            priority: this._readMembershipRows()[0]?.priority || 0,
            weight: this._readMembershipRows()[0]?.weight || 100,
            maxConcurrency: parseInt(this.refs.fMaxConc.value) || 10,
            timeoutMs: 60000,
            groupId: this._readMembershipRows()[0]?.groupId || 'default',
            memberships: this._readMembershipRows(),
            models,
        };
    }

    async _fetchModels() {
        this.refs.fetchModelsBtn.disabled = true;
        try {
            let result;
            if (this._editingId) {
                result = await postJson(`${API.airelay}/admin/channels/${this._editingId}/discover-models`, {});
            } else {
                result = await postJson(`${API.airelay}/admin/channels/discover-models`, {
                    protocol: this.refs.fProtocol.value,
                    baseUrl: this.refs.fBaseUrl.value.trim(),
                    apiKey: this.refs.fApiKey.value,
                    apiKeyEnv: this.refs.fApiKeyEnv.value.trim() || null,
                });
            }
            if (result.error) {
                this._banner(false, result.error);
                return;
            }
            // Append discovered models that are not already present (preserve existing rows)
            const existingNames = new Set(
                Array.from(this.refs.modelRows.querySelectorAll('.model-row'))
                    .map(r => r.querySelector('[data-role="public"]').value.trim().toLowerCase())
                    .filter(Boolean)
            );
            (result.models || []).forEach(model => {
                if (!existingNames.has(model.toLowerCase())) {
                    this._addModelRow({ publicModelName: model, upstreamModelName: model, enabled: true });
                }
            });
            if (!this.refs.modelRows.children.length) this._addModelRow();
            this._banner(true, `Fetched ${(result.models || []).length} model(s) in ${result.latencyMs}ms`);
        } catch (e) {
            this._banner(false, e.message);
        } finally {
            this.refs.fetchModelsBtn.disabled = false;
        }
    }

    async _testModelRow(row) {
        const publicModelName = row.querySelector('[data-role="public"]').value.trim();
        const upstreamModelName = row.querySelector('[data-role="upstream"]').value.trim();
        const resultDiv = row.querySelector('[data-role="test-result"]');
        const banner = resultDiv?.querySelector('.test-banner');
        if (!publicModelName) {
            if (banner) { banner.className = 'test-banner err'; banner.textContent = 'Public model name is required.'; }
            if (resultDiv) resultDiv.style.display = 'block';
            return;
        }
        if (!this._editingId) {
            if (banner) { banner.className = 'test-banner err'; banner.textContent = 'Save the channel first, then run model-level test.'; }
            if (resultDiv) resultDiv.style.display = 'block';
            return;
        }
        try {
            const res = await postJson(`${API.airelay}/admin/channels/${this._editingId}/test-model`, {
                publicModelName,
                upstreamModelName: upstreamModelName || null,
            });
            if (banner) {
                banner.className = `test-banner ${res.ok ? 'ok' : 'err'}`;
                banner.textContent = res.ok ? `OK in ${res.latencyMs}ms` : (res.error || 'Failed');
            }
            if (resultDiv) resultDiv.style.display = 'block';
        } catch (e) {
            if (banner) { banner.className = 'test-banner err'; banner.textContent = e.message; }
            if (resultDiv) resultDiv.style.display = 'block';
        }
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
