import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

export class PanelUsers extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .panel-layout { display: flex; flex-direction: column; gap: 28px; }
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
                <div class="section-card">
                    <h3 class="section-title">Users</h3>
                    <keel-data-table data-ref="usersTable"></keel-data-table>
                </div>
                <div class="section-card">
                    <div class="toolbar">
                        <h3 class="section-title" style="margin:0;">User Groups</h3>
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
        this.refs.hero.render({ label: 'Access Control', title: 'Users & Groups', metaHtml: '' });
        this.refs.addGroupBtn.addEventListener('click', () => { this.refs.groupForm.hidden = false; });
        this.refs.cancelGroupBtn.addEventListener('click', () => { this.refs.groupForm.hidden = true; });
        this.refs.submitGroupBtn.addEventListener('click', async () => {
            try {
                await postJson(`${API.account}/admin/groups`, {
                    groupId: this.refs.gId.value,
                    name: this.refs.gName.value,
                    costMultiplier: parseFloat(this.refs.gMultiplier.value) || 1.0,
                    defaultBudgetUsd: parseFloat(this.refs.gBudget.value) || 10,
                });
                this.refs.groupForm.hidden = true;
                this.refresh();
            } catch (e) { alert(e.message); }
        });
    }

    async refresh() {
        try {
            const [users, groups] = await Promise.all([
                requestJson(`${API.account}/admin/users`),
                requestJson(`${API.account}/admin/groups`),
            ]);
            this._renderUsers(users.users || []);
            this._renderGroups(groups.groups || []);
        } catch (e) {
            this.refs.usersTable.render({ headers: [], rows: [], emptyHtml: `<div class="empty">Failed: ${e.message}</div>` });
        }
    }

    _renderUsers(users) {
        this.refs.usersTable.render({
            headers: ['User ID', 'Email', 'Display Name', 'Role', 'Group', 'Status', 'Action'],
            rows: users.map(u => [
                `<code style="font-size:11px;">${u.userId}</code>`,
                escapeHtml(u.email),
                escapeHtml(u.displayName),
                u.role === 'admin'
                    ? '<span style="color:var(--navy);font-weight:700;font-size:10px;text-transform:uppercase;">Admin</span>'
                    : '<span style="color:var(--muted);font-weight:700;font-size:10px;text-transform:uppercase;">User</span>',
                u.groupId,
                u.status === 'active'
                    ? '<span style="color:var(--green);font-weight:700;font-size:10px;text-transform:uppercase;">Active</span>'
                    : '<span style="color:var(--red);font-weight:700;font-size:10px;text-transform:uppercase;">Suspended</span>',
                u.status === 'active'
                    ? `<button class="btn-action danger" data-suspend="${u.userId}">Suspend</button>`
                    : `<button class="btn-action success" data-activate="${u.userId}">Activate</button>`
            ]),
            emptyHtml: '<div class="empty">No users found.</div>'
        });

        this.refs.usersTable.shadowRoot.querySelectorAll('[data-suspend]').forEach(btn => {
            btn.addEventListener('click', async () => {
                try { await postJson(`${API.account}/admin/users/${btn.dataset.suspend}/suspend`, {}); this.refresh(); }
                catch (e) { alert(e.message); }
            });
        });
        this.refs.usersTable.shadowRoot.querySelectorAll('[data-activate]').forEach(btn => {
            btn.addEventListener('click', async () => {
                try { await postJson(`${API.account}/admin/users/${btn.dataset.activate}/activate`, {}); this.refresh(); }
                catch (e) { alert(e.message); }
            });
        });
    }

    _renderGroups(groups) {
        this.refs.groupsTable.render({
            headers: ['Group ID', 'Name', 'Cost Multiplier', 'Default RPM', 'Default TPM', 'Budget'],
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
}

customElements.define('ai-panel-users', PanelUsers);
