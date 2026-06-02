import { KeelElement } from '/js/components/base/KeelElement.js';
import { requestJson, postJson, deleteJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '/js/utils.js';

export class PanelRateLimits extends KeelElement {
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
                    border: 1px solid rgba(17, 24, 39, 0.04);
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
                    padding: 10px 20px; border: 0; border-radius: 999px;
                    background: var(--navy); color: #f8fafc; font-size: 11px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; cursor: pointer; transition: background 200ms ease;
                }
                .btn-primary:hover { background: var(--navy-2); }
                .btn-ghost {
                    padding: 8px 16px; border: 1px solid var(--line-strong); border-radius: 999px;
                    background: transparent; color: var(--ink); font-size: 10px; font-weight: 700;
                    letter-spacing: 0.08em; text-transform: uppercase; cursor: pointer; transition: all 150ms ease;
                }
                .btn-ghost:hover { background: var(--panel-strong); }
                .btn-danger-ghost {
                    padding: 8px 16px; border: 1px solid var(--red); border-radius: 999px;
                    background: transparent; color: var(--red); font-size: 10px; font-weight: 700;
                    letter-spacing: 0.08em; text-transform: uppercase; cursor: pointer;
                }
                .btn-danger-ghost:hover { background: var(--red-soft); }
                .btn-sm {
                    padding: 5px 12px; border: 1px solid var(--red); border-radius: 999px;
                    background: transparent; color: var(--red); font-size: 9px; font-weight: 700;
                    text-transform: uppercase; letter-spacing: 0.06em; cursor: pointer;
                }
                .form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 20px; }
                .field label {
                    display: block; font-size: 10px; font-weight: 800; text-transform: uppercase;
                    letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px;
                }
                .field input, .field select {
                    width: 100%; padding: 10px 12px; border: 0; border-radius: var(--radius-sm);
                    font-size: 13px; background: var(--color-surface-container-high, #e4e2dc); color: var(--ink);
                    transition: all 150ms ease;
                }
                .field input:focus, .field select:focus {
                    outline: none; box-shadow: 0 2px 0 0 var(--teal); background: var(--color-surface-container-lowest, #fff);
                }
                .form-actions { display: flex; gap: 12px; }
                .summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
                .summary-item {
                    background: var(--color-surface-container-lowest, #fff);
                    border-radius: var(--radius-sm);
                    padding: 18px 20px;
                    border: 1px solid rgba(17, 24, 39, 0.04);
                }
                .summary-label {
                    font-size: 10px; font-weight: 800; text-transform: uppercase;
                    letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px;
                }
                .summary-val {
                    font-family: var(--font-headline); font-size: 28px; line-height: 0.95;
                    letter-spacing: -0.04em; color: var(--ink);
                }
                .empty { text-align: center; color: var(--muted); padding: 40px; font-size: 13px; }
            </style>
            <div class="panel-layout">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="summary-grid" data-ref="summary"></div>
                <div class="section-card">
                    <div class="toolbar">
                        <h3 class="section-title" style="margin:0;">Rules</h3>
                        <div style="display:flex;gap:10px;">
                            <button class="btn-primary" data-ref="addRuleBtn">Add Rule</button>
                            <button class="btn-danger-ghost" data-ref="resetAllBtn">Reset All Buckets</button>
                        </div>
                    </div>
                    <div class="section-card" data-ref="ruleForm" hidden style="background:var(--color-surface-container-lowest,#fff);margin-bottom:20px;">
                        <h3 class="section-title">New Rule</h3>
                        <div class="form-grid">
                            <div class="field"><label>Name</label><input data-ref="fName" placeholder="Rule name"></div>
                            <div class="field"><label>Dimension</label>
                                <select data-ref="fDimension"><option>IP</option><option>USER</option><option>API_KEY</option><option>MODEL</option><option>GLOBAL</option></select>
                            </div>
                            <div class="field"><label>Path Pattern</label><input data-ref="fPath" value="/v1/*"></div>
                            <div class="field"><label>Capacity</label><input data-ref="fCapacity" type="number" value="120"></div>
                            <div class="field"><label>Refill Rate / sec</label><input data-ref="fRate" type="number" step="0.1" value="2"></div>
                            <div class="field"><label>Priority</label><input data-ref="fPriority" type="number" value="0"></div>
                        </div>
                        <div class="form-actions">
                            <button class="btn-primary" data-ref="submitRuleBtn">Create Rule</button>
                            <button class="btn-ghost" data-ref="cancelRuleBtn">Cancel</button>
                        </div>
                    </div>
                    <keel-data-table data-ref="rulesTable"></keel-data-table>
                </div>
                <div class="section-card">
                    <h3 class="section-title">Active Buckets (Top 20)</h3>
                    <keel-data-table data-ref="bucketsTable"></keel-data-table>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label: 'Traffic Control', title: 'Rate Limits', metaHtml: '' });
        this.refs.addRuleBtn.addEventListener('click', () => { this.refs.ruleForm.hidden = false; });
        this.refs.cancelRuleBtn.addEventListener('click', () => { this.refs.ruleForm.hidden = true; });
        this.refs.submitRuleBtn.addEventListener('click', async () => {
            try {
                await postJson(`${API.riskcontrol}/v1/rules`, {
                    name: this.refs.fName.value || 'Rule',
                    dimension: this.refs.fDimension.value,
                    pathPattern: this.refs.fPath.value || '/v1/*',
                    capacity: parseInt(this.refs.fCapacity.value) || 120,
                    refillRatePerSec: parseFloat(this.refs.fRate.value) || 2,
                    priority: parseInt(this.refs.fPriority.value) || 0,
                });
                this.refs.ruleForm.hidden = true;
                this.refresh();
            } catch (e) { alert(e.message); }
        });
        this.refs.resetAllBtn.addEventListener('click', async () => {
            if (!confirm('Reset all rate limit buckets?')) return;
            try { await postJson(`${API.riskcontrol}/v1/reset`, {}); this.refresh(); }
            catch (e) { alert(e.message); }
        });
    }

    async refresh() {
        try {
            const [rules, snap] = await Promise.all([
                requestJson(`${API.riskcontrol}/v1/rules`),
                requestJson(`${API.riskcontrol}/v1/snapshot`),
            ]);
            this._render(rules.rules || [], snap);
        } catch (e) {
            this.refs.rulesTable.render({ headers: [], rows: [], emptyHtml: `<div class="empty">Failed: ${e.message}</div>` });
        }
    }

    _render(rules, snap) {
        this.refs.summary.innerHTML = [
            ['Rules', snap.ruleCount || 0],
            ['Active Buckets', snap.bucketCount || 0],
            ['Total Allowed', (snap.totalAllowed || 0).toLocaleString()],
            ['Total Rejected', (snap.totalRejected || 0).toLocaleString()],
        ].map(([label, val]) => `
            <div class="summary-item">
                <div class="summary-label">${label}</div>
                <div class="summary-val">${val}</div>
            </div>
        `).join('');

        this.refs.rulesTable.render({
            headers: ['Name', 'Dimension', 'Path', 'Capacity', 'Refill/s', 'Priority', 'Enabled', 'Action'],
            rows: rules.map(r => [
                escapeHtml(r.name || r.ruleId),
                `<code>${r.dimension}</code>`,
                `<code>${r.pathPattern}</code>`,
                String(r.capacity),
                String(r.refillRatePerSec),
                String(r.priority),
                r.enabled
                    ? '<span style="color:var(--green);font-weight:700;font-size:10px;text-transform:uppercase;">ON</span>'
                    : '<span style="color:var(--red);font-weight:700;font-size:10px;text-transform:uppercase;">OFF</span>',
                `<button class="btn-sm" data-del-rule="${r.ruleId}">Delete</button>`
            ]),
            emptyHtml: '<div class="empty">No rules configured.</div>'
        });

        this.refs.rulesTable.shadowRoot.querySelectorAll('[data-del-rule]').forEach(btn => {
            btn.addEventListener('click', async () => {
                if (!confirm('Delete this rule?')) return;
                try { await deleteJson(`${API.riskcontrol}/v1/rules/${btn.dataset.delRule}`); this.refresh(); }
                catch (e) { alert(e.message); }
            });
        });

        const buckets = snap.topBuckets || [];
        this.refs.bucketsTable.render({
            headers: ['Rule', 'Dimension', 'Value', 'Allowed', 'Rejected', 'Remaining', 'Capacity'],
            rows: buckets.map(b => [
                `<code>${b.ruleId}</code>`,
                b.dimension,
                `<code>${b.value}</code>`,
                String(b.totalAllowed || 0),
                String(b.totalRejected || 0),
                String(b.remainingTokens ?? '-'),
                String(b.capacity ?? '-')
            ]),
            emptyHtml: '<div class="empty">No active buckets.</div>'
        });
    }
}

customElements.define('ai-panel-rate-limits', PanelRateLimits);
