import { KeelElement } from './base/KeelElement.js';
import { requestJson, putJson, postJson, deleteJson } from '../api.js';
import { API } from '../config.js';
import { state, setTab } from '../state.js';
import { escapeHtml, formatDate, formatNumber } from '../utils.js';

const ADMIN = `${API.customerportal}/admin`;

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
                .status.suspended { color:var(--amber); }
                .status.deleted { color:var(--red); }
                .balance { font-family:var(--font-display); font-size:18px; }
                .empty { padding:30px; text-align:center; font-family:var(--font-mono); font-size:12px; font-weight:800; color:var(--muted); text-transform:uppercase; }
                .toolbar { display:flex; align-items:center; gap:12px; margin-bottom:18px; }
                .toolbar .spacer { flex:1; }
                .panel-err { display:none; margin-bottom:18px; padding:10px 12px; background:var(--red); color:var(--paper); font-family:var(--font-mono); font-size:11px; font-weight:800; }
                .panel-err.show { display:block; }
                .btn { padding:7px 11px; border:2px solid var(--ink); background:var(--paper); color:var(--ink); cursor:pointer; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.08em; text-transform:uppercase; }
                .btn:hover { background:var(--ink); color:var(--paper); }
                .btn-danger { border-color:var(--red); color:var(--red); }
                .btn-danger:hover { background:var(--red); color:var(--paper); }
                .overlay { position:fixed; inset:0; background:rgba(11,11,11,0.55); display:none; align-items:center; justify-content:center; z-index:900; }
                .overlay.open { display:flex; }
                .modal { width:min(720px, 95vw); max-height:90vh; overflow:auto; border:2px solid var(--ink); background:var(--paper); box-shadow:var(--shadow-lg); }
                .modal-head { display:flex; justify-content:space-between; align-items:center; padding:14px 18px; background:var(--ink); color:var(--paper); font-family:var(--font-display); font-size:16px; text-transform:uppercase; letter-spacing:-0.04em; }
                .modal-body { padding:18px; display:grid; gap:14px; }
                .tabs { display:flex; gap:0; border:2px solid var(--ink); }
                .tab { flex:1; padding:9px 0; text-align:center; cursor:pointer; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.1em; text-transform:uppercase; background:var(--paper); color:var(--ink); }
                .tab + .tab { border-left:2px solid var(--ink); }
                .tab.active { background:var(--surface-accent); color:var(--on-accent); }
                .tab-panel { display:none; }
                .tab-panel.active { display:grid; gap:14px; }
                .field label { display:block; margin-bottom:6px; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.12em; text-transform:uppercase; color:var(--muted); }
                .field input, .field select { width:100%; padding:10px 12px; border:2px solid var(--ink); background:var(--paper); color:var(--ink); font-family:var(--font-mono); font-size:12px; }
                .field input[disabled] { opacity:0.9; background:var(--color-surface-container-low, #ebe9e3); }
                .modal-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
                .actions-row { display:flex; gap:8px; justify-content:flex-end; }
                .keys-list { display:grid; gap:8px; }
                .key-item { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:8px 10px; border:1px solid var(--ink); font-family:var(--font-mono); font-size:10px; }
                .key-item code { background:var(--ink); color:var(--paper); padding:2px 6px; }
                .err { display:none; padding:10px 12px; background:var(--red); color:var(--paper); font-family:var(--font-mono); font-size:11px; font-weight:800; }
                .err.show { display:block; }
                .scroll { max-height:300px; overflow:auto; border:2px solid var(--ink); }
                .subtools { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:10px; }
                .submeta { font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.08em; text-transform:uppercase; color:var(--muted); }
                .pager-actions { display:flex; gap:8px; }
                .pager-actions .btn[disabled] { opacity:0.45; cursor:not-allowed; }
            </style>
            <keel-hero data-ref="hero"></keel-hero>
            <div class="toolbar">
                <span style="font-family:var(--font-mono);font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:0.1em;color:var(--muted);" data-ref="meta"></span>
                <span class="spacer"></span>
            </div>
            <div class="panel-err" data-ref="panelErr"></div>
            <div data-ref="tableWrap"><div class="empty">Loading...</div></div>
            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span data-ref="modalTitle">Customer Detail</span><button class="btn" data-ref="closeBtn" style="border-color:var(--paper);color:var(--paper);background:transparent;">Close</button></div>
                    <div class="modal-body">
                        <div class="err" data-ref="modalErr"></div>
                        <div class="tabs">
                            <div class="tab active" data-tab="profile">Profile</div>
                            <div class="tab" data-tab="keys">Keys</div>
                            <div class="tab" data-tab="usage">Usage</div>
                            <div class="tab" data-tab="ledger">Ledger</div>
                        </div>

                        <div class="tab-panel active" data-panel="profile">
                            <div class="modal-grid">
                                <div class="field"><label>Email</label><input data-ref="email" disabled /></div>
                                <div class="field"><label>Created</label><input data-ref="createdAt" disabled /></div>
                            </div>
                            <div class="modal-grid">
                                <div class="field"><label>Display Name</label><input data-ref="displayName" /></div>
                                <div class="field"><label>Status</label>
                                    <select data-ref="status">
                                        <option value="active">active</option>
                                        <option value="suspended">suspended</option>
                                    </select>
                                </div>
                            </div>
                            <div class="modal-grid">
                                <div class="field"><label>Balance Credits</label><input data-ref="balance" disabled /></div>
                                <div class="field"><label>Total Keys</label><input data-ref="totalKeys" disabled /></div>
                            </div>
                            <div class="actions-row">
                                <button class="btn btn-danger" data-ref="deleteBtn">Delete Customer</button>
                                <button class="btn" data-ref="saveProfileBtn">Save Profile</button>
                            </div>
                            <div class="field" style="border-top:2px solid var(--ink);padding-top:14px;">
                                <label>Adjust Credits (delta, can be negative)</label>
                                <div class="modal-grid">
                                    <input data-ref="creditDelta" type="number" placeholder="e.g. 1000 or -500" />
                                    <input data-ref="creditReason" placeholder="reason" value="admin_adjustment" />
                                </div>
                                <div class="actions-row" style="margin-top:10px;">
                                    <button class="btn" data-ref="adjustBtn">Apply Adjustment</button>
                                </div>
                            </div>
                        </div>

                        <div class="tab-panel" data-panel="keys">
                            <div class="keys-list" data-ref="keysList"><div class="empty">No keys</div></div>
                        </div>

                        <div class="tab-panel" data-panel="usage">
                            <div class="subtools">
                                <span class="submeta" data-ref="usageMeta">0-0 of 0</span>
                                <div class="pager-actions">
                                    <button class="btn" data-ref="usagePrevBtn" disabled>Prev</button>
                                    <button class="btn" data-ref="usageNextBtn" disabled>Next</button>
                                </div>
                            </div>
                            <div class="scroll" data-ref="usageWrap"><div class="empty">Loading…</div></div>
                        </div>

                        <div class="tab-panel" data-panel="ledger">
                            <div class="subtools">
                                <span class="submeta" data-ref="ledgerMeta">0-0 of 0</span>
                                <div class="pager-actions">
                                    <button class="btn" data-ref="ledgerPrevBtn" disabled>Prev</button>
                                    <button class="btn" data-ref="ledgerNextBtn" disabled>Next</button>
                                </div>
                            </div>
                            <div class="scroll" data-ref="ledgerWrap"><div class="empty">Loading…</div></div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label:'Customer Directory', title:'Customers', metaHtml:'' });
        this._selected = null;
        this._usagePage = this._newPageState();
        this._ledgerPage = this._newPageState();
        this.refs.closeBtn.addEventListener('click', () => this._closeModal());
        this.refs.overlay.addEventListener('click', (e) => { if (e.target === this.refs.overlay) this._closeModal(); });
        this.shadowRoot.querySelectorAll('[data-tab]').forEach(tab => {
            tab.addEventListener('click', () => this._switchTab(tab.dataset.tab));
        });
        this.refs.saveProfileBtn.addEventListener('click', () => this._saveProfile());
        this.refs.deleteBtn.addEventListener('click', () => this._deleteCustomer());
        this.refs.adjustBtn.addEventListener('click', () => this._adjustCredits());
        this.refs.keysList.addEventListener('click', (e) => {
            const btn = e.target.closest('[data-revoke-key]');
            if (btn) this._revokeKey(btn.dataset.revokeKey);
            const usageBtn = e.target.closest('[data-open-key-usage]');
            if (usageBtn) this._openUsageForKey(usageBtn.dataset.openKeyUsage);
        });
        this.refs.usagePrevBtn.addEventListener('click', () => this._stepPage('usage', 'prev'));
        this.refs.usageNextBtn.addEventListener('click', () => this._stepPage('usage', 'next'));
        this.refs.ledgerPrevBtn.addEventListener('click', () => this._stepPage('ledger', 'prev'));
        this.refs.ledgerNextBtn.addEventListener('click', () => this._stepPage('ledger', 'next'));
    }

    async refresh() {
        try {
            this._hidePanelErr();
            const data = await requestJson(`${ADMIN}/customers`);
            this._customers = data.customers || [];
            this.refs.meta.textContent = `${this._customers.length} customer${this._customers.length !== 1 ? 's' : ''}`;
            this.refs.hero.render({ label:'Customer Directory', title:'Customers', metaHtml:`<div style="padding:16px 22px;font-family:var(--font-headline);font-size:48px;line-height:0.8;letter-spacing:-0.05em;color:var(--paper);">${this._customers.length}</div>` });
            if (!this._customers.length) { this.refs.tableWrap.innerHTML = '<div class="empty">No customers yet.</div>'; return; }
            this.refs.tableWrap.innerHTML = `
                <table>
                    <thead><tr><th>ID</th><th>Email</th><th>Display Name</th><th>Status</th><th>Balance</th><th>Keys</th><th>Created</th><th>Manage</th></tr></thead>
                    <tbody>${this._customers.map(c => `
                        <tr>
                            <td>${escapeHtml(c.customerId)}</td>
                            <td>${escapeHtml(c.email)}</td>
                            <td>${escapeHtml(c.displayName)}</td>
                            <td class="status ${escapeHtml(c.status)}">${escapeHtml(c.status)}</td>
                            <td class="balance">${formatNumber(c.balanceCredits)}</td>
                            <td>${formatNumber(c.totalKeys)}</td>
                            <td>${formatDate(c.createdAt)}</td>
                            <td><button class="btn" data-view="${escapeHtml(c.customerId)}">Manage</button></td>
                        </tr>`).join('')}</tbody>
                </table>`;
            this.shadowRoot.querySelectorAll('[data-view]').forEach(btn => btn.addEventListener('click', () => this._openModal(btn.dataset.view)));
            const requestedCustomerId = state.activeTab === 'customers' ? state.tabQuery?.customerId : null;
            if (requestedCustomerId && requestedCustomerId !== this._selected?.customerId && this._customers.some(c => c.customerId === requestedCustomerId)) {
                await this._openModal(requestedCustomerId);
            }
        } catch (e) { this.refs.tableWrap.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`; }
    }

    async _openModal(customerId) {
        try {
            this._hidePanelErr();
            const detail = await requestJson(`${ADMIN}/customers/${encodeURIComponent(customerId)}`);
            this._selected = detail;
            this._usagePage = this._newPageState();
            this._ledgerPage = this._newPageState();
            this._hideErr();
            this._switchTab('profile');
            this.refs.modalTitle.textContent = `Customer · ${detail.customerId}`;
            this.refs.email.value = detail.email || '';
            this.refs.displayName.value = detail.displayName || '';
            this.refs.status.value = this._statusControlValue(detail.status);
            this.refs.balance.value = formatNumber(detail.balanceCredits || 0);
            this.refs.totalKeys.value = formatNumber(detail.totalKeys || 0);
            this.refs.createdAt.value = formatDate(detail.createdAt || '');
            this._renderKeys(detail.keys || []);
            this.refs.overlay.classList.add('open');
        } catch (e) { this._showPanelErr(e.message); }
    }

    _renderKeys(keys) {
        this.refs.keysList.innerHTML = keys.length ? keys.map(key => `
            <div class="key-item">
                <div><strong>${escapeHtml(key.name)}</strong> <code>${escapeHtml(key.prefix)}</code> · ${escapeHtml(key.status)}</div>
                <div style="display:flex;gap:8px;align-items:center;">
                    <button class="btn" data-open-key-usage="${escapeHtml(key.keyId)}">Usage</button>
                    ${key.status === 'active' ? `<button class="btn btn-danger" data-revoke-key="${escapeHtml(key.keyId)}">Revoke</button>` : '<span>revoked</span>'}
                </div>
            </div>`).join('') : '<div class="empty">No keys</div>';
    }

    _switchTab(tab) {
        this.shadowRoot.querySelectorAll('[data-tab]').forEach(t => t.classList.toggle('active', t.dataset.tab === tab));
        this.shadowRoot.querySelectorAll('[data-panel]').forEach(p => p.classList.toggle('active', p.dataset.panel === tab));
        if (tab === 'usage') this._loadUsage();
        if (tab === 'ledger') this._loadLedger();
    }

    _newPageState() {
        return { cursor: null, previous: [], nextCursor: null, pageSize: 20, total: 0, rangeLabel: '0-0 of 0', hasPrev: false, hasNext: false };
    }

    _usageNavigationQuery(keyId) {
        return {
            customerId: this._selected?.customerId,
            keyId,
        };
    }

    _pageState(response, page, key = 'records') {
        const rows = response?.[key] || [];
        const total = response?.total || 0;
        const start = rows.length ? page.previous.length * page.pageSize + 1 : 0;
        const end = rows.length ? start + rows.length - 1 : 0;
        return {
            cursor: page.cursor,
            previous: [...page.previous],
            pageSize: page.pageSize,
            total,
            nextCursor: response?.nextCursor || null,
            hasPrev: page.previous.length > 0,
            hasNext: Boolean(response?.nextCursor),
            rangeLabel: `${start}-${end} of ${total}`,
        };
    }

    _statusControlValue(status) {
        return status === 'locked' ? 'suspended' : (status || 'active');
    }

    _usageTableHtml(rows) {
        return `<table><thead><tr><th>Time</th><th>Model</th><th>Status</th><th>Group</th><th>Key</th><th>Request</th><th>In</th><th>Out</th><th>Credits</th><th>Cost</th></tr></thead>
            <tbody>${rows.map(r => `<tr>
                <td>${escapeHtml((r.createdAt||'').replace('T',' ').slice(0,19))}</td>
                <td><code>${escapeHtml(r.model || '')}</code></td>
                <td>${escapeHtml(String(r.status ?? 200))}</td>
                <td>${escapeHtml(r.groupId||'—')}</td>
                <td><code>${escapeHtml(r.keyId || '—')}</code></td>
                <td><samp>${escapeHtml(r.requestId||'')}</samp></td>
                <td>${formatNumber(r.inputTokens||0)}</td>
                <td>${formatNumber(r.outputTokens||0)}</td>
                <td>${formatNumber(r.creditCost||0)}</td>
                <td>${this._formatUsdMicros(r.usdMicrosCost||0)}</td>
            </tr>`).join('')}</tbody></table>`;
    }

    _ledgerTableHtml(rows) {
        return `<table><thead><tr><th>Time</th><th>Delta</th><th>Balance</th><th>Reason</th><th>Ref</th></tr></thead>
            <tbody>${rows.map(r => `<tr>
                <td>${escapeHtml((r.createdAt||'').replace('T',' ').slice(0,19))}</td>
                <td style="color:${(r.deltaCredits||0) < 0 ? 'var(--red)' : 'var(--green)'};font-weight:800;">${(r.deltaCredits||0) > 0 ? '+' : ''}${formatNumber(r.deltaCredits||0)}</td>
                <td>${formatNumber(r.balanceAfterCredits||0)}</td>
                <td>${escapeHtml(r.reason||'')}</td>
                <td><samp>${escapeHtml(r.refId||'—')}</samp></td>
            </tr>`).join('')}</tbody></table>`;
    }

    _formatUsdMicros(value) {
        return `$${(Number(value || 0) / 1_000_000).toFixed(4)}`;
    }

    async _loadUsage(cursor = this._usagePage.cursor) {
        if (!this._selected) return;
        try {
            const params = new URLSearchParams({ limit: String(this._usagePage.pageSize) });
            if (cursor) params.set('cursor', cursor);
            const data = await requestJson(`${ADMIN}/customers/${encodeURIComponent(this._selected.customerId)}/usage?${params}`);
            const rows = data.records || [];
            this._usagePage = this._pageState(data, { ...this._usagePage, cursor }, 'records');
            this.refs.usageMeta.textContent = this._usagePage.rangeLabel;
            this.refs.usagePrevBtn.disabled = !this._usagePage.hasPrev;
            this.refs.usageNextBtn.disabled = !this._usagePage.hasNext;
            this.refs.usageWrap.innerHTML = rows.length ? this._usageTableHtml(rows) : '<div class="empty">No usage records.</div>';
        } catch (e) { this.refs.usageWrap.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`; }
    }

    async _loadLedger(cursor = this._ledgerPage.cursor) {
        if (!this._selected) return;
        try {
            const params = new URLSearchParams({ limit: String(this._ledgerPage.pageSize) });
            if (cursor) params.set('cursor', cursor);
            const data = await requestJson(`${ADMIN}/customers/${encodeURIComponent(this._selected.customerId)}/ledger?${params}`);
            const rows = data.entries || [];
            this._ledgerPage = this._pageState(data, { ...this._ledgerPage, cursor }, 'entries');
            this.refs.ledgerMeta.textContent = this._ledgerPage.rangeLabel;
            this.refs.ledgerPrevBtn.disabled = !this._ledgerPage.hasPrev;
            this.refs.ledgerNextBtn.disabled = !this._ledgerPage.hasNext;
            this.refs.ledgerWrap.innerHTML = rows.length ? this._ledgerTableHtml(rows) : '<div class="empty">No ledger entries.</div>';
        } catch (e) { this.refs.ledgerWrap.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`; }
    }

    async _stepPage(kind, direction) {
        const pageKey = kind === 'usage' ? '_usagePage' : '_ledgerPage';
        const page = this[pageKey];
        if (direction === 'next' && page.nextCursor) {
            page.previous.push(page.cursor);
            page.cursor = page.nextCursor;
        } else if (direction === 'prev' && page.previous.length) {
            page.cursor = page.previous.pop() || null;
        } else {
            return;
        }
        this[pageKey] = page;
        if (kind === 'usage') await this._loadUsage(page.cursor);
        else await this._loadLedger(page.cursor);
    }

    async _saveProfile() {
        if (!this._selected) return;
        this._hideErr();
        try {
            await putJson(`${ADMIN}/customers/${encodeURIComponent(this._selected.customerId)}`, {
                displayName: this.refs.displayName.value.trim() || null,
                status: this.refs.status.value === 'suspended' ? 'locked' : this.refs.status.value,
            });
            await this._openModal(this._selected.customerId);
            this.refresh();
        } catch (e) { this._showErr(e.message); }
    }

    async _adjustCredits() {
        if (!this._selected) return;
        const delta = parseInt(this.refs.creditDelta.value, 10);
        if (!delta) { this._showErr('Enter a non-zero credit delta.'); return; }
        this._hideErr();
        try {
            await postJson(`${ADMIN}/customers/${encodeURIComponent(this._selected.customerId)}/credits`, {
                deltaCredits: delta,
                reason: this.refs.creditReason.value.trim() || 'admin_adjustment',
            });
            this.refs.creditDelta.value = '';
            await this._openModal(this._selected.customerId);
            this.refresh();
        } catch (e) { this._showErr(e.message); }
    }

    async _revokeKey(keyId) {
        if (!this._selected) return;
        if (!confirm(`Revoke key ${keyId}?`)) return;
        this._hideErr();
        try {
            await deleteJson(`${ADMIN}/customers/${encodeURIComponent(this._selected.customerId)}/keys/${encodeURIComponent(keyId)}`);
            await this._openModal(this._selected.customerId);
            this._switchTab('keys');
        } catch (e) { this._showErr(e.message); }
    }

    _openUsageForKey(keyId) {
        if (!keyId) return;
        setTab('usage', this._usageNavigationQuery(keyId));
        this._closeModal();
    }

    async _deleteCustomer() {
        if (!this._selected) return;
        if (!confirm(`Soft-delete customer ${this._selected.email}? Their keys will be revoked.`)) return;
        this._hideErr();
        try {
            await deleteJson(`${ADMIN}/customers/${encodeURIComponent(this._selected.customerId)}`);
            this._closeModal();
            this.refresh();
        } catch (e) { this._showErr(e.message); }
    }

    _showErr(msg) { this.refs.modalErr.textContent = msg; this.refs.modalErr.classList.add('show'); }
    _hideErr() { this.refs.modalErr.textContent = ''; this.refs.modalErr.classList.remove('show'); }
    _showPanelErr(msg) { this.refs.panelErr.textContent = msg; this.refs.panelErr.classList.add('show'); }
    _hidePanelErr() { this.refs.panelErr.textContent = ''; this.refs.panelErr.classList.remove('show'); }

    _closeModal() { this.refs.overlay.classList.remove('open'); this._selected = null; }
}

customElements.define('ai-panel-customers', PanelCustomers);
