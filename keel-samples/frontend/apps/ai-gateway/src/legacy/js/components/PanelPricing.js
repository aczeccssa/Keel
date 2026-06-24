import { KeelElement } from './base/KeelElement.js';
import { requestJson, putJson, deleteJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml, formatNumber } from '../utils.js';

/**
 * Pricing management — list, create, edit, delete per-model rate cards.
 */
export class PanelPricing extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .layout { display: flex; flex-direction: column; gap: 24px; height: 100%; }
                .toolbar { display: flex; align-items: center; gap: 12px; }
                .toolbar .spacer { flex: 1; }
                .btn { padding: 11px 18px; border: 2px solid var(--ink); background: var(--ink); color: var(--paper); font-family: var(--font-mono); font-size: 11px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.12em; cursor: pointer; }
                .btn:hover { background: var(--teal); border-color: var(--teal); }
                .btn-ghost { padding: 8px 12px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; cursor: pointer; }
                .btn-ghost:hover { background: var(--ink); color: var(--paper); }
                .btn-danger { padding: 7px 11px; border: 2px solid var(--red); background: transparent; color: var(--red); font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; cursor: pointer; }
                .btn-danger:hover { background: var(--red); color: var(--paper); }
                .table-card { background: var(--panel-strong); border: 2px solid var(--ink); overflow: hidden; flex: 1; }
                .table-wrap { max-height: 70vh; overflow: auto; }
                .empty { text-align: center; color: var(--muted); padding: 40px; font-family: var(--font-mono); font-size: 12px; }
                .tier-list { display:grid; gap:4px; max-width:540px; }
                .tier-line { font-family:var(--font-mono); font-size:10px; line-height:1.45; }
                .overlay { position: fixed; inset: 0; background: rgba(11,11,11,0.55); display: none; align-items: center; justify-content: center; z-index: 900; }
                .overlay.open { display: flex; }
                .modal { width: min(640px, 92vw); max-height: 90vh; overflow-y: auto; border: 2px solid var(--ink); background: var(--paper); box-shadow: var(--shadow-lg); }
                .modal-head { display: flex; justify-content: space-between; padding: 14px 18px; background: var(--ink); color: var(--paper); font-family: var(--font-headline); font-size: 16px; text-transform: uppercase; letter-spacing: -0.04em; }
                .modal-body { padding: 18px; display: grid; gap: 14px; }
                .field label { display: block; margin-bottom: 6px; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted); }
                .field input, .field textarea { width: 100%; padding: 10px 12px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); font-family: var(--font-mono); font-size: 13px; }
                .field input:focus, .field textarea:focus { outline: none; box-shadow: var(--shadow-sm); }
                .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
                .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; }
                .switch-row { display:flex; gap:16px; flex-wrap:wrap; font-family:var(--font-mono); font-size:11px; font-weight:800; text-transform:uppercase; }
                .error { display: none; padding: 8px 14px; background: var(--red); color: var(--paper); font-family: var(--font-mono); font-size: 10px; font-weight: 800; }
                .error.show { display: block; }
            </style>
            <div class="layout" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="toolbar">
                    <span class="spacer" style="font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.1em;color:var(--muted);text-transform:uppercase;" data-ref="meta"></span>
                    <button class="btn" data-ref="addBtn">+ Add Pricing</button>
                </div>
                <div class="table-card"><div class="table-wrap" data-ref="tableWrap"></div></div>
            </div>
            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span data-ref="modalTitle">Add Pricing</span><button class="btn-ghost" style="color:var(--paper);border-color:var(--paper);" data-ref="closeBtn">&#10005;</button></div>
                    <div class="error" data-ref="error"></div>
                    <div class="modal-body">
                        <div class="field"><label>Model Name</label><input data-ref="fModel" placeholder="claude-sonnet-4-20250514" /></div>
                        <div class="grid-3">
                            <div class="field"><label>Variant Key</label><input data-ref="fVariant" placeholder="claude-std or claude-1m" /></div>
                            <div class="field"><label>Label (opt)</label><input data-ref="fLabel" placeholder="Claude Std" /></div>
                            <div class="field"><label>Billing Unit</label><input data-ref="fUnit" type="number" step="1000" placeholder="1000000" value="1000000" /></div>
                        </div>
                        <div class="switch-row">
                            <label><input type="checkbox" data-ref="fUse1M" checked /> Use 1M token unit</label>
                            <label><input type="checkbox" data-ref="fTiered" /> Enable tiered pricing</label>
                        </div>
                        <div class="grid-2">
                            <div class="field"><label>Input $/unit</label><input data-ref="fInput" type="number" step="0.0001" placeholder="3.00" /></div>
                            <div class="field"><label>Output $/unit</label><input data-ref="fOutput" type="number" step="0.0001" placeholder="15.00" /></div>
                        </div>
                        <div class="grid-2">
                            <div class="field"><label>Cache Creation $/unit (opt)</label><input data-ref="fCacheW" type="number" step="0.0001" placeholder="3.75" /></div>
                            <div class="field"><label>Cache Read $/unit (opt)</label><input data-ref="fCacheR" type="number" step="0.0001" placeholder="0.30" /></div>
                        </div>
                        <div class="grid-2">
                            <div class="field"><label>Reasoning $/unit (opt)</label><input data-ref="fReason" type="number" step="0.0001" placeholder="" /></div>
                            <div class="field"><label>Credit Multiplier (opt)</label><input data-ref="fCredit" type="number" step="0.1" min="0" placeholder="1.0" /></div>
                        </div>
                        <div class="field"><label>Notes (opt)</label><input data-ref="fNotes" placeholder="Public Anthropic pricing" /></div>
                        <div class="field" data-ref="tiersField">
                            <label>Tiers</label>
                            <textarea data-ref="fTiers" rows="5" placeholder="Format: start-end|unit|input|output|cacheWrite|cacheRead|reasoning&#10;0-200000|200000|0.80|4.00|1.00|0.08|4.00&#10;200000-*|1000000|3.00|15.00|3.75|0.30|15.00"></textarea>
                        </div>
                        <button class="btn" data-ref="saveBtn">Save</button>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._pricings = [];
        this._hasRendered = false;
        this.refs.hero.render({ label: 'Model Pricing', title: 'Pricing', metaHtml: '' });
        this.refs.addBtn.addEventListener('click', () => this._openModal(null));
        this.refs.closeBtn.addEventListener('click', () => this._closeModal());
        this.refs.overlay.addEventListener('click', (e) => { if (e.target === this.refs.overlay) this._closeModal(); });
        this.refs.saveBtn.addEventListener('click', () => this._save());
        this.refs.fUse1M.addEventListener('change', () => { if (this.refs.fUse1M.checked) this.refs.fUnit.value = 1_000_000; });
        this.refs.fTiered.addEventListener('change', () => this._syncTierVisibility());
    }

    async refresh() {
        try {
            const data = await requestJson(`${API.airelay}/admin/pricing`);
            this._pricings = data.pricings || [];
            this.refs.hero.render({
                label: 'Model Pricing',
                title: 'Pricing',
                metaHtml: `<div style="padding:16px 22px;font-family:var(--font-headline);font-size:48px;line-height:0.8;letter-spacing:-0.05em;color:var(--paper);">${this._pricings.length}</div>`
            });
            this._renderTable(this._hasRendered);
            this._hasRendered = true;
            this.refs.meta.textContent = `${this._pricings.length} RATE CARD${this._pricings.length === 1 ? '' : 'S'} CONFIGURED`;
        } catch (e) {
            this.refs.tableWrap.innerHTML = `<div class="empty">Error: ${escapeHtml(e.message)}</div>`;
        }
    }

    _renderTable(silent) {
        if (this._pricings.length === 0) {
            this.refs.tableWrap.innerHTML = '<div class="empty">// NO PRICING CONFIGURED — click "Add Pricing"</div>';
            return;
        }
        const headers = ['Model', 'Variant', 'Unit', 'Credit ×', 'Rates / Tiers', 'Notes', 'Actions'];
        const rows = this._pricings.map(p => [
            `<code>${escapeHtml(p.model)}</code>`,
            p.variantKey ? `<code>${escapeHtml(p.variantKey)}</code>` : '—',
            `${formatNumber(p.billingUnitTokens || 1_000_000)}`,
            p.creditMultiplier != null ? `×${escapeHtml(String(p.creditMultiplier))}` : '—',
            this._rateHtml(p),
            p.notes ? escapeHtml(p.notes) : '',
            `<button class="btn-ghost" data-edit="${escapeHtml(p.pricingId)}">Edit</button> <button class="btn-danger" data-del="${escapeHtml(p.pricingId)}">Del</button>`,
        ]);
        let table = this.refs.tableWrap.querySelector('keel-data-table');
        if (!table) { table = document.createElement('keel-data-table'); this.refs.tableWrap.innerHTML = ''; this.refs.tableWrap.appendChild(table); }
        table.render({ silent, headers, rows, emptyHtml: '<div class="empty">// NO PRICING</div>' });
        const actionRoot = table.shadowRoot || table;
        actionRoot.classList.style.display = 'flex'; // Not wrap allowed
        actionRoot.querySelectorAll('[data-edit]').forEach(b => b.addEventListener('click', () => this._openModal(b.dataset.edit)));
        actionRoot.querySelectorAll('[data-del]').forEach(b => b.addEventListener('click', () => this._delete(b.dataset.del)));
    }

    _rateHtml(p) {
        if (p.tiers?.length) {
            return `<div class="tier-list">${p.tiers.map(t => {
                const end = t.endTokensExclusive == null ? '∞' : formatNumber(t.endTokensExclusive);
                const cache = t.cacheCreationCostPerUnit != null || t.cacheReadCostPerUnit != null ? ` · cache W/R $${this._fmt(t.cacheCreationCostPerUnit)}/$${this._fmt(t.cacheReadCostPerUnit)}` : '';
                const reason = t.reasoningOutputCostPerUnit != null ? ` · reason $${this._fmt(t.reasoningOutputCostPerUnit)}` : '';
                return `<div class="tier-line"><code>${formatNumber(t.startTokensInclusive)}-${end}</code> unit ${formatNumber(t.billingUnitTokens)} · in/out $${this._fmt(t.inputCostPerUnit)}/$${this._fmt(t.outputCostPerUnit)}${cache}${reason}</div>`;
            }).join('')}</div>`;
        }
        return `<div class="tier-line">flat · in/out $${this._fmt(p.inputCostPerMTok)}/$${this._fmt(p.outputCostPerMTok)}${p.cacheCreationCostPerMTok != null || p.cacheReadCostPerMTok != null ? ` · cache W/R $${this._fmt(p.cacheCreationCostPerMTok)}/$${this._fmt(p.cacheReadCostPerMTok)}` : ''}${p.reasoningOutputCostPerMTok != null ? ` · reason $${this._fmt(p.reasoningOutputCostPerMTok)}` : ''}</div>`;
    }

    _fmt(value) { return Number(value ?? 0).toFixed(4); }

    _openModal(pricingId) {
        const p = pricingId ? this._pricings.find(x => x.pricingId === pricingId) : null;
        this.refs.modalTitle.textContent = p ? 'Edit Pricing' : 'Add Pricing';
        this.refs.fModel.value = p?.model || '';
        this.refs.fModel.disabled = !!p;
        this.refs.fVariant.value = p?.variantKey || '';
        this.refs.fLabel.value = p?.label || '';
        this.refs.fUnit.value = p?.billingUnitTokens || 1_000_000;
        this.refs.fUse1M.checked = Number(this.refs.fUnit.value) === 1_000_000;
        this.refs.fInput.value = p?.inputCostPerMTok ?? '';
        this.refs.fOutput.value = p?.outputCostPerMTok ?? '';
        this.refs.fCacheW.value = p?.cacheCreationCostPerMTok ?? '';
        this.refs.fCacheR.value = p?.cacheReadCostPerMTok ?? '';
        this.refs.fReason.value = p?.reasoningOutputCostPerMTok ?? '';
        this.refs.fCredit.value = p?.creditMultiplier ?? '';
        this.refs.fNotes.value = p?.notes ?? '';
        this.refs.fTiered.checked = Boolean(p?.tiers?.length);
        this.refs.fTiers.value = (p?.tiers || []).map(t => `${t.startTokensInclusive}-${t.endTokensExclusive == null ? '*' : t.endTokensExclusive}|${t.billingUnitTokens}|${t.inputCostPerUnit}|${t.outputCostPerUnit}|${t.cacheCreationCostPerUnit ?? ''}|${t.cacheReadCostPerUnit ?? ''}|${t.reasoningOutputCostPerUnit ?? ''}`).join('\n');
        this._syncTierVisibility();
        this.refs.error.classList.remove('show');
        this.refs.overlay.classList.add('open');
    }

    _syncTierVisibility() { this.refs.tiersField.style.display = this.refs.fTiered.checked ? 'block' : 'none'; }
    _closeModal() { this.refs.overlay.classList.remove('open'); }

    async _save() {
        const model = this.refs.fModel.value.trim();
        if (!model) { this._showError('Model name is required'); return; }
        const billingUnitTokens = parseInt(this.refs.fUnit.value, 10) || 1_000_000;
        const tiers = this.refs.fTiered.checked ? this.refs.fTiers.value.split('\n').map(line => line.trim()).filter(Boolean).map(line => {
            const [range, unit, input, output, cacheWrite, cacheRead, reasoning] = line.split('|').map(v => v.trim());
            const [startText, endText] = range.split('-').map(v => v.trim());
            return {
                startTokensInclusive: parseInt(startText, 10) || 0,
                endTokensExclusive: !endText || endText === '*' ? null : parseInt(endText, 10),
                billingUnitTokens: parseInt(unit, 10) || billingUnitTokens,
                inputCostPerUnit: parseFloat(input) || 0,
                outputCostPerUnit: parseFloat(output) || 0,
                cacheCreationCostPerUnit: cacheWrite ? parseFloat(cacheWrite) : null,
                cacheReadCostPerUnit: cacheRead ? parseFloat(cacheRead) : null,
                reasoningOutputCostPerUnit: reasoning ? parseFloat(reasoning) : null,
            };
        }) : [];
        const body = {
            model,
            variantKey: this.refs.fVariant.value.trim() || null,
            label: this.refs.fLabel.value.trim() || null,
            billingUnitTokens,
            tiers,
            inputCostPerMTok: parseFloat(this.refs.fInput.value) || 0,
            outputCostPerMTok: parseFloat(this.refs.fOutput.value) || 0,
            cacheCreationCostPerMTok: this.refs.fCacheW.value ? parseFloat(this.refs.fCacheW.value) : null,
            cacheReadCostPerMTok: this.refs.fCacheR.value ? parseFloat(this.refs.fCacheR.value) : null,
            reasoningOutputCostPerMTok: this.refs.fReason.value ? parseFloat(this.refs.fReason.value) : null,
            creditMultiplier: this.refs.fCredit.value ? parseFloat(this.refs.fCredit.value) : null,
            notes: this.refs.fNotes.value || null,
        };
        this.refs.saveBtn.disabled = true;
        try { await putJson(`${API.airelay}/admin/pricing`, body); this._closeModal(); this.refresh(); }
        catch (e) { this._showError(e.message); }
        this.refs.saveBtn.disabled = false;
    }

    async _delete(pricingId) {
        const target = this._pricings.find(p => p.pricingId === pricingId);
        if (!confirm(`Delete pricing for "${target?.model || pricingId}"${target?.variantKey ? ` (${target.variantKey})` : ''}?`)) return;
        try {
            const variant = target?.variantKey ? `?variantKey=${encodeURIComponent(target.variantKey)}` : '';
            await deleteJson(`${API.airelay}/admin/pricing/${encodeURIComponent(pricingId)}${variant}`);
            this.refresh();
        } catch (e) { alert('Error: ' + e.message); }
    }

    _showError(msg) { this.refs.error.textContent = msg; this.refs.error.classList.add('show'); }
}

customElements.define('ai-panel-pricing', PanelPricing);
