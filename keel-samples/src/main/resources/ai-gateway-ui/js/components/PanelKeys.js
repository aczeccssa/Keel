import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson, deleteJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

export class PanelKeys extends KeelElement {
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
                    border: 1px solid rgba(17, 24, 39, 0.04);
                }
                .section-title {
                    font-family: var(--font-headline);
                    font-size: 20px;
                    font-weight: 500;
                    margin: 0 0 20px;
                    color: var(--ink);
                }
                .btn-primary {
                    padding: 12px 24px;
                    border: 0;
                    border-radius: 999px;
                    background: var(--navy);
                    color: #f8fafc;
                    font-size: 11px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    cursor: pointer;
                    transition: background 200ms ease;
                }
                .btn-primary:hover { background: var(--navy-2); }
                .btn-ghost {
                    padding: 10px 18px;
                    border: 1px solid var(--line-strong);
                    border-radius: 999px;
                    background: transparent;
                    color: var(--ink);
                    font-size: 11px;
                    font-weight: 700;
                    letter-spacing: 0.08em;
                    text-transform: uppercase;
                    cursor: pointer;
                    transition: all 150ms ease;
                }
                .btn-ghost:hover { background: var(--panel-strong); }
                .btn-danger {
                    padding: 6px 14px;
                    border: 1px solid var(--red);
                    border-radius: 999px;
                    background: transparent;
                    color: var(--red);
                    font-size: 10px;
                    font-weight: 700;
                    letter-spacing: 0.08em;
                    text-transform: uppercase;
                    cursor: pointer;
                }
                .btn-danger:hover { background: var(--red-soft); }
                .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 24px; }
                .field label {
                    display: block;
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                    color: var(--muted);
                    margin-bottom: 8px;
                }
                .field input {
                    width: 100%;
                    padding: 12px 14px;
                    border: 0;
                    border-radius: var(--radius-sm);
                    font-size: 14px;
                    background: var(--color-surface-container-high, #e4e2dc);
                    color: var(--ink);
                    transition: all 150ms ease;
                }
                .field input:focus {
                    outline: none;
                    box-shadow: 0 2px 0 0 var(--teal);
                    background: var(--color-surface-container-lowest, #fff);
                }
                .raw-key-display {
                    background: rgba(15, 118, 110, 0.06);
                    border-radius: var(--radius-sm);
                    padding: 16px 20px;
                    font-family: var(--font-mono);
                    font-size: 13px;
                    word-break: break-all;
                    color: var(--teal);
                    margin-bottom: 20px;
                    line-height: 1.6;
                }
                .form-actions { display: flex; gap: 12px; }
            </style>
            <div class="panel-layout">
                <div class="toolbar">
                    <keel-hero data-ref="hero"></keel-hero>
                    <button class="btn-primary" data-ref="createBtn">Create API Key</button>
                </div>
                <div class="section-card" data-ref="createForm" hidden>
                    <h3 class="section-title">Create New API Key</h3>
                    <div class="form-grid">
                        <div class="field"><label>Display Name</label><input data-ref="fName" placeholder="My API Key"></div>
                        <div class="field"><label>Max Budget (USD)</label><input data-ref="fBudget" type="number" value="10"></div>
                        <div class="field"><label>RPM Limit</label><input data-ref="fRpm" type="number" placeholder="120"></div>
                        <div class="field"><label>TPM Limit</label><input data-ref="fTpm" type="number" placeholder="120000"></div>
                    </div>
                    <div class="raw-key-display" data-ref="rawKeyDisplay" hidden></div>
                    <div class="form-actions">
                        <button class="btn-primary" data-ref="submitBtn">Create</button>
                        <button class="btn-ghost" data-ref="cancelBtn">Cancel</button>
                    </div>
                </div>
                <div class="section-card">
                    <h3 class="section-title">Your API Keys</h3>
                    <keel-data-table data-ref="table"></keel-data-table>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label: 'Token Management', title: 'API Keys', metaHtml: '' });
        this.refs.createBtn.addEventListener('click', () => {
            this.refs.createForm.hidden = false;
            this.refs.rawKeyDisplay.hidden = true;
        });
        this.refs.cancelBtn.addEventListener('click', () => { this.refs.createForm.hidden = true; });
        this.refs.submitBtn.addEventListener('click', async () => {
            try {
                const data = await postJson(`${API.token}/v1/keys`, {
                    displayName: this.refs.fName.value || 'API Key',
                    maxBudgetUsd: parseFloat(this.refs.fBudget.value) || 10,
                    rpmLimit: parseInt(this.refs.fRpm.value) || null,
                    tpmLimit: parseInt(this.refs.fTpm.value) || null,
                });
                this.refs.rawKeyDisplay.textContent = `Your new API key (copy now — it won't be shown again): ${data.rawKey}`;
                this.refs.rawKeyDisplay.hidden = false;
                this.refresh();
            } catch (e) { alert(e.message); }
        });
    }

    async refresh() {
        try {
            const data = await requestJson(`${API.token}/admin/keys`);
            this._render(data.keys || []);
        } catch (e) {
            this.refs.table.render({ headers: [], rows: [], emptyHtml: `<div style="text-align:center;color:var(--muted);padding:40px;">Failed to load: ${e.message}</div>` });
        }
    }

    _render(keys) {
        this.refs.hero.render({
            label: 'Token Management',
            title: 'API Keys',
            metaHtml: `<span style="font-size:11px;font-weight:700;color:var(--muted);">${keys.length} key${keys.length !== 1 ? 's' : ''}</span>`
        });

        this.refs.table.render({
            headers: ['Key ID', 'Name', 'User', 'Status', 'Budget', 'Spent', 'Remaining', 'Action'],
            rows: keys.map(k => [
                `<code style="font-size:11px;">${k.keyId}</code>`,
                escapeHtml(k.displayName || ''),
                `<code style="font-size:11px;">${k.userId}</code>`,
                k.status === 'active'
                    ? '<span style="color:var(--green);font-weight:700;font-size:10px;text-transform:uppercase;letter-spacing:0.08em;">Active</span>'
                    : '<span style="color:var(--red);font-weight:700;font-size:10px;text-transform:uppercase;letter-spacing:0.08em;">Revoked</span>',
                `$${(k.maxBudgetUsd || 0).toFixed(2)}`,
                `$${(k.currentSpendUsd || 0).toFixed(4)}`,
                `$${(k.remainingBudgetUsd || 0).toFixed(4)}`,
                k.status === 'active'
                    ? `<button class="btn-danger" data-revoke="${k.keyId}">Revoke</button>`
                    : ''
            ]),
            emptyHtml: '<div style="text-align:center;color:var(--muted);padding:40px;">No API keys yet. Create one above to get started.</div>'
        });

        this.refs.table.shadowRoot.querySelectorAll('[data-revoke]').forEach(btn => {
            btn.addEventListener('click', async () => {
                if (!confirm('Revoke this key? This cannot be undone.')) return;
                try { await deleteJson(`${API.token}/v1/keys/${btn.dataset.revoke}`); this.refresh(); }
                catch (e) { alert(e.message); }
            });
        });
    }
}

customElements.define('ai-panel-keys', PanelKeys);
