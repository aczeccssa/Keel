import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson, putJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

export class PanelUsers extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .panel-layout { display: flex; flex-direction: column; gap: 28px; }
                .panel-notice {
                    display: none;
                    padding: 12px 14px;
                    border: 2px solid var(--red);
                    background: var(--red-soft);
                    color: var(--ink);
                    font-family: var(--font-mono);
                    font-size: 11px;
                    font-weight: 800;
                    letter-spacing: 0.08em;
                    text-transform: uppercase;
                }
                .panel-notice.show { display: block; }
                .section-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 28px;
                    box-shadow: var(--shadow-sm);
                    border: 2px solid var(--ink);
                }
                .section-title {
                    font-family: var(--font-headline);
                    font-size: 20px;
                    font-weight: 500;
                    margin: 0 0 20px;
                    color: var(--ink);
                }
                .toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
                .btn-primary {
                    padding: 10px 20px; border: 0; border-radius: 0;
                    background: var(--navy); color: #f8fafc; font-size: 11px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; cursor: pointer;
                }
                .btn-primary:hover { background: var(--navy-2); }
                .btn-action {
                    padding: 5px 12px; border: 1px solid var(--line-strong); border-radius: 0;
                    background: transparent; color: var(--ink); font-size: 9px; font-weight: 700;
                    text-transform: uppercase; letter-spacing: 0.06em; cursor: pointer;
                }
                .btn-action:hover { background: var(--panel-strong); }
                .btn-action.danger { border-color: var(--red); color: var(--red); }
                .btn-action.danger:hover { background: var(--red-soft); }
                .btn-action.success { border-color: var(--green); color: var(--green); }
                .btn-action.success:hover { background: var(--green-soft); }
                .btn-ghost {
                    padding: 10px 18px; border: 1px solid var(--line-strong); border-radius: 0;
                    background: transparent; color: var(--ink); font-size: 10px; font-weight: 700;
                    letter-spacing: 0.08em; text-transform: uppercase; cursor: pointer;
                }
                .btn-ghost:hover { background: var(--panel-strong); }
                .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; }
                .field label {
                    display: block; font-size: 10px; font-weight: 800; text-transform: uppercase;
                    letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px;
                }
                .field input {
                    width: 100%; padding: 10px 12px; border: 0; border-radius: var(--radius-sm);
                    font-size: 13px; background: var(--color-surface-container-high, #e4e2dc); color: var(--ink);
                    transition: all 150ms ease;
                }
                .field input:focus {
                    outline: none; box-shadow: var(--shadow-sm); background: var(--color-surface-container-lowest, #fff);
                }
                .form-actions { display: flex; gap: 12px; }
                .empty { text-align: center; color: var(--muted); padding: 40px; font-size: 13px; }
            </style>
            <div class="panel-layout">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="panel-notice" data-ref="panelNotice"></div>
                <div class="section-card">
                    <h3 class="section-title">Admin Users</h3>
                    <div style="font-family:var(--font-mono);font-size:10px;color:var(--muted);margin:-12px 0 16px;">B-end accounts that sign into this admin UI. Not routing groups and not end-customers.</div>
                    <keel-data-table data-ref="usersTable"></keel-data-table>
                </div>
                <div class="section-card">
                    <div class="toolbar">
                        <div>
                            <h3 class="section-title" style="margin:0;">Account Groups</h3>
                            <div style="font-family:var(--font-mono);font-size:10px;color:var(--muted);margin-top:4px;">Account-level defaults & permissions for admin users (RPM/TPM/budget).</div>
                        </div>
                        <button class="btn-primary" data-ref="addGroupBtn">Create Group</button>
                    </div>
                    <div class="section-card" data-ref="groupForm" hidden style="background:var(--color-surface-container-lowest,#fff);margin-bottom:20px;">
                        <h3 class="section-title">New Group</h3>
                        <div class="form-grid">
                            <div class="field"><label>Group ID</label><input data-ref="gId" placeholder="group-id"></div>
                            <div class="field"><label>Name</label><input data-ref="gName" placeholder="Group name"></div>
                            <div class="field"><label>Cost Multiplier</label><input data-ref="gMultiplier" type="number" step="0.1" value="1.0"></div>
                            <div class="field"><label>Default Budget (USD)</label><input data-ref="gBudget" type="number" value="10"></div>
                        </div>
                        <div class="form-actions">
                            <button class="btn-primary" data-ref="submitGroupBtn">Create</button>
                            <button class="btn-ghost" data-ref="cancelGroupBtn">Cancel</button>
                        </div>
                    </div>
                    <keel-data-table data-ref="groupsTable"></keel-data-table>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label: 'B-End Account Administration', title: 'Admin Users', metaHtml: '' });
        this.refs.addGroupBtn.addEventListener('click', () => { this.refs.groupForm.hidden = false; });
        this.refs.cancelGroupBtn.addEventListener('click', () => { this.refs.groupForm.hidden = true; });
        this.refs.submitGroupBtn.addEventListener('click', async () => {
            const btn = this.refs.submitGroupBtn;
            this._hideNotice();
            btn.disabled = true;
            const originalText = btn.textContent;
            btn.textContent = 'Creating…';
            try {
                await postJson(`${API.account}/admin/groups`, {
                    groupId: this.refs.gId.value,
                    name: this.refs.gName.value,
                    costMultiplier: parseFloat(this.refs.gMultiplier.value) || 1.0,
                    defaultBudgetUsd: parseFloat(this.refs.gBudget.value) || 10,
                });
                this.refs.groupForm.hidden = true;
                await this.refresh();
            } catch (e) {
                this._showNotice(e.message);
            } finally {
                btn.disabled = false;
                btn.textContent = originalText;
            }
        });
    }

    async refresh() {
        try {
            const [users, groups] = await Promise.all([
                requestJson(`${API.account}/admin/users`),
                requestJson(`${API.account}/admin/groups`),
            ]);
            this._groups = groups.groups || [];
            this._renderUsers(users.users || []);
            this._renderGroups(this._groups);
        } catch (e) {
            this._showNotice(e.message);
            this.refs.usersTable.render({ headers: [], rows: [], emptyHtml: `<div class="empty">Failed: ${e.message}</div>` });
        }
    }

    _renderUsers(users) {
        this.refs.usersTable.render({
            silent: !!this._hasUsersRendered,
            headers: ['User ID', 'Email', 'Display Name', 'Role', 'Account Group', 'Status', 'Action'],
            rows: users.map(u => [
                `<code style="font-size:11px;">${u.userId}</code>`,
                escapeHtml(u.email),
                escapeHtml(u.displayName),
                `<select class="btn-action" data-user-role="${u.userId}">
                    ${this._option('user', 'User', u.role)}
                    ${this._option('admin', 'Admin', u.role)}
                </select>`,
                `<select class="btn-action" data-user-group="${u.userId}">
                    ${(this._groups || []).map(group => this._option(group.groupId, group.name || group.groupId, u.groupId)).join('')}
                </select>`,
                `<select class="btn-action" data-user-status="${u.userId}">
                    ${this._option('active', 'Active', u.status)}
                    ${this._option('suspended', 'Suspended', u.status)}
                </select>`,
                `<button class="btn-action success" data-save-user="${u.userId}">Save</button>`
            ]),
            emptyHtml: '<div class="empty">No users found.</div>'
        });
        this._hasUsersRendered = true;
        this.refs.usersTable.shadowRoot.querySelectorAll('[data-save-user]').forEach(btn => {
            btn.addEventListener('click', async () => {
                await this._saveUser(btn.dataset.saveUser, btn);
            });
        });
    }

    _renderGroups(groups) {
        this.refs.groupsTable.render({
            silent: !!this._hasGroupsRendered,
            headers: ['Account Group ID', 'Name', 'Cost Multiplier', 'Default RPM', 'Default TPM', 'Default Budget'],
            rows: groups.map(g => [
                `<code>${g.groupId}</code>`,
                escapeHtml(g.name),
                String(g.costMultiplier),
                g.defaultRpm ?? 'unlimited',
                g.defaultTpm ?? 'unlimited',
                `$${(g.defaultBudgetUsd || 0).toFixed(2)}`
            ]),
            emptyHtml: '<div class="empty">No groups configured.</div>'
        });
    }

    async _saveUser(userId, button) {
        if (!userId) return;
        const role = this.refs.usersTable.shadowRoot.querySelector(`[data-user-role="${userId}"]`)?.value;
        const groupId = this.refs.usersTable.shadowRoot.querySelector(`[data-user-group="${userId}"]`)?.value;
        const status = this.refs.usersTable.shadowRoot.querySelector(`[data-user-status="${userId}"]`)?.value;
        this._hideNotice();
        button.disabled = true;
        const originalText = button.textContent;
        button.textContent = 'Saving…';
        try {
            await putJson(`${API.account}/admin/users/${userId}`, { role, groupId, status });
            await this.refresh();
        } catch (e) {
            this._showNotice(e.message);
            button.disabled = false;
            button.textContent = originalText;
        }
    }

    _option(value, label, selectedValue) {
        return `<option value="${escapeHtml(value)}" ${value === selectedValue ? 'selected' : ''}>${escapeHtml(label)}</option>`;
    }

    _showNotice(message) {
        this.refs.panelNotice.textContent = message;
        this.refs.panelNotice.classList.add('show');
    }

    _hideNotice() {
        this.refs.panelNotice.textContent = '';
        this.refs.panelNotice.classList.remove('show');
    }
}

customElements.define('ai-panel-users', PanelUsers);
