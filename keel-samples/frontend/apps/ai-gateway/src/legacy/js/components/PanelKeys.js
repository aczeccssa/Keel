import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson, deleteJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml, copyText } from '../utils.js';

async function copyToClipboard(text) {
    const ok = await copyText(text);
    if (!ok) { try { alert('Copy failed — text is selectable above.'); } catch {} }
}

const CLIENTS = [
    {
        id: 'code',
        name: 'Claude Code',
        icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>`,
        note: 'Shell env — run before launching Claude Code',
        snippet: (base, key) =>
`export ANTHROPIC_BASE_URL="${base}"
export ANTHROPIC_API_KEY="${key}"`,
    },
    {
        id: 'desktop',
        name: 'Claude Desktop',
        icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/></svg>`,
        note: 'Add to claude_desktop_config.json → env block',
        snippet: (base, key) => `{
  "env": {
    "ANTHROPIC_BASE_URL": "${base}",
    "ANTHROPIC_API_KEY": "${key}"
  }
}`,
    },
    {
        id: 'codex',
        name: 'Codex',
        icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="16"/><line x1="8" x2="16" y1="12" y2="12"/></svg>`,
        note: 'Shell env — run before launching Codex CLI',
        snippet: (base, key) =>
`export OPENAI_API_KEY="${key}"
export OPENAI_BASE_URL="${base}"
export OPENAI_API_BASE="${base}"`,
    },
    {
        id: 'opencode',
        name: 'OpenCode',
        icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>`,
        note: 'Shell env — compatible agents using Anthropic API',
        snippet: (base, key) =>
`ANTHROPIC_BASE_URL="${base}"
ANTHROPIC_API_KEY="${key}"`,
    },
    {
        id: 'openclaw',
        name: 'OpenClaw',
        icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>`,
        note: 'Shell env — OpenAI-compatible client',
        snippet: (base, key) =>
`OPENAI_API_KEY="${key}"
OPENAI_BASE_URL="${base}"`,
    },
    {
        id: 'hermes',
        name: 'Hermes',
        icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 15a4 4 0 0 0 4 4h9a5 5 0 1 0-.1-9.999 5.002 5.002 0 1 0-9.78 2.096A4.001 4.001 0 0 0 3 15z"/></svg>`,
        note: 'Shell env — Hermes-compatible SDK',
        snippet: (base, key) =>
`export ANTHROPIC_BASE_URL="${base}"
export ANTHROPIC_API_KEY="${key}"`,
    },
];

export class PanelKeys extends KeelElement {
    hostStyles() { return 'height:100%'; }

    template() {
        return `
            <style>
                .panel-layout { display: grid; gap: 22px; }
                .section-card {
                    position: relative;
                    background: var(--paper);
                    border: 2px solid var(--ink);
                    box-shadow: var(--shadow-sm);
                    overflow: hidden;
                }
                .section-title-row {
                    display: flex; align-items: center; justify-content: space-between; gap: 16px;
                    padding: 16px 18px;
                    background: var(--ink);
                    color: var(--paper);
                }
                .section-kicker {
                    display: block; margin-bottom: 4px;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.18em; text-transform: uppercase; color: var(--red);
                }
                .section-title {
                    margin: 0; font-family: var(--font-headline); font-size: 20px; line-height: 1; letter-spacing: -0.04em; text-transform: uppercase;
                }
                .section-body { padding: 20px 18px; }
                /* Form */
                .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-bottom: 20px; }
                .field label {
                    display: block; font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px;
                }
                .field input {
                    width: 100%; padding: 11px 12px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em;
                    transition: all 120ms var(--ease-smooth);
                }
                .field input:focus { outline: none; box-shadow: var(--shadow-sm); }
                .btn-primary {
                    padding: 12px 22px; border: 2px solid var(--ink); background: var(--ink); color: var(--paper); cursor: pointer;
                    font-family: var(--font-mono); font-size: 11px; font-weight: 800; letter-spacing: 0.14em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .btn-primary:hover { background: var(--red); border-color: var(--red); }
                .btn-ghost {
                    padding: 11px 18px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); cursor: pointer;
                    font-family: var(--font-mono); font-size: 11px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .btn-ghost:hover { background: var(--ink); color: var(--paper); }
                .btn-danger {
                    padding: 7px 12px; border: 2px solid var(--red); background: transparent; color: var(--red); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .btn-danger:hover { background: var(--red); color: var(--paper); }
                .form-actions { display: flex; gap: 10px; }
                /* Key reveal (inline) */
                .key-reveal {
                    display: none; margin-top: 16px; padding: 14px 16px;
                    background: var(--red-soft); border: 2px solid var(--red);
                }
                .key-reveal.is-visible { display: block; }
                .key-reveal .kicker { font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.18em; text-transform: uppercase; color: var(--red); margin-bottom: 8px; }
                .key-reveal .key-row {
                    display: flex; align-items: center; gap: 10px;
                    padding: 10px 12px; background: var(--paper); border: 2px solid var(--ink);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 800; letter-spacing: 0.04em; color: var(--ink);
                    word-break: break-all; user-select: all;
                }
                .key-reveal .copy-btn {
                    flex-shrink: 0; padding: 7px 12px; border: 2px solid var(--ink); background: var(--ink); color: var(--paper); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                }
                .key-reveal .copy-btn:hover { background: var(--red); border-color: var(--red); }
                /* Modal overlay */
                .connect-overlay {
                    display: none; position: fixed; inset: 0; z-index: 900;
                    background: rgba(11,11,11,0.55);
                    backdrop-filter: blur(2px);
                    align-items: center; justify-content: center;
                }
                .connect-overlay.is-open { display: flex; }
                .connect-modal {
                    position: relative;
                    width: min(820px, 92vw);
                    max-height: 88vh;
                    overflow: hidden;
                    background: var(--paper);
                    border: 2px solid var(--ink);
                    box-shadow: var(--shadow-lg);
                    display: grid;
                    grid-template-rows: auto auto 1fr;
                }
                .modal-bar {
                    display: flex; align-items: center; justify-content: space-between; gap: 12px;
                    padding: 14px 18px;
                    background: var(--ink); color: var(--paper);
                }
                .modal-bar-title {
                    font-family: var(--font-headline); font-size: 18px; line-height: 1; letter-spacing: -0.04em; text-transform: uppercase;
                }
                .modal-close {
                    padding: 6px 12px; border: 1px solid var(--paper); background: transparent; color: var(--paper); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .modal-close:hover { background: var(--red); border-color: var(--red); }
                /* Key display in modal */
                .modal-key {
                    display: flex; align-items: center; gap: 10px;
                    padding: 14px 18px;
                    border-bottom: 2px solid var(--ink);
                    background: var(--red-soft);
                }
                .modal-key-val {
                    flex: 1; min-width: 0;
                    padding: 10px 12px; background: var(--paper); border: 2px solid var(--ink);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 800; letter-spacing: 0.04em; color: var(--ink);
                    word-break: break-all; overflow: hidden; text-overflow: ellipsis;
                    user-select: all;
                }
                .modal-key-copy {
                    flex-shrink: 0; padding: 9px 14px; border: 2px solid var(--ink); background: var(--ink); color: var(--paper); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .modal-key-copy:hover { background: var(--red); border-color: var(--red); }
                /* Client tabs in modal */
                .modal-clients {
                    display: grid;
                    grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
                    gap: 1px;
                    background: var(--ink);
                    border-bottom: 2px solid var(--ink);
                }
                .modal-client-tab {
                    display: flex; align-items: center; justify-content: center; gap: 7px;
                    padding: 12px 8px;
                    background: var(--paper); color: var(--muted); cursor: pointer;
                    font-family: var(--font-mono); font-size: 9.5px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    border-right: 1px solid var(--ink);
                    transition: background 120ms, color 120ms;
                }
                .modal-client-tab:last-child { border-right: 0; }
                .modal-client-tab svg { width: 15px; height: 15px; flex-shrink: 0; }
                .modal-client-tab.is-active { background: var(--ink); color: var(--paper); }
                /* Client content */
                .modal-content {
                    overflow-y: auto;
                    padding: 18px;
                    background: var(--color-surface-container-low, #ebe9e3);
                }
                .client-snippet-wrap { display: none; }
                .client-snippet-wrap.is-active { display: block; }
                .snippet-note {
                    margin-bottom: 10px;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--muted);
                }
                .snippet-code {
                    position: relative;
                    padding: 16px; background: var(--ink);
                    font-family: var(--font-mono); font-size: 11px; line-height: 1.7; color: var(--paper);
                    white-space: pre-wrap; word-break: break-all; letter-spacing: 0.02em;
                    border: 2px solid var(--ink); min-height: 70px;
                }
                .snippet-copy {
                    display: block; margin-top: 10px; width: 100%; padding: 10px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .snippet-copy:hover { background: var(--ink); color: var(--paper); }
            </style>
            <div class="panel-layout" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>

                <div class="section-card">
                    <div class="section-title-row">
                        <div>
                            <span class="section-kicker">[ Key Generator ]</span>
                            <h3 class="section-title">Create API Key</h3>
                        </div>
                        <button class="btn-primary" data-ref="toggleForm">+ New Key</button>
                    </div>
                    <div class="section-body" data-ref="formBody" hidden>
                        <div class="form-grid">
                            <div class="field"><label>Display Name</label><input data-ref="fName" placeholder="My API Key"></div>
                            <div class="field"><label>Routing Group</label><select data-ref="fGroup"></select></div>
                            <div class="field"><label>Max Budget (USD)</label><input data-ref="fBudget" type="number" value="10"></div>
                            <div class="field"><label>RPM Limit</label><input data-ref="fRpm" type="number" placeholder="120"></div>
                            <div class="field"><label>TPM Limit</label><input data-ref="fTpm" type="number" placeholder="120000"></div>
                        </div>
                        <div class="form-actions">
                            <button class="btn-primary" data-ref="submitBtn">Generate</button>
                            <button class="btn-ghost" data-ref="cancelBtn">Cancel</button>
                        </div>
                        <div class="key-reveal" data-ref="keyReveal">
                            <div class="kicker">/// Key Generated — Copy Now (will not be shown again)</div>
                            <div class="key-row">
                                <span data-ref="keyValue"></span>
                                <button class="copy-btn" data-ref="copyKeyBtn">Copy</button>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="section-card">
                    <div class="section-title-row">
                        <div>
                            <span class="section-kicker">[ Active Registry ]</span>
                            <h3 class="section-title">Your API Keys</h3>
                        </div>
                        <span style="font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.12em;text-transform:uppercase;" data-ref="keyCount"></span>
                    </div>
                    <div class="section-body">
                        <keel-data-table data-ref="table"></keel-data-table>
                    </div>
                </div>

                <!-- Global connect modal -->
                <div class="connect-overlay" data-ref="connectOverlay">
                    <div class="connect-modal">
                        <div class="modal-bar">
                            <span class="section-kicker" style="color:var(--red);margin-bottom:2px;">/// Quick Connect</span>
                            <button class="modal-close" data-ref="modalClose">ESC</button>
                        </div>
                        <div class="modal-key">
                            <span class="modal-key-val" data-ref="modalKeyVal"></span>
                            <button class="modal-key-copy" data-ref="modalKeyCopy">Copy Key</button>
                        </div>
                        <div class="modal-clients" data-ref="modalClients"></div>
                        <div class="modal-content" data-ref="modalContent"></div>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._relayBase = `${location.origin}/api/plugins/airelay/v1`;
        this._currentModalKey = null;
        this._groups = [];
        this._generatedSecrets = new Map();

        // Create form toggle
        this.refs.toggleForm.addEventListener('click', () => {
            this.refs.formBody.hidden = !this.refs.formBody.hidden;
            this.refs.keyReveal.classList.remove('is-visible');
            this._populateGroupSelect();
        });
        this.refs.cancelBtn.addEventListener('click', () => {
            this.refs.formBody.hidden = true;
        });
        // Copy newly generated key
        this.refs.copyKeyBtn.addEventListener('click', () => {
            copyToClipboard(this.refs.keyValue.textContent);
            this.refs.copyKeyBtn.textContent = 'Copied';
            setTimeout(() => { this.refs.copyKeyBtn.textContent = 'Copy'; }, 1500);
        });
        // Generate key
        this.refs.submitBtn.addEventListener('click', async () => {
            try {
                const data = await postJson(`${API.token}/v1/keys`, {
                    displayName: this.refs.fName.value || 'API Key',
                    groupId: this.refs.fGroup.value || 'default',
                    maxBudgetUsd: parseFloat(this.refs.fBudget.value) || 10,
                    rpmLimit: parseInt(this.refs.fRpm.value) || null,
                    tpmLimit: parseInt(this.refs.fTpm.value) || null,
                });
                this.refs.keyValue.textContent = data.rawKey;
                this.refs.keyReveal.classList.add('is-visible');
                if (data.key?.keyId) this._generatedSecrets.set(data.key.keyId, data.rawKey);
                this.refresh();
            } catch (e) { alert(e.message); }
        });
        // Modal close
        this.refs.modalClose.addEventListener('click', () => this._closeModal());
        this.refs.connectOverlay.addEventListener('click', (e) => {
            if (e.target === this.refs.connectOverlay) this._closeModal();
        });
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && this.refs.connectOverlay.classList.contains('is-open')) this._closeModal();
        });
        // Modal key copy
        this.refs.modalKeyCopy.addEventListener('click', () => {
            if (!this._currentModalKey) return;
            copyToClipboard(this._currentModalKey);
            this.refs.modalKeyCopy.textContent = 'Copied';
            setTimeout(() => { this.refs.modalKeyCopy.textContent = 'Copy Key'; }, 1500);
        });
        // Modal client tabs (delegated)
        this.refs.modalClients.addEventListener('click', (e) => {
            const tab = e.target.closest('[data-client]');
            if (!tab) return;
            this._setActiveClient(tab.dataset.client);
        });
        // Modal snippet copy (delegated)
        this.refs.modalContent.addEventListener('click', (e) => {
            const btn = e.target.closest('.snippet-copy');
            if (!btn) return;
            const pre = btn.previousElementSibling;
            if (!pre) return;
            copyToClipboard(pre.textContent);
            btn.textContent = 'Copied';
            setTimeout(() => { btn.textContent = 'Copy to Clipboard'; }, 1500);
        });
    }

    _populateGroupSelect() {
        const options = (this._groups.length ? this._groups : [{ groupId: 'default', name: 'Default' }])
            .map(g => `<option value="${escapeHtml(g.groupId)}">${escapeHtml(g.name || g.groupId)}</option>`).join('');
        this.refs.fGroup.innerHTML = options;
    }

    async refresh() {
        try {
            const [data, groups] = await Promise.all([
                requestJson(`${API.token}/admin/keys`),
                requestJson(`${API.airelay}/admin/groups`).catch(() => ({ groups: [] })),
            ]);
            this._groups = groups.groups || [];
            this._populateGroupSelect();
            this._render(data.keys || []);
        } catch (e) {
            this.refs.table.render({ headers: [], rows: [], emptyHtml: `<div class="km-empty">// Failed to load: ${escapeHtml(e.message)}</div>` });
        }
    }

    _render(keys) {
        this.refs.hero.render({
            label: 'Token Management',
            title: 'API Keys',
            metaHtml: `<div style="display:flex;flex-direction:column;align-items:flex-end;gap:4px;">
                <span style="font-family:var(--font-headline);font-size:clamp(32px,4vw,56px);line-height:0.82;letter-spacing:-0.06em;color:var(--paper);">${keys.length}</span>
                <span style="font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.16em;text-transform:uppercase;color:var(--paper);">Keys / Active</span>
            </div>`
        });
        if (this.refs.keyCount) this.refs.keyCount.textContent = `${keys.length} key${keys.length !== 1 ? 's' : ''}`;

        this.refs.table.render({
            silent: !!this._hasRendered,
            headers: ['Key ID', 'Name', 'Group', 'User', 'Status', 'Budget', 'Spent', 'Remaining', 'Action'],
            rows: keys.map(k => [
                `<code>${escapeHtml(k.keyId)}</code>`,
                escapeHtml(k.displayName || ''),
                `<code>${escapeHtml(k.groupId || 'default')}</code>`,
                `<samp>${escapeHtml(k.userId || '')}</samp>`,
                k.status === 'active'
                    ? `<span class="chip is-healthy"><span>●</span>Active</span>`
                    : `<span class="chip is-alert"><span>■</span>Revoked</span>`,
                `<data value="${k.maxBudgetUsd || 0}">$${(k.maxBudgetUsd || 0).toFixed(2)}</data>`,
                `<data value="${k.currentSpendUsd || 0}">$${(k.currentSpendUsd || 0).toFixed(4)}</data>`,
                `<data value="${k.remainingBudgetUsd || 0}">$${(k.remainingBudgetUsd || 0).toFixed(4)}</data>`,
                k.status === 'active'
                    ? `<button class="btn-ghost btn-connect" data-connect="${escapeHtml(k.keyId)}" style="margin-right:6px;padding:6px 10px;font-size:9px;">Connect</button><button class="btn-danger" data-delete-key="${escapeHtml(k.keyId)}">Delete</button>`
                    : ''
            ]),
            emptyHtml: '<div class="km-empty">// No API keys. Generate one above to get started.</div>'
        });
        this._hasRendered = true;

        // Connect buttons
        this.refs.table.shadowRoot.querySelectorAll('[data-connect]').forEach(btn => {
            btn.addEventListener('click', () => this._openModal(btn.dataset.connect));
        });
        // Delete buttons
        this.refs.table.shadowRoot.querySelectorAll('[data-delete-key]').forEach(btn => {
            btn.addEventListener('click', async () => {
                if (!confirm('Delete this key? This cannot be undone.')) return;
                try { await deleteJson(`${API.token}/v1/keys/${btn.dataset.deleteKey}`); this.refresh(); }
                catch (e) { alert(e.message); }
            });
        });
    }

    _openModal(keyId) {
        const rawSecret = this._generatedSecrets.get(keyId) || '';
        this._currentModalKey = rawSecret;
        if (rawSecret) {
            this.refs.modalKeyVal.textContent = rawSecret;
            this.refs.modalKeyCopy.disabled = false;
            this.refs.modalKeyCopy.textContent = 'Copy Key';
        } else {
            this.refs.modalKeyVal.textContent = `${keyId} · secret shown once at creation — paste the value you saved.`;
            this.refs.modalKeyCopy.disabled = true;
            this.refs.modalKeyCopy.textContent = 'No Secret';
        }
        const base = this._relayBase;
        const snippetKey = rawSecret || 'PASTE_YOUR_SAVED_SK_KEEL_KEY_HERE';

        // Render client tabs
        this.refs.modalClients.innerHTML = CLIENTS.map((c, i) =>
            `<div class="modal-client-tab ${i === 0 ? 'is-active' : ''}" data-client="${c.id}">
                ${c.icon}<span>${c.name}</span>
            </div>`
        ).join('');

        // Render client snippets
        this.refs.modalContent.innerHTML = CLIENTS.map((c, i) =>
            `<div class="client-snippet-wrap ${i === 0 ? 'is-active' : ''}" data-client-panel="${c.id}">
                <div class="snippet-note">${c.note}</div>
                <pre class="snippet-code">${c.snippet(base, snippetKey)}</pre>
                <button class="snippet-copy">Copy to Clipboard</button>
            </div>`
        ).join('');

        this.refs.connectOverlay.classList.add('is-open');
    }

    _setActiveClient(clientId) {
        this.refs.modalClients.querySelectorAll('.modal-client-tab').forEach(t => t.classList.toggle('is-active', t.dataset.client === clientId));
        this.refs.modalContent.querySelectorAll('.client-snippet-wrap').forEach(p => p.classList.toggle('is-active', p.dataset.clientPanel === clientId));
    }

    _closeModal() {
        this.refs.connectOverlay.classList.remove('is-open');
        this._currentModalKey = null;
    }
}

customElements.define('ai-panel-keys', PanelKeys);
