import { KeelElement } from './base/KeelElement.js';
import { getJson, postJson, deleteJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml, formatDate, copyText } from '../utils.js';
import './shared/KeelDataTable.js';

const CLIENTS = [
    { name: 'Claude Code', icon: 'cc', snippet: 'claude --api-key SK --model claude-sonnet-4-20250514' },
    { name: 'Claude Desktop', icon: 'cd', snippet: 'CLAUDE_API_KEY=SK claude-desktop' },
    { name: 'Codex', icon: 'cx', snippet: 'export OPENAI_API_KEY=SK\nexport OPENAI_BASE_URL=BASE' },
    { name: 'OpenCode', icon: 'oc', snippet: 'opencode --api-key SK --base-url BASE' },
    { name: 'OpenClaw', icon: 'ow', snippet: 'OPENCLAW_API_KEY=SK\nOPENCLAW_BASE_URL=BASE openclaw' },
    { name: 'Hermes', icon: 'hm', snippet: 'HERMES_API_KEY=SK\nHERMES_BASE_URL=BASE hermes' },
];

const BASE_URL = window.location.origin + '/api/plugins/airelay/v1';

/** Show each client icon as a bold two-letter tag. */
function clientIcons() {
    return CLIENTS.map(c => `<span style="display:inline-block;padding:2px 6px;border:1px solid var(--ink);font-family:var(--font-mono);font-size:9px;font-weight:800;margin-right:4px;cursor:pointer;" title="${c.name}" data-client="${c.name}">${c.icon.toUpperCase()}</span>`).join('');
}

export class PanelKeys extends KeelElement {
    constructor() { super(); this._hasRendered = false; }
    hostStyles() { return ''; }

    template() {
        return `
            <style>
                .toolbar { display:flex; align-items:center; gap:12px; margin-bottom:18px; }
                .toolbar .spacer { flex:1; }
                .btn {
                    padding:11px 18px; border:2px solid var(--ink); background:var(--ink); color:var(--paper);
                    font-family:var(--font-mono); font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; cursor:pointer;
                }
                .btn:hover { background:var(--teal); border-color:var(--teal); }
                .btn-ghost {
                    padding:8px 12px; border:2px solid var(--ink); background:var(--paper); color:var(--ink);
                    font-family:var(--font-mono); font-size:10px; font-weight:800; text-transform:uppercase; letter-spacing:0.1em; cursor:pointer;
                }
                .btn-ghost:hover { background:var(--ink); color:var(--paper); }
                table { width:100%; border-collapse:collapse; font-family:var(--font-mono); font-size:11px; border:2px solid var(--ink); }
                th,td { padding:10px 14px; text-align:left; border-bottom:1px solid var(--bg, #e5e0d8); }
                th { font-size:9px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; color:var(--muted); background:var(--bg, #ebe9e3); }
                .status { font-weight:800; text-transform:uppercase; letter-spacing:0.06em; }
                .status.active { color:var(--green); }
                .status.revoked { color:var(--red); }
                .empty { padding:30px; text-align:center; font-family:var(--font-mono); font-size:12px; font-weight:800; color:var(--muted); text-transform:uppercase; letter-spacing:0.08em; }
                /* Modal */
                .overlay { position:fixed; inset:0; background:rgba(11,11,11,0.55); display:none; align-items:center; justify-content:center; z-index:900; }
                .overlay.open { display:flex; }
                .modal { width:min(560px,92vw); border:2px solid var(--ink); background:var(--paper); box-shadow:var(--shadow-lg); }
                .modal-head { display:flex; justify-content:space-between; padding:14px 18px; background:var(--ink); color:var(--paper); font-family:var(--font-display); font-size:16px; text-transform:uppercase; letter-spacing:-0.04em; }
                .modal-body { padding:18px; display:grid; gap:14px; }
                .field label { display:block; margin-bottom:6px; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.14em; text-transform:uppercase; color:var(--muted); }
                .field input,.field select { width:100%; padding:10px 12px; border:2px solid var(--ink); font-family:var(--font-mono); font-size:13px; color:var(--ink); background:var(--paper); }
                .key-box { padding:14px; border:2px solid var(--ink); background:var(--color-surface-container-low, #f0ede6); font-family:var(--font-mono); font-size:13px; word-break:break-all; display:flex; justify-content:space-between; align-items:center; gap:10px; }
                .key-val { flex:1; }
                .copy-btn { padding:6px 12px; border:1px solid var(--ink); background:var(--paper); font-family:var(--font-mono); font-size:10px; font-weight:800; cursor:pointer; }
                .copy-btn:disabled { opacity:0.5; cursor:not-allowed; }
                .error { display:none; padding:10px 14px; background:var(--red); color:var(--paper); font-family:var(--font-mono); font-size:11px; font-weight:800; }
                .error.show { display:block; }
                .connect-tabs { display:flex; gap:4px; flex-wrap:wrap; }
                .connect-tab { padding:6px 10px; border:1px solid var(--ink); background:var(--paper); font-family:var(--font-mono); font-size:9px; font-weight:800; text-transform:uppercase; cursor:pointer; }
                .connect-tab.active { background:var(--ink); color:var(--paper); }
                .snippet { padding:14px; border:1px solid var(--ink); background:var(--ink); color:var(--paper); font-family:var(--font-mono); font-size:11px; line-height:1.6; white-space:pre-wrap; word-break:break-all; }
            </style>
            <div class="toolbar">
                <span style="font-family:var(--font-mono);font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:0.1em;color:var(--muted);" data-ref="meta"></span>
                <span class="spacer"></span>
                <button class="btn" data-ref="newBtn">+ New Key</button>
            </div>
            <div data-ref="tableWrap"><div class="empty">Loading...</div></div>

            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span data-ref="modalTitle">New Key</span><button class="btn-ghost" style="color:var(--paper);border-color:var(--paper);" data-ref="closeBtn">&#10005;</button></div>
                    <div class="error" data-ref="error"></div>
                    <div class="modal-body">
                        <div class="field"><label>Key Name</label><input data-ref="fName" placeholder="My LLM Key" /></div>
                        <div class="field"><label>Routing Group</label><select data-ref="fGroup"><option>default</option></select></div>
                        <div class="field"><label>Monthly Budget (credits, optional)</label><input data-ref="fBudget" type="number" placeholder="Unlimited" /></div>
                        <button class="btn" data-ref="saveBtn">Create</button>
                    </div>
                </div>
            </div>

            <div class="overlay" data-ref="connectOverlay">
                <div class="modal">
                    <div class="modal-head"><span>Connect</span><button class="btn-ghost" style="color:var(--paper);border-color:var(--paper);" data-ref="connectClose">&#10005;</button></div>
                    <div class="modal-body">
                        <div class="field"><label>Your API Key</label></div>
                        <div class="key-box">
                            <span class="key-val" data-ref="connectKeyVal">—</span>
                            <button class="copy-btn" data-ref="connectKeyCopy">Copy</button>
                        </div>
                        <div class="field"><label>Configuration</label></div>
                        <div class="connect-tabs" data-ref="connectTabs"></div>
                        <div class="snippet" data-ref="connectSnippet"></div>
                        <button class="copy-btn" data-ref="connectSnippetCopy">Copy Snippet</button>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._keys = []; this._groups = []; this._generatedSecrets = new Map();
        this.refs.newBtn.addEventListener('click', () => this._openModal());
        this.refs.closeBtn.addEventListener('click', () => this._closeModal());
        this.refs.overlay.addEventListener('click', (e) => { if (e.target === this.refs.overlay) this._closeModal(); });
        this.refs.saveBtn.addEventListener('click', () => this._save());
        this.refs.connectClose.addEventListener('click', () => { this.refs.connectOverlay.classList.remove('open'); });
        this.refs.connectOverlay.addEventListener('click', (e) => { if (e.target === this.refs.connectOverlay) this.refs.connectOverlay.classList.remove('open'); });
        this.refs.connectKeyCopy.addEventListener('click', () => this._copyConnectKey());
        this.refs.connectSnippetCopy.addEventListener('click', () => this._copySnippet());
    }

    async refresh() {
        try {
            const [keysResp] = await Promise.all([getJson(`${API.customerPortal}/v1/customer/keys`)]);
            this._keys = keysResp.keys || [];
            this._render();
        } catch (e) { this.refs.tableWrap.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`; }
    }

    _render() {
        this.refs.meta.textContent = `${this._keys.length} key${this._keys.length !== 1 ? 's' : ''}`;
        if (!this._keys.length) { this.refs.tableWrap.innerHTML = '<div class="empty">No API keys yet. Create one to get started.</div>'; return; }
        const headers = ['Name', 'Prefix', 'Group', 'Status', 'Created', ''];
        const rows = this._keys.map(k => [
            escapeHtml(k.name),
            escapeHtml(k.prefix),
            escapeHtml(k.routingGroupId),
            `<span class="status ${k.status}">${k.status}</span>`,
            formatDate(k.createdAt),
            `<button class="btn-ghost" data-connect="${escapeHtml(k.keyId)}">Connect</button>${k.status === 'active' ? ` <button class="btn-ghost" data-delete-key="${escapeHtml(k.keyId)}" style="border-color:var(--red);color:var(--red);">Delete</button>` : ''}`
        ]);
        let table = this.refs.tableWrap.querySelector('keel-data-table');
        if (!table) {
            table = document.createElement('keel-data-table');
            this.refs.tableWrap.innerHTML = '';
            this.refs.tableWrap.appendChild(table);
        }
        table.render({ silent: !!this._hasRendered, headers, rows, emptyHtml: '<div class="empty">No API keys yet.</div>' });
        this._hasRendered = true;
        const root = table.shadowRoot || table;
        root.querySelectorAll('[data-connect]').forEach(btn => btn.addEventListener('click', () => this._openConnect(btn.dataset.connect)));
        root.querySelectorAll('[data-delete-key]').forEach(btn => btn.addEventListener('click', () => this._deleteKey(btn.dataset.deleteKey)));
    }

    _openModal() {
        this.refs.modalTitle.textContent = 'New Key';
        this.refs.fName.value = '';
        this.refs.fBudget.value = '';
        this.refs.error.classList.remove('show');
        this.refs.overlay.classList.add('open');
        this._populateGroups();
    }

    _closeModal() { this.refs.overlay.classList.remove('open'); }

    async _populateGroups() {
        try {
            const data = await fetch('/api/plugins/airelay/admin/groups').then(r => r.json());
            this._groups = data.groups || [];
            this.refs.fGroup.innerHTML = this._groups.map(g => `<option>${escapeHtml(g.groupId)}</option>`).join('') || '<option>default</option>';
        } catch (e) { /* keep default */ }
    }

    async _save() {
        const name = this.refs.fName.value.trim();
        const groupId = this.refs.fGroup.value || 'default';
        const monthlyBudgetCredits = this.refs.fBudget.value ? parseInt(this.refs.fBudget.value) : null;
        if (!name) { this._error('Key name is required.'); return; }
        this.refs.saveBtn.disabled = true;
        try {
            const result = await postJson(`${API.customerPortal}/v1/customer/keys`, { name, routingGroupId: groupId, monthlyBudgetCredits });
            this._generatedSecrets.set(result.key.keyId, result.rawKey);
            this._closeModal();
            this.refresh();
            // Show connect immediately
            setTimeout(() => this._openConnect(result.key.keyId), 100);
        } catch (e) { this._error(e.message); }
        this.refs.saveBtn.disabled = false;
    }

    _openConnect(keyId) {
        const rawSecret = this._generatedSecrets.get(keyId) || '';
        this._currentConnectKey = rawSecret;
        if (rawSecret) {
            this.refs.connectKeyVal.textContent = rawSecret;
            this.refs.connectKeyCopy.disabled = false;
            this.refs.connectKeyCopy.textContent = 'Copy';
        } else {
            this.refs.connectKeyVal.textContent = `${keyId} · secret was shown only at creation. If you lost it, delete this key and create a new one.`;
            this.refs.connectKeyCopy.disabled = true;
            this.refs.connectKeyCopy.textContent = 'No Secret';
        }
        // Render client tabs
        this.refs.connectTabs.innerHTML = CLIENTS.map((c, i) =>
            `<button class="connect-tab${i === 0 ? ' active' : ''}" data-client-idx="${i}">${c.icon.toUpperCase()}</button>`).join('');
        this.refs.connectTabs.querySelectorAll('[data-client-idx]').forEach(btn => {
            btn.addEventListener('click', () => {
                this.refs.connectTabs.querySelectorAll('.connect-tab').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                this._renderConnectSnippet(parseInt(btn.dataset.clientIdx));
            });
        });
        this._renderConnectSnippet(0);
        this.refs.connectOverlay.classList.add('open');
    }

    _renderConnectSnippet(idx) {
        const c = CLIENTS[idx];
        const sk = this._currentConnectKey || 'PASTE_YOUR_KEY_HERE';
        this.refs.connectSnippet.textContent = c.snippet.replace(/SK/g, sk).replace(/BASE/g, BASE_URL);
        this.refs.connectSnippetCopy.textContent = 'Copy Snippet';
    }

    async _copyConnectKey() {
        if (!this._currentConnectKey) return;
        if (await copyText(this._currentConnectKey)) {
            this.refs.connectKeyCopy.textContent = 'Copied!';
            setTimeout(() => { this.refs.connectKeyCopy.textContent = 'Copy'; }, 2000);
        }
    }

    async _copySnippet() {
        if (await copyText(this.refs.connectSnippet.textContent)) {
            this.refs.connectSnippetCopy.textContent = 'Copied!';
            setTimeout(() => { this.refs.connectSnippetCopy.textContent = 'Copy Snippet'; }, 2000);
        }
    }

    async _deleteKey(keyId) {
        if (!confirm('Delete this key? This cannot be undone.')) return;
        try {
            await deleteJson(`${API.customerPortal}/v1/customer/keys/${encodeURIComponent(keyId)}`);
            this.refresh();
        } catch (e) { alert(e.message); }
    }

    _error(msg) { this.refs.error.textContent = msg; this.refs.error.classList.add('show'); }
}

customElements.define('customer-panel-keys', PanelKeys);
