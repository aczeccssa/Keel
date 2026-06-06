import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson, deleteJson } from '../api.js';
import { escapeHtml, formatDate, formatNumber } from '../utils.js';

export class PanelRedemptionCodes extends KeelElement {
    hostStyles() { return ''; }

    template() {
        return `
            <style>
                table { width:100%; border-collapse:collapse; font-family:var(--font-mono); font-size:11px; border:2px solid var(--ink); }
                th,td { padding:10px 14px; text-align:left; border-bottom:1px solid var(--color-surface-container-low, #e5e0d8); }
                th { font-size:9px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; color:var(--muted); background:var(--color-surface-container-low, #ebe9e3); }
                .toolbar { display:flex; align-items:center; gap:12px; margin-bottom:18px; }
                .toolbar .spacer { flex:1; }
                .btn {
                    padding:11px 18px; border:2px solid var(--ink); background:var(--ink); color:var(--paper);
                    font-family:var(--font-mono); font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; cursor:pointer;
                }
                .btn:hover { background:var(--red); border-color:var(--red); }
                .btn-danger {
                    padding:6px 10px; border:1px solid var(--red); color:var(--red); background:transparent;
                    font-family:var(--font-mono); font-size:9px; font-weight:800; text-transform:uppercase; cursor:pointer;
                }
                .btn-danger:hover { background:var(--red); color:var(--paper); }
                .status { font-weight:800; text-transform:uppercase; letter-spacing:0.06em; }
                .status.active { color:var(--green); }
                .status.redeemed { color:var(--amber); }
                .status.revoked { color:var(--red); }
                .empty { padding:30px; text-align:center; font-family:var(--font-mono); font-size:12px; font-weight:800; color:var(--muted); text-transform:uppercase; }
                .overlay { position:fixed; inset:0; background:rgba(11,11,11,0.55); display:none; align-items:center; justify-content:center; z-index:900; }
                .overlay.open { display:flex; }
                .modal { width:min(420px,92vw); border:2px solid var(--ink); background:var(--paper); box-shadow:var(--shadow-lg); }
                .modal-head { display:flex; justify-content:space-between; padding:14px 18px; background:var(--ink); color:var(--paper); font-family:var(--font-display); font-size:16px; text-transform:uppercase; letter-spacing:-0.04em; }
                .modal-body { padding:18px; display:grid; gap:14px; }
                .field label { display:block; margin-bottom:6px; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.14em; text-transform:uppercase; color:var(--muted); }
                .field input { width:100%; padding:10px 12px; border:2px solid var(--ink); font-family:var(--font-mono); font-size:13px; }
                .error { display:none; padding:10px 14px; background:var(--red); color:var(--paper); font-family:var(--font-mono); font-size:11px; font-weight:800; }
                .error.show { display:block; }
            </style>
            <keel-hero data-ref="hero"></keel-hero>
            <div class="toolbar">
                <span style="font-family:var(--font-mono);font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:0.1em;color:var(--muted);" data-ref="meta"></span>
                <span class="spacer"></span>
                <button class="btn" data-ref="newBtn">+ New Code</button>
            </div>
            <div data-ref="tableWrap"><div class="empty">Loading...</div></div>
            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span>Mint Code</span><button class="btn" style="border-color:var(--paper);color:var(--paper);padding:4px 10px;font-size:10px;" data-ref="closeBtn">&#10005;</button></div>
                    <div class="error" data-ref="error"></div>
                    <div class="modal-body">
                        <div class="field"><label>Face Value (credits)</label><input data-ref="fValue" type="number" placeholder="1000" /></div>
                        <div class="field"><label>Custom Code (optional)</label><input data-ref="fCode" placeholder="Auto-generated" /></div>
                        <div class="field"><label>Expires In Days (optional)</label><input data-ref="fExpiry" type="number" placeholder="Never" /></div>
                        <button class="btn" data-ref="saveBtn">Mint Code</button>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label:'Redemption Codes', title:'Codes', metaHtml:'' });
        this.refs.newBtn.addEventListener('click', () => this.refs.overlay.classList.add('open'));
        this.refs.closeBtn.addEventListener('click', () => this.refs.overlay.classList.remove('open'));
        this.refs.overlay.addEventListener('click', (e) => { if (e.target === this.refs.overlay) this.refs.overlay.classList.remove('open'); });
        this.refs.saveBtn.addEventListener('click', () => this._save());
    }

    async refresh() {
        try {
            const data = await requestJson('/api/plugins/customer-portal/admin/codes');
            this._codes = data.codes || [];
            this.refs.meta.textContent = `${this._codes.length} code${this._codes.length !== 1 ? 's' : ''}`;
            if (!this._codes.length) { this.refs.tableWrap.innerHTML = '<div class="empty">No redemption codes yet.</div>'; return; }
            this.refs.tableWrap.innerHTML = `
                <table>
                    <thead><tr><th>Code</th><th>Value</th><th>Status</th><th>Redeemed By</th><th>Expires</th><th>Created</th><th></th></tr></thead>
                    <tbody>${this._codes.map(c => `
                        <tr>
                            <td><strong>${escapeHtml(c.code)}</strong></td>
                            <td>${formatNumber(c.faceValueCredits)}</td>
                            <td class="status ${c.status}">${c.status}</td>
                            <td>${c.redeemedByCustomerId || '—'}</td>
                            <td>${c.expiresAt ? formatDate(c.expiresAt) : '—'}</td>
                            <td>${formatDate(c.createdAt)}</td>
                            <td>${c.status === 'active' ? `<button class="btn-danger" data-revoke="${escapeHtml(c.code)}">Revoke</button>` : ''}</td>
                        </tr>
                    `).join('')}</tbody>
                </table>`;
            this.shadowRoot.querySelectorAll('[data-revoke]').forEach(btn => {
                btn.addEventListener('click', () => this._revoke(btn.dataset.revoke));
            });
        } catch (e) { this.refs.tableWrap.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`; }
    }

    async _save() {
        const faceValueCredits = parseInt(this.refs.fValue.value);
        if (!faceValueCredits || faceValueCredits <= 0) { this._error('Face value must be positive.'); return; }
        const code = this.refs.fCode.value.trim() || null;
        const expiresInDays = this.refs.fExpiry.value ? parseInt(this.refs.fExpiry.value) : null;
        this.refs.saveBtn.disabled = true;
        try {
            await postJson('/api/plugins/customer-portal/admin/codes', { faceValueCredits, code, expiresInDays });
            this.refs.overlay.classList.remove('open');
            this.refresh();
        } catch (e) { this._error(e.message); }
        this.refs.saveBtn.disabled = false;
    }

    async _revoke(code) {
        if (!confirm(`Revoke redemption code ${code}?`)) return;
        try { await deleteJson(`/api/plugins/customer-portal/admin/codes/${encodeURIComponent(code)}`); this.refresh(); }
        catch (e) { alert(e.message); }
    }

    _error(msg) { this.refs.error.textContent = msg; this.refs.error.classList.add('show'); }
}

customElements.define('ai-panel-redemption-codes', PanelRedemptionCodes);
