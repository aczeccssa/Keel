import { KeelElement } from './base/KeelElement.js';
import { requestJson } from '../api.js';
import { escapeHtml, formatDate, formatNumber } from '../utils.js';

export class PanelCustomers extends KeelElement {
    hostStyles() { return ''; }

    template() {
        return `
            <style>
                table { width:100%; border-collapse:collapse; font-family:var(--font-mono); font-size:11px; border:2px solid var(--ink); }
                th,td { padding:10px 14px; text-align:left; border-bottom:1px solid var(--color-surface-container-low, #e5e0d8); }
                th { font-size:9px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; color:var(--muted); background:var(--color-surface-container-low, #ebe9e3); }
                .status { font-weight:800; text-transform:uppercase; letter-spacing:0.06em; }
                .status.active { color:var(--green); }
                .status.locked { color:var(--amber); }
                .status.deleted { color:var(--red); }
                .balance { font-family:var(--font-display); font-size:18px; }
                .empty { padding:30px; text-align:center; font-family:var(--font-mono); font-size:12px; font-weight:800; color:var(--muted); text-transform:uppercase; }
                .toolbar { display:flex; align-items:center; gap:12px; margin-bottom:18px; }
                .toolbar .spacer { flex:1; }
                .btn { padding:7px 11px; border:2px solid var(--ink); background:var(--paper); color:var(--ink); cursor:pointer; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.08em; text-transform:uppercase; }
                .btn:hover { background:var(--ink); color:var(--paper); }
                .overlay { position:fixed; inset:0; background:rgba(11,11,11,0.55); display:none; align-items:center; justify-content:center; z-index:900; }
                .overlay.open { display:flex; }
                .modal { width:min(620px, 94vw); border:2px solid var(--ink); background:var(--paper); box-shadow:var(--shadow-lg); }
                .modal-head { display:flex; justify-content:space-between; align-items:center; padding:14px 18px; background:var(--ink); color:var(--paper); font-family:var(--font-display); font-size:16px; text-transform:uppercase; letter-spacing:-0.04em; }
                .modal-body { padding:18px; display:grid; gap:14px; }
                .field label { display:block; margin-bottom:6px; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.12em; text-transform:uppercase; color:var(--muted); }
                .field input { width:100%; padding:10px 12px; border:2px solid var(--ink); background:var(--paper); color:var(--ink); font-family:var(--font-mono); font-size:12px; }
                .field input[disabled] { opacity:0.9; background:var(--color-surface-container-low, #ebe9e3); }
                .modal-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
                .keys-list { display:grid; gap:8px; }
                .key-item { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:8px 10px; border:1px solid var(--ink); font-family:var(--font-mono); font-size:10px; }
                .key-item code { background:var(--ink); color:var(--paper); padding:2px 6px; }
                .hint { font-family:var(--font-mono); font-size:11px; color:var(--muted); line-height:1.6; }
            </style>
            <keel-hero data-ref="hero"></keel-hero>
            <div class="toolbar">
                <span style="font-family:var(--font-mono);font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:0.1em;color:var(--muted);" data-ref="meta"></span>
                <span class="spacer"></span>
            </div>
            <div data-ref="tableWrap"><div class="empty">Loading...</div></div>
            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span data-ref="modalTitle">Customer Detail</span><button class="btn" data-ref="closeBtn" style="border-color:var(--paper);color:var(--paper);background:transparent;">Close</button></div>
                    <div class="modal-body">
                        <div class="modal-grid">
                            <div class="field"><label>Email</label><input data-ref="email" disabled /></div>
                            <div class="field"><label>Status</label><input data-ref="status" disabled /></div>
                        </div>
                        <div class="modal-grid">
                            <div class="field"><label>Display Name</label><input data-ref="displayName" disabled /></div>
                            <div class="field"><label>Balance Credits</label><input data-ref="balance" disabled /></div>
                        </div>
                        <div class="modal-grid">
                            <div class="field"><label>Total Keys</label><input data-ref="totalKeys" disabled /></div>
                            <div class="field"><label>Created</label><input data-ref="createdAt" disabled /></div>
                        </div>
                        <div class="field"><label>Customer Keys</label><div class="keys-list" data-ref="keysList"><div class="empty">No keys</div></div></div>
                        <div class="hint">This page is intentionally read-only. Management actions like credit adjustment, key revocation, status edits, and deletion are hidden from the primary UI.</div>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label:'Customer Directory', title:'Customers', metaHtml:'' });
        this._selected = null;
        this.refs.closeBtn.addEventListener('click', () => this._closeModal());
        this.refs.overlay.addEventListener('click', (e) => { if (e.target === this.refs.overlay) this._closeModal(); });
    }

    async refresh() {
        try {
            const data = await requestJson('/api/plugins/customer-portal/admin/customers');
            this._customers = data.customers || [];
            this.refs.meta.textContent = `${this._customers.length} customer${this._customers.length !== 1 ? 's' : ''}`;
            this.refs.hero.render({ label:'Customer Directory', title:'Customers', metaHtml:`<div style="padding:16px 22px;font-family:var(--font-headline);font-size:48px;line-height:0.8;letter-spacing:-0.05em;color:var(--paper);">${this._customers.length}</div>` });
            if (!this._customers.length) { this.refs.tableWrap.innerHTML = '<div class="empty">No customers yet.</div>'; return; }
            this.refs.tableWrap.innerHTML = `
                <table>
                    <thead><tr><th>ID</th><th>Email</th><th>Display Name</th><th>Status</th><th>Balance</th><th>Keys</th><th>Created</th><th>View</th></tr></thead>
                    <tbody>${this._customers.map(c => `
                        <tr>
                            <td>${escapeHtml(c.customerId)}</td>
                            <td>${escapeHtml(c.email)}</td>
                            <td>${escapeHtml(c.displayName)}</td>
                            <td class="status ${c.status}">${escapeHtml(c.status)}</td>
                            <td class="balance">${formatNumber(c.balanceCredits)}</td>
                            <td>${formatNumber(c.totalKeys)}</td>
                            <td>${formatDate(c.createdAt)}</td>
                            <td><button class="btn" data-view="${escapeHtml(c.customerId)}">View</button></td>
                        </tr>`).join('')}</tbody>
                </table>`;
            this.shadowRoot.querySelectorAll('[data-view]').forEach(btn => btn.addEventListener('click', () => this._openModal(btn.dataset.view)));
        } catch (e) { this.refs.tableWrap.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`; }
    }

    async _openModal(customerId) {
        try {
            const detail = await requestJson(`/api/plugins/customer-portal/admin/customers/${encodeURIComponent(customerId)}`);
            this._selected = detail;
            this.refs.modalTitle.textContent = `Customer · ${detail.customerId}`;
            this.refs.email.value = detail.email || '';
            this.refs.displayName.value = detail.displayName || '';
            this.refs.status.value = detail.status || '';
            this.refs.balance.value = formatNumber(detail.balanceCredits || 0);
            this.refs.totalKeys.value = formatNumber(detail.totalKeys || 0);
            this.refs.createdAt.value = formatDate(detail.createdAt || '');
            this.refs.keysList.innerHTML = (detail.keys || []).length ? detail.keys.map(key => `
                <div class="key-item"><div><strong>${escapeHtml(key.name)}</strong> <code>${escapeHtml(key.prefix)}</code></div><span>${escapeHtml(key.status)}</span></div>`).join('') : '<div class="empty">No keys</div>';
            this.refs.overlay.classList.add('open');
        } catch (e) { alert(e.message); }
    }

    _closeModal() { this.refs.overlay.classList.remove('open'); this._selected = null; }
}

customElements.define('ai-panel-customers', PanelCustomers);
