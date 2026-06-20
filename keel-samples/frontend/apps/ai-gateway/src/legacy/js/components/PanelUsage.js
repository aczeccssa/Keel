import { KeelElement } from './base/KeelElement.js';
import { requestJson } from '../api.js';
import { API } from '../config.js';
import { state, setTab } from '../state.js';
import { escapeHtml } from '../utils.js';

/**
 * Detail view: per-request records with full token + cost breakdown.
 * Filters are server-side selects (group / channel / model / status).
 */
export class PanelUsage extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .layout { display: flex; flex-direction: column; gap: 24px; height: 100%; }
                .hero-meta { display: grid; justify-items: end; gap: 8px; padding: 4px 0; }
                .hero-chip {
                    display: inline-flex; align-items: center; padding: 7px 10px;
                    border: 1px solid rgba(235, 231, 223, 0.18); background: rgba(11, 11, 11, 0.18);
                    color: var(--on-accent); font-family: var(--font-mono); font-size: 10px; font-weight: 800;
                    letter-spacing: 0.08em; text-transform: uppercase;
                }
                .toolbar { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
                .pager { display: flex; gap: 10px; align-items: center; justify-content: space-between; flex-wrap: wrap; padding: 12px 14px; background: var(--paper); border: 2px solid var(--ink); }
                .pager .range { font-family: var(--font-mono); font-size: 11px; font-weight: 800; color: var(--muted); }
                .pager-actions { display: flex; gap: 8px; align-items: center; }
                .pager button { padding: 8px 12px; border: 2px solid var(--ink); background: var(--panel-strong); color: var(--ink); font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; cursor: pointer; }
                .pager button:disabled { opacity: .38; cursor: not-allowed; }
                .toolbar .field { display: flex; flex-direction: column; gap: 4px; }
                .toolbar label {
                    font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted);
                }
                .toolbar select {
                    padding: 8px 10px; border: 2px solid var(--ink);
                    background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 11px; min-width: 180px;
                }
                .toolbar select:focus { outline: none; box-shadow: var(--shadow-sm); }
                .clear-btn {
                    padding: 8px 14px; border: 2px solid var(--red); background: transparent; color: var(--red);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: .08em;
                    text-transform: uppercase; cursor: pointer;
                }
                .summary {
                    display: grid; grid-template-columns: repeat(7, 1fr); gap: 2px;
                    background: var(--surface-accent); border: 2px solid var(--ink);
                }
                .summary .cell { background: var(--paper); padding: 14px 12px; font-family: var(--font-mono); }
                .summary .label { font-size: 9px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--muted); }
                .summary .val { margin-top: 4px; font-family: var(--font-headline); font-size: 18px; line-height: 1; letter-spacing: -0.03em; font-feature-settings: 'tnum'; }

                .table-card { background: var(--panel-strong); border: 2px solid var(--ink); flex: 1; min-height: 0; overflow: hidden; }
                .table-scroll { overflow: auto; max-height: 70vh; }
                table.usage { border-collapse: collapse; width: max-content; min-width: 100%; font-family: var(--font-mono); font-size: 11px; }
                table.usage thead th {
                    position: sticky; top: 0; z-index: 1; text-align: left; white-space: nowrap;
                    font-size: 10px; font-weight: 800; letter-spacing: .14em; text-transform: uppercase;
                    color: var(--on-accent); background: var(--surface-accent); padding: 11px 12px;
                    border-right: 1px solid rgba(244,244,240,0.28);
                }
                table.usage tbody td {
                    padding: 10px 12px; white-space: nowrap; border-right: 1px solid var(--ink);
                    border-bottom: 1px solid var(--ink); color: var(--ink); vertical-align: middle;
                }
                table.usage tbody td:last-child, table.usage thead th:last-child { border-right: 0; }
                table.usage tbody tr:nth-child(even) td { background: var(--color-surface-container-low, #ebe9e3); }
                table.usage code {
                    font-family: var(--font-mono); font-size: 10.5px; font-weight: 800; letter-spacing: .04em;
                    background: var(--surface-accent); color: var(--on-accent); border: 1px solid var(--surface-accent); padding: 2px 6px;
                }
                .detail-btn { border:1px solid var(--teal); color:var(--teal); background:transparent; padding:6px 10px; font-family:var(--font-mono); font-size:10px; font-weight:800; cursor:pointer; text-transform:uppercase; }
                .drawer-backdrop { position:fixed; inset:0; background:rgba(15,23,42,.34); z-index:950; display:none; justify-content:flex-end; }
                .drawer-backdrop.open { display:flex; }
                .drawer { width:min(560px, 96vw); height:100%; overflow:auto; background:var(--panel-strong); border-left:2px solid var(--ink); box-shadow:-18px 0 40px rgba(15,23,42,.2); padding:26px; }
                .drawer-head { display:flex; justify-content:space-between; align-items:start; gap:16px; margin-bottom:20px; }
                .drawer h3 { font-family:var(--font-headline); font-size:26px; margin:0; }
                .drawer section { margin:20px 0; display:grid; gap:8px; }
                .drawer section h4 { margin:0 0 4px; font-family:var(--font-mono); font-size:11px; letter-spacing:.12em; text-transform:uppercase; color:var(--muted); }
                .kv { display:grid; grid-template-columns:140px 1fr; gap:10px; font-family:var(--font-mono); font-size:12px; }
                .kv .k { color:var(--muted); text-transform:uppercase; font-weight:800; font-size:10px; }
                .kv .v { word-break:break-word; }
                .empty { text-align: center; color: var(--muted); padding: 30px; font-family: var(--font-mono); font-size: 12px; }
                @media (max-width: 1100px) { .summary { grid-template-columns: repeat(3, 1fr); } }
            </style>
            <div class="layout" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="toolbar">
                    <div class="field">
                        <label>Group</label>
                        <select data-ref="groupFilter"><option value="">All groups</option></select>
                    </div>
                    <div class="field">
                        <label>Channel</label>
                        <select data-ref="channelFilter"><option value="">All channels</option></select>
                    </div>
                    <div class="field">
                        <label>Model</label>
                        <select data-ref="modelFilter"><option value="">All models</option></select>
                    </div>
                    <div class="field">
                        <label>Status</label>
                        <select data-ref="statusFilter">
                            <option value="">All status</option>
                            <option value="success">Success</option>
                            <option value="error">Error</option>
                        </select>
                    </div>
                    <div class="field">
                        <label>Page Size</label>
                        <select data-ref="limitFilter">
                            <option selected>50</option>
                            <option>100</option>
                            <option>200</option>
                        </select>
                    </div>
                    <button class="clear-btn" data-ref="clearBtn">Clear</button>
                </div>
                <div class="summary" data-ref="summary"></div>
                <div class="pager">
                    <div class="range" data-ref="pageRange">0 records</div>
                    <div class="pager-actions">
                        <button data-ref="prevBtn" disabled>Prev</button>
                        <button data-ref="nextBtn" disabled>Next</button>
                    </div>
                </div>
                <div class="table-card">
                    <div class="table-scroll" data-ref="tableWrap"></div>
                </div>
                <div class="drawer-backdrop" data-ref="drawerBackdrop">
                    <aside class="drawer" data-ref="drawer"></aside>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._records = [];
        this._channelNameById = {};
        this._groupNameById = {};
        this._offset = 0;
        this._total = 0;
        this._pageSize = 50;
        this._hasRendered = false;
        this._optionsLoaded = false;
        this.refs.hero.render({ label: 'Request Ledger', title: 'Usage', metaHtml: '' });
        const onFilterChange = () => { this._offset = 0; this.refresh(); };
        this.refs.groupFilter.addEventListener('change', onFilterChange);
        this.refs.channelFilter.addEventListener('change', onFilterChange);
        this.refs.modelFilter.addEventListener('change', onFilterChange);
        this.refs.statusFilter.addEventListener('change', onFilterChange);
        this.refs.limitFilter.addEventListener('change', onFilterChange);
        this.refs.prevBtn.addEventListener('click', () => {
            this._offset = Math.max(0, this._offset - this._pageSize);
            this.refresh();
        });
        this.refs.nextBtn.addEventListener('click', () => {
            this._offset = this._offset + this._pageSize;
            this.refresh();
        });
        this.refs.clearBtn.addEventListener('click', () => {
            this.refs.groupFilter.value = '';
            this.refs.channelFilter.value = '';
            this.refs.modelFilter.value = '';
            this.refs.statusFilter.value = '';
            this._offset = 0;
            if (Object.keys(this._contextQuery()).length) {
                setTab('usage');
                return;
            }
            this.refresh();
        });
        this.refs.tableWrap.addEventListener('click', (event) => {
            const btn = event.target.closest('[data-detail]');
            if (!btn) return;
            const record = this._records.find(r => (r.requestId || r.recordId) === btn.dataset.detail);
            if (record) this._showDetail(record);
        });
        this.refs.drawerBackdrop.addEventListener('click', (event) => {
            if (event.target === this.refs.drawerBackdrop) this._closeDetail();
        });
    }

    async _loadFilterOptions() {
        if (this._optionsLoaded) return;
        try {
            const [groups, channels] = await Promise.all([
                requestJson(`${API.airelay}/admin/groups`).catch(() => ({ groups: [] })),
                requestJson(`${API.airelay}/admin/channels`).catch(() => ({ channels: [] })),
            ]);
            const groupList = groups.groups || [];
            const channelList = channels.channels || [];
            this._channelNameById = {};
            channelList.forEach(c => { if (c.channelId) this._channelNameById[c.channelId] = c.name || c.channelId; });
            this._groupNameById = {};
            groupList.forEach(g => { if (g.groupId) this._groupNameById[g.groupId] = g.name || g.groupId; });
            const models = Array.from(new Set(channelList.flatMap(c => (c.models || []).map(m => m.publicModelName)).filter(Boolean))).sort();

            this._fillSelect(this.refs.groupFilter, groupList.map(g => [g.groupId, g.name || g.groupId]));
            this._fillSelect(this.refs.channelFilter, channelList.map(c => [c.channelId, c.name || c.channelId]));
            this._fillSelect(this.refs.modelFilter, models.map(m => [m, m]));
            this._optionsLoaded = true;
        } catch {
            // options are best-effort
        }
    }

    _fillSelect(select, pairs) {
        const current = select.value;
        const first = select.querySelector('option');
        select.innerHTML = '';
        if (first) select.appendChild(first);
        pairs.forEach(([value, label]) => {
            const opt = document.createElement('option');
            opt.value = value;
            opt.textContent = label;
            select.appendChild(opt);
        });
        if (current) select.value = current;
    }

    _buildQuery() {
        this._pageSize = parseInt(this.refs.limitFilter.value) || 50;
        const params = new URLSearchParams({
            limit: String(this._pageSize),
            offset: String(this._offset),
        });
        const group = this.refs.groupFilter.value;
        const channel = this.refs.channelFilter.value;
        const model = this.refs.modelFilter.value;
        const status = this.refs.statusFilter.value;
        const context = this._contextQuery();
        if (group) params.set('routingGroupId', group);
        if (channel) params.set('channelId', channel);
        if (model) params.set('model', model);
        if (status) params.set('statusFilter', status);
        if (context.keyId) params.set('keyId', context.keyId);
        if (context.customerId) params.set('customerId', context.customerId);
        return params;
    }

    _contextQuery() {
        if (state.activeTab !== 'usage') return {};
        const { keyId, customerId } = state.tabQuery || {};
        return {
            ...(keyId ? { keyId } : {}),
            ...(customerId ? { customerId } : {}),
        };
    }

    async refresh() {
        await this._loadFilterOptions();
        try {
            const params = this._buildQuery();
            const data = await requestJson(`${API.token}/admin/usage/records?${params}`);
            this._records = data.records || [];
            this._total = data.total != null ? data.total : this._records.length;
            this._offset = data.offset != null ? data.offset : this._offset;
            this._renderTable(this._hasRendered);
            this._renderPager();
            this._hasRendered = true;
        } catch (e) {
            this.refs.tableWrap.innerHTML = `<div class="empty">Error: ${escapeHtml(e.message)}</div>`;
        }
    }

    _renderPager() {
        const start = this._records.length === 0 ? 0 : this._offset + 1;
        const end = this._offset + this._records.length;
        const context = this._contextQuery();
        const hasFilters = this.refs.groupFilter.value || this.refs.channelFilter.value
            || this.refs.modelFilter.value || this.refs.statusFilter.value
            || context.keyId || context.customerId;
        const suffix = hasFilters ? ` (filtered${this._contextSummary(context)})` : '';
        this.refs.pageRange.textContent = `${start}–${end} of ${this._total} records${suffix}`;
        this.refs.prevBtn.disabled = this._offset <= 0;
        this.refs.nextBtn.disabled = end >= this._total;
    }

    _contextSummary(context = this._contextQuery()) {
        const parts = [];
        if (context.keyId) parts.push(`key ${context.keyId}`);
        if (context.customerId) parts.push(`customer ${context.customerId}`);
        return parts.length ? `: ${parts.join(' · ')}` : '';
    }

    _groupLabel(r) {
        if (r.routingGroupName) return r.routingGroupName;
        const id = r.routingGroupId || r.groupId;
        if (id && this._groupNameById[id]) return this._groupNameById[id];
        return id || r.poolLevelId || 'unknown';
    }

    _channelLabel(r) {
        if (r.channelName) return r.channelName;
        const id = r.channelId || r.upstreamKeyId;
        if (!id) return '—';
        return this._channelNameById[id] || id;
    }

    _renderTable(silent) {
        const filtered = this._records;
        const sums = filtered.reduce((acc, r) => {
            const u = r.usage || {};
            const c = r.cost || {};
            acc.input += u.promptTokens || 0;
            acc.output += u.completionTokens || 0;
            acc.cr += u.cacheReadInputTokens || 0;
            acc.cw += u.cacheCreationInputTokens || 0;
            acc.reason += u.reasoningTokens || 0;
            acc.cost += c.totalCostUsd || 0;
            if (c.cacheHitRate != null) { acc.hitN += 1; acc.hitSum += c.cacheHitRate; }
            return acc;
        }, { input: 0, output: 0, cr: 0, cw: 0, reason: 0, cost: 0, hitN: 0, hitSum: 0 });
        const hit = sums.hitN > 0 ? (sums.hitSum / sums.hitN * 100).toFixed(1) + '%' : '—';
        this.refs.hero.render({
            label: 'Request Ledger',
            title: 'Usage',
            metaHtml: `<div class="hero-meta"><span class="hero-chip">${this._total} records</span><span class="hero-chip">${hit} cache hit</span></div>`
        });
        this.refs.summary.innerHTML = [
            this._sumCell('Records', filtered.length.toString()),
            this._sumCell('Input', sums.input.toLocaleString()),
            this._sumCell('Output', sums.output.toLocaleString()),
            this._sumCell('Cache Read', sums.cr.toLocaleString()),
            this._sumCell('Cache Write', sums.cw.toLocaleString()),
            this._sumCell('Reasoning', sums.reason.toLocaleString()),
            this._sumCell('Total Cost', '$' + sums.cost.toFixed(4)),
        ].join('');

        if (filtered.length === 0) {
            const context = this._contextQuery();
            const hasFilters = this.refs.groupFilter.value || this.refs.channelFilter.value || this.refs.modelFilter.value || this.refs.statusFilter.value
                || context.keyId || context.customerId;
            this.refs.tableWrap.innerHTML = `<div class="empty">${hasFilters ? '// NO RECORDS MATCH FILTERS' : '// NO USAGE RECORDS YET'}</div>`;
            return;
        }

        const headers = ['Model', 'Time', 'Group', 'Channel', 'In', 'Out', 'CR', 'CW', 'CP', 'Reason', 'In$', 'Out$', 'CW$', 'CR$', 'Total$', 'Hit', 'Status', 'Latency', 'Detail'];
        const rows = filtered.map(r => {
            const u = r.usage || {};
            const c = r.cost || {};
            const id = r.requestId || r.recordId || '';
            return `<tr>
                <td>${this._modelCell(r.model)}</td>
                <td>${escapeHtml((r.createdAt || '').replace('T', ' ').slice(0, 19))}</td>
                <td><code>${escapeHtml(this._groupLabel(r))}</code></td>
                <td><code>${escapeHtml(this._channelLabel(r))}</code></td>
                <td>${this._fmt(u.promptTokens)}</td>
                <td>${this._fmt(u.completionTokens)}</td>
                <td>${this._fmt(u.cacheReadInputTokens)}</td>
                <td>${this._fmt(u.cacheCreationInputTokens)}</td>
                <td>${this._fmt(u.cachedPromptTokens)}</td>
                <td>${this._fmt(u.reasoningTokens)}</td>
                <td>$${(c.inputCostUsd || 0).toFixed(5)}</td>
                <td>$${(c.outputCostUsd || 0).toFixed(5)}</td>
                <td>$${(c.cacheWriteCostUsd || 0).toFixed(5)}</td>
                <td>$${(c.cacheReadCostUsd || 0).toFixed(5)}</td>
                <td>$${(c.totalCostUsd || 0).toFixed(5)}</td>
                <td>${c.cacheHitRate != null ? `${(c.cacheHitRate * 100).toFixed(0)}%` : '—'}</td>
                <td>${r.status >= 400 ? `<span style="color:var(--red);font-weight:800;">${r.status}</span>` : `<span style="color:var(--green);font-weight:800;">${r.status || 200}</span>`}</td>
                <td>${r.latencyMs || 0}ms</td>
                <td><button class="detail-btn" data-detail="${escapeHtml(id)}">View</button></td>
            </tr>`;
        }).join('');

        this.refs.tableWrap.innerHTML = `<table class="usage"><thead><tr>${headers.map(h => `<th>${h}</th>`).join('')}</tr></thead><tbody>${rows}</tbody></table>`;
    }

    _showDetail(record) {
        const u = record.usage || {};
        const c = record.cost || {};
        const hit = (record.cacheHitRate != null ? record.cacheHitRate : c.cacheHitRate);
        const kv = (k, v) => `<div class="kv"><span class="k">${k}</span><span class="v">${v == null || v === '' ? '—' : escapeHtml(String(v))}</span></div>`;
        const navButtons = [
            record.keyId ? `<button class="detail-btn" data-filter-key="${escapeHtml(record.keyId)}">Usage For Key</button>` : '',
            record.customerId ? `<button class="detail-btn" data-open-customer="${escapeHtml(record.customerId)}">Open Customer</button>` : '',
        ].filter(Boolean).join('');
        this.refs.drawer.innerHTML = `
            <div class="drawer-head">
                <div>
                    <h3>Request detail</h3>
                    <div style="color:var(--muted);font-family:var(--font-mono);font-size:11px;">Full token, cost, routing and error context.</div>
                </div>
                <div style="display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end;">
                    ${navButtons}
                    <button class="detail-btn" data-ref="drawerClose">Close</button>
                </div>
            </div>
            <section>
                <h4>Basic</h4>
                ${kv('Request ID', record.requestId || record.recordId)}
                ${kv('Timestamp', record.createdAt)}
                ${kv('Model', record.model)}
                ${kv('Routing Group', this._groupLabel(record))}
                ${kv('Channel', this._channelLabel(record))}
                ${kv('Status', `${record.status ?? '—'} (${record.outcome || '—'})`)}
            </section>
            <section>
                <h4>Identity & Routing</h4>
                ${kv('User ID', record.userId)}
                ${kv('User Email', record.userEmail)}
                ${kv('Key ID', record.keyId)}
                ${kv('Key Name', record.keyDisplayName)}
                ${kv('Customer ID', record.customerId)}
                ${kv('Customer Email', record.customerEmail)}
                ${kv('Routing Group ID', record.routingGroupId || record.groupId)}
                ${kv('Routing Group Name', record.routingGroupName || this._groupNameById[record.routingGroupId || record.groupId])}
                ${kv('Pool Level ID', record.poolLevelId)}
                ${kv('Channel ID', record.channelId || record.upstreamKeyId)}
                ${kv('Channel Name', this._channelLabel(record))}
            </section>
            <section>
                <h4>Tokens</h4>
                ${kv('Input', (u.promptTokens || 0).toLocaleString())}
                ${kv('Output', (u.completionTokens || 0).toLocaleString())}
                ${kv('Cache Write', (u.cacheCreationInputTokens || 0).toLocaleString())}
                ${kv('Cache Read', (u.cacheReadInputTokens || 0).toLocaleString())}
                ${kv('Cache Hit Rate', hit != null ? `${(hit * 100).toFixed(1)}%` : '—')}
            </section>
            <section>
                <h4>Cost</h4>
                ${kv('Input Cost', `$${(c.inputCostUsd || 0).toFixed(5)}`)}
                ${kv('Output Cost', `$${(c.outputCostUsd || 0).toFixed(5)}`)}
                ${kv('Cache Write Cost', `$${(c.cacheWriteCostUsd || 0).toFixed(5)}`)}
                ${kv('Cache Read Cost', `$${(c.cacheReadCostUsd || 0).toFixed(5)}`)}
                ${kv('Total Cost', `$${(c.totalCostUsd || record.totalCostUsd || 0).toFixed(5)}`)}
            </section>
            <section>
                <h4>Performance & Errors</h4>
                ${kv('Latency', `${record.latencyMs || 0}ms`)}
                ${kv('Failover', record.failoverCount ?? 0)}
                ${kv('Streamed', record.streamed ? 'yes' : 'no')}
                ${kv('Error Code', record.errorCode)}
                ${kv('Error Detail', record.errorDetail)}
            </section>
        `;
        this.refs.drawer.querySelector('[data-ref="drawerClose"]').addEventListener('click', () => this._closeDetail());
        this.refs.drawer.querySelector('[data-filter-key]')?.addEventListener('click', () => {
            setTab('usage', {
                ...this._contextQuery(),
                keyId: record.keyId,
                customerId: record.customerId || undefined,
            });
            this._closeDetail();
        });
        this.refs.drawer.querySelector('[data-open-customer]')?.addEventListener('click', () => {
            setTab('customers', { customerId: record.customerId });
            this._closeDetail();
        });
        this.refs.drawerBackdrop.classList.add('open');
    }

    _closeDetail() {
        this.refs.drawerBackdrop.classList.remove('open');
    }

    _sumCell(label, val) {
        return `<div class="cell"><div class="label">${label}</div><div class="val">${val}</div></div>`;
    }

    _fmt(n) {
        if (n == null) return '0';
        if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
        return String(n);
    }

    _modelCell(model) {
        const text = String(model || '');
        const parts = text.split(' -> ');
        if (parts.length < 2) return `<code>${escapeHtml(text)}</code>`;
        const alias = parts.shift();
        const real = parts.join(' -> ');
        return `
            <span style="display:inline-flex;align-items:center;gap:6px;white-space:nowrap;">
                <code>${escapeHtml(alias)}</code>
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.65" aria-hidden="true" style="display:inline-block;vertical-align:middle;width:12px;height:12px;min-width:12px;color:var(--muted);max-width:none;">
                    <path d="M2 8h9"></path>
                    <path d="m8 4 4 4-4 4"></path>
                </svg>
                <code>${escapeHtml(real)}</code>
            </span>
        `;
    }
}

customElements.define('ai-panel-usage', PanelUsage);
