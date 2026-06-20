import { KeelElement } from './base/KeelElement.js';
import { requestJson, postJson, deleteJson, putJson } from '../api.js';
import { API } from '../config.js';
import { escapeHtml } from '../utils.js';

const EXPOSURE_LABELS = {
    ALL_MODELS: 'All Models',
    ALIASES_ONLY: 'Aliases Only',
    ALIASES_AND_MODELS: 'Aliases + Models',
    ALIAS_ONLY: 'Aliases Only',
    ALIAS_AND_MODEL_NAMES: 'Aliases + Models',
    MODEL_NAMES_ONLY: 'All Models',
};

/**
 * Groups page = NewAPI-style routing pools. A Group bundles multiple channels into one
 * priority/weight-routed pool that virtual API keys can be restricted to.
 */
export class PanelGroups extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .layout { display: grid; gap: 22px; }
                .toolbar { display: flex; align-items: center; gap: 12px; }
                .toolbar .spacer { flex: 1; }
                .btn-primary, .btn-ghost, .btn-danger {
                    cursor: pointer; font-family: var(--font-mono); font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em;
                }
                .btn-primary { padding: 11px 18px; border: 2px solid var(--ink); background: var(--surface-accent); color: var(--on-accent); font-size: 11px; }
                .btn-primary:hover { background: var(--teal); border-color: var(--teal); }
                .btn-ghost { padding: 8px 12px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); font-size: 10px; }
                .btn-ghost:hover { background: var(--surface-accent); color: var(--on-accent); }
                .btn-danger { padding: 7px 11px; border: 2px solid var(--red); background: transparent; color: var(--red); font-size: 10px; }
                .btn-danger:hover { background: var(--red); color: var(--on-accent); }
                .grid { display: grid; gap: 18px; }
                .group-card { background: var(--panel-strong); border: 2px solid var(--ink); box-shadow: var(--shadow-sm); overflow: hidden; }
                .group-head { display: flex; justify-content: space-between; gap: 16px; flex-wrap: wrap; padding: 18px 18px 16px; background: var(--surface-accent); color: var(--on-accent); }
                .group-title { margin: 0; font-family: var(--font-headline); font-size: 22px; line-height: 1; letter-spacing: -0.04em; text-transform: uppercase; }
                .group-sub { display: block; margin-top: 6px; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.16em; text-transform: uppercase; opacity: 0.7; }
                .group-meta { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
                .tag { display: inline-flex; align-items: center; padding: 4px 9px; border: 1px solid rgba(235, 231, 223, 0.18); font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase; }
                .tag.is-enabled { background: var(--surface-strong); color: var(--ink); border-color: var(--ink); }
                .tag.is-disabled { background: var(--red); color: var(--on-accent); border-color: var(--red); }
                .group-body { padding: 16px 18px; display: grid; gap: 16px; }
                .group-overview { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(320px, 0.9fr); gap: 16px; }
                .info-stack { display: grid; gap: 14px; }
                .detail-card {
                    border: 1px solid var(--ink);
                    background: var(--surface-muted);
                    padding: 12px;
                }
                .detail-card.aliases {
                    background: linear-gradient(180deg, rgba(20, 184, 166, 0.06), transparent 44%), var(--surface-muted);
                }
                .section-label {
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.12em;
                    text-transform: uppercase;
                    color: var(--muted);
                    margin-bottom: 8px;
                }
                .models { display: flex; flex-wrap: wrap; gap: 7px; }
                code { font-family: var(--font-mono); font-size: 10px; font-weight: 800; background: var(--surface-accent); color: var(--on-accent); padding: 2px 6px; text-transform: uppercase; letter-spacing: 0.04em; }
                .priority-block { border: 1px solid var(--ink); }
                .priority-head { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: var(--surface-muted); font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink); }
                .priority-head .badge { background: var(--red); color: var(--on-accent); padding: 2px 8px; }
                table { width: 100%; border-collapse: collapse; font-family: var(--font-mono); font-size: 11px; }
                th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--ink); vertical-align: middle; }
                th { font-size: 9px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase; color: var(--muted); background: var(--surface-soft); border-bottom: 1px solid var(--ink); }
                tr:last-child td { border-bottom: 0; }
                .status { font-weight: 800; text-transform: uppercase; letter-spacing: 0.06em; }
                .status.ok { color: var(--green); } .status.warn { color: var(--amber); } .status.bad { color: var(--red); }
                .empty { padding: 32px 20px; text-align: center; font-family: var(--font-mono); font-size: 12px; font-weight: 800; color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em; }
                .membership-tools, .alias-row { display: grid; grid-template-columns: 1.4fr .55fr .55fr auto; gap: 8px; align-items: end; }
                .membership-tools { padding: 12px; border: 1px dashed var(--ink); background: var(--surface-muted); }
                .inline-input, .inline-select { width: 100%; padding: 7px 8px; border: 1px solid var(--ink); background: var(--paper); color: var(--ink); font-family: var(--font-mono); font-size: 11px; }
                .row-actions { display: flex; gap: 6px; }
                .field-mini { display: grid; gap: 4px; }
                .field-mini span { font-family: var(--font-mono); font-size: 9px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--muted); }
                .alias-route-list { display: grid; gap: 8px; }
                .alias-route { padding: 10px 12px; border: 1px dashed var(--ink); background: var(--surface-soft); }
                .alias-route-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
                .alias-route-body { font-family: var(--font-mono); font-size: 10px; color: var(--muted); line-height: 1.6; }
                /* Modal */
                .overlay { position: fixed; inset: 0; background: rgba(11,11,11,0.55); display: none; align-items: center; justify-content: center; z-index: 900; }
                .overlay.open { display: flex; }
                .modal { width: min(680px, 94vw); max-height: 88vh; overflow-y: auto; background: var(--paper); border: 2px solid var(--ink); box-shadow: var(--shadow-lg); }
                .modal-head { display: flex; justify-content: space-between; padding: 14px 18px; background: var(--surface-accent); color: var(--on-accent); font-family: var(--font-headline); font-size: 18px; letter-spacing: -0.04em; text-transform: uppercase; }
                .modal-body { padding: 18px; display: grid; gap: 14px; }
                .field label { display: block; margin-bottom: 6px; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted); }
                .field input, .field textarea, .field select { width: 100%; padding: 10px 12px; border: 2px solid var(--ink); background: var(--paper); font-family: var(--font-mono); font-size: 12px; color: var(--ink); }
                .modal-actions { display: flex; gap: 10px; justify-content: flex-end; }
                .error-banner { display: none; padding: 10px 14px; background: var(--red); color: var(--on-accent); font-family: var(--font-mono); font-size: 11px; font-weight: 800; letter-spacing: 0.06em; }
                .error-banner.visible { display: block; }
                @media (max-width: 1080px) {
                    .group-overview { grid-template-columns: 1fr; }
                }
            </style>
            <div class="layout">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="toolbar">
                    <span style="font-family:var(--font-mono);font-size:11px;font-weight:800;letter-spacing:0.12em;text-transform:uppercase;color:var(--muted);" data-ref="metaLine"></span>
                    <span class="spacer"></span>
                    <button class="btn-primary" data-ref="newBtn">+ New Group</button>
                </div>
                <div class="grid" data-ref="grid"></div>
            </div>

            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span data-ref="modalTitle">New Group</span><button class="btn-ghost" data-ref="closeBtn">ESC</button></div>
                    <div class="error-banner" data-ref="error"></div>
                    <div class="modal-body">
                        <div class="field"><label>Group ID</label><input data-ref="fId" placeholder="premium" /></div>
                        <div class="field"><label>Display Name</label><input data-ref="fName" placeholder="Premium" /></div>
                        <div class="field"><label>Description</label><textarea data-ref="fDesc" rows="2" placeholder="Optional"></textarea></div>
                        <div class="field">
                            <label>Exposure Mode</label>
                            <select data-ref="fExposureMode">
                                <option value="ALL_MODELS">All Models</option>
                                <option value="ALIASES_ONLY">Aliases Only</option>
                                <option value="ALIASES_AND_MODELS">Aliases + Models</option>
                            </select>
                        </div>
                        <div class="field">
                            <label>Alias Routes</label>
                            <datalist id="aliasModelList" data-ref="aliasModelList"></datalist>
                            <div data-ref="aliasRows" style="display:grid;gap:8px;margin-bottom:8px;"></div>
                            <button class="btn-ghost" data-ref="addAliasBtn" style="font-size:10px;">+ Add Alias Target</button>
                            <div style="margin-top:6px;font-family:var(--font-mono);font-size:10px;color:var(--muted);">Each row is one fallback step: alias name → target model → optional channel constraint. Leave channel empty to match any attached channel that serves that model.</div>
                        </div>
                        <div class="field"><label><input type="checkbox" data-ref="fEnabled" checked /> Enabled</label></div>
                        <div class="modal-actions"><button class="btn-ghost" data-ref="cancelBtn">Cancel</button><button class="btn-primary" data-ref="saveBtn">Save</button></div>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label: 'Routing Pools', title: 'Groups', metaHtml: '' });
        this._groups = []; this._channels = []; this._chains = []; this._editing = null;
        this.refs.newBtn.addEventListener('click', () => this._openModal(null));
        this.refs.cancelBtn.addEventListener('click', () => this._closeModal());
        this.refs.closeBtn.addEventListener('click', () => this._closeModal());
        this.refs.overlay.addEventListener('click', (e) => { if (e.target === this.refs.overlay) this._closeModal(); });
        this.refs.saveBtn.addEventListener('click', () => this._save());
        this.refs.addAliasBtn.addEventListener('click', () => this._addAliasRow());
    }

    async refresh() {
        try {
            const [groups, channels, pools] = await Promise.all([
                requestJson(`${API.airelay}/admin/groups`),
                requestJson(`${API.airelay}/admin/channels`),
                requestJson(`${API.airelay}/admin/pools`),
            ]);
            this._groups = groups.groups || [];
            this._channels = channels.channels || [];
            this._chains = pools.chains || [];
            this._render();
        } catch (e) {
            this.refs.grid.innerHTML = `<div class="empty">Failed to load groups: ${escapeHtml(e.message)}</div>`;
        }
    }

    _render() {
        const total = this._groups.length;
        this.refs.metaLine.textContent = `${total} group${total !== 1 ? 's' : ''} / ${this._channels.length} channels`;
        this.refs.hero.render({
            label: 'Routing Pools',
            title: 'Groups',
            metaHtml: `<span style="display:inline-flex;align-items:center;padding:7px 10px;border:1px solid rgba(235,231,223,0.18);background:rgba(11,11,11,0.18);color:var(--on-accent);font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.08em;text-transform:uppercase;">${total} group${total !== 1 ? 's' : ''}</span>`
        });
        if (!total) { this.refs.grid.innerHTML = '<div class="empty">No routing groups configured.</div>'; return; }
        const channelsByGroup = this._channels.reduce((acc, c) => {
            const memberships = c.memberships?.length ? c.memberships : [{ groupId: c.groupId || 'default', priority: c.priority ?? 0, weight: c.weight ?? 100, enabled: c.enabled }];
            memberships.filter(m => m.enabled).forEach(m => (acc[m.groupId] ||= []).push({ ...c, membershipPriority: m.priority, membershipWeight: m.weight, membershipEnabled: m.enabled }));
            return acc;
        }, {});
        const chainByGroup = Object.fromEntries(this._chains.map(c => [c.chainId, c]));
        this.refs.grid.innerHTML = this._groups.map(group => this._renderGroupCard(group, channelsByGroup[group.groupId] || [], chainByGroup[group.groupId])).join('');
        this._bindCardActions();
    }

    _renderGroupCard(group, channels, chain) {
        const models = [...new Set(channels.flatMap(c => (c.models || []).filter(m => m.enabled).map(m => m.publicModelName)))].sort();
        const priorityMap = channels.reduce((acc, c) => { (acc[String(c.membershipPriority ?? c.priority ?? 0)] ||= []).push(c); return acc; }, {});
        const priorityKeys = Object.keys(priorityMap).sort((a, b) => Number(b) - Number(a));
        const levelHealth = chain ? Object.fromEntries(chain.levels.map(l => [l.levelId, l])) : {};
        const aliasRoutes = (group.aliasRoutes || []).filter(a => a.enabled);
        const channelNameById = Object.fromEntries(this._channels.map(c => [c.channelId, c.name]));
        const savedAliasNames = aliasRoutes.map(route => route.aliasName);
        const exposedModels = this._fallbackExposedModels(
            group.exposureMode,
            models,
            savedAliasNames.length ? savedAliasNames : (chain?.modelAliases || [])
        );
        const attachedIds = new Set(channels.map(c => c.channelId));
        const attachOptions = this._channels.filter(c => !attachedIds.has(c.channelId)).map(c => `<option value="${escapeHtml(c.channelId)}">${escapeHtml(c.name)} · ${escapeHtml(c.protocol)}</option>`).join('');
        const priorityBlocks = priorityKeys.length === 0 ? '<div class="empty">No channels assigned to this group.</div>' : priorityKeys.map((priority, idx) => {
            const levelId = `${group.groupId}-p${priority}`;
            const lh = levelHealth[levelId];
            const rows = priorityMap[priority];
            return `
                <div class="priority-block">
                    <div class="priority-head"><span><span class="badge">P${priority}</span>&nbsp; ${idx === 0 ? 'Primary' : `Backup #${idx}`}</span><span>${rows.length} channel${rows.length !== 1 ? 's' : ''}${lh ? ` · ${lh.healthyKeys} healthy / ${lh.cooldownKeys + lh.degradedKeys + lh.disabledKeys} watch` : ''}</span></div>
                    <table><thead><tr><th>Channel</th><th>Protocol</th><th>Priority</th><th>Weight</th><th>Models</th><th>Status</th><th>Actions</th></tr></thead><tbody>
                        ${rows.map(c => `
                            <tr data-membership-row="${escapeHtml(group.groupId)}:${escapeHtml(c.channelId)}">
                                <td>${escapeHtml(c.name)}</td>
                                <td>${escapeHtml(c.protocol)}</td>
                                <td><input class="inline-input" data-member-priority value="${c.membershipPriority ?? c.priority ?? 0}" type="number"></td>
                                <td><input class="inline-input" data-member-weight value="${c.membershipWeight ?? c.weight ?? 100}" type="number"></td>
                                <td>${(c.models || []).filter(m => m.enabled).map(m => `<code>${escapeHtml(m.publicModelName)}</code>`).join(' ') || '<span style="color:var(--muted);">—</span>'}</td>
                                <td>${this._statusCell(c)}</td>
                                <td><div class="row-actions"><button class="btn-ghost" data-save-membership="${escapeHtml(group.groupId)}:${escapeHtml(c.channelId)}">Save</button><button class="btn-danger" data-detach-membership="${escapeHtml(group.groupId)}:${escapeHtml(c.channelId)}">Detach</button></div></td>
                            </tr>`).join('')}
                    </tbody></table>
                </div>`;
        }).join('');
        return `
            <article class="group-card">
                <header class="group-head">
                    <div><h3 class="group-title">${escapeHtml(group.name || group.groupId)}</h3><span class="group-sub">${escapeHtml(group.groupId)}${group.description ? ` · ${escapeHtml(group.description)}` : ''}</span></div>
                    <div class="group-meta">
                        <span class="tag ${group.enabled ? 'is-enabled' : 'is-disabled'}">${group.enabled ? 'ENABLED' : 'DISABLED'}</span>
                        <span class="tag is-enabled">${channels.length} channels</span><span class="tag is-enabled">${models.length} models</span>
                        <button class="btn-ghost" data-edit-group="${escapeHtml(group.groupId)}">Edit</button>
                        ${group.groupId === 'default' ? '' : `<button class="btn-danger" data-delete-group="${escapeHtml(group.groupId)}">Delete</button>`}
                    </div>
                </header>
                <div class="card-error" data-card-error="${escapeHtml(group.groupId)}" style="display:none;padding:10px 18px;background:var(--red);color:var(--on-accent);font-family:var(--font-mono);font-size:11px;font-weight:800;"></div>
                <div class="group-body">
                    <div class="group-overview">
                        <div class="info-stack">
                            <div class="detail-card">
                                <div class="section-label">Exposed To Clients</div>
                                <div class="models"><code>${escapeHtml(EXPOSURE_LABELS[group.exposureMode] || group.exposureMode || 'All Models')}</code>${exposedModels.map(m => `<code>${escapeHtml(m)}</code>`).join('') || '<span class="empty">Nothing exposed</span>'}</div>
                            </div>
                            <div class="detail-card">
                                <div class="section-label">Channel Models</div>
                                <div class="models">${models.map(m => `<code>${escapeHtml(m)}</code>`).join('') || '<span class="empty">No channel models</span>'}</div>
                            </div>
                        </div>
                        <div class="detail-card aliases">
                            <div class="section-label">Alias Routes</div>
                            ${this._renderAliasRoutes(aliasRoutes, channelNameById)}
                        </div>
                    </div>
                    <div class="membership-tools">
                        <select class="inline-select" data-attach-channel="${escapeHtml(group.groupId)}">${attachOptions || '<option value="">No unattached channels</option>'}</select>
                        <input class="inline-input" data-attach-priority="${escapeHtml(group.groupId)}" value="0" type="number" placeholder="Priority">
                        <input class="inline-input" data-attach-weight="${escapeHtml(group.groupId)}" value="100" type="number" placeholder="Weight">
                        <button class="btn-primary" data-attach-membership="${escapeHtml(group.groupId)}" ${attachOptions ? '' : 'disabled'}>Attach</button>
                    </div>
                    ${priorityBlocks}
                </div>
            </article>`;
    }

    _bindCardActions() {
        this.shadowRoot.querySelectorAll('[data-edit-group]').forEach(btn => btn.addEventListener('click', () => this._openModal(this._groups.find(x => x.groupId === btn.dataset.editGroup))));
        this.shadowRoot.querySelectorAll('[data-delete-group]').forEach(btn => btn.addEventListener('click', () => this._delete(btn.dataset.deleteGroup, btn)));
        this.shadowRoot.querySelectorAll('[data-attach-membership]').forEach(btn => btn.addEventListener('click', () => this._attachMembership(btn.dataset.attachMembership)));
        this.shadowRoot.querySelectorAll('[data-save-membership]').forEach(btn => btn.addEventListener('click', () => this._saveMembership(btn.dataset.saveMembership)));
        this.shadowRoot.querySelectorAll('[data-detach-membership]').forEach(btn => btn.addEventListener('click', () => this._detachMembership(btn.dataset.detachMembership)));
    }

    _statusCell(channel) {
        if (!channel.enabled) return '<span class="status bad">Disabled</span>';
        if (channel.status === 'HEALTHY') return '<span class="status ok">Healthy</span>';
        if (channel.status === 'DEGRADED') return '<span class="status warn">Degraded</span>';
        return `<span class="status bad">${escapeHtml(channel.status || 'Unknown')}</span>`;
    }

    _fallbackExposedModels(exposureMode, directModels, aliasNames) {
        const aliases = [...new Set((aliasNames || []).filter(Boolean))];
        const mode = this._normalizeExposure(exposureMode);
        if (mode === 'ALIASES_ONLY') return aliases;
        if (mode === 'ALIASES_AND_MODELS') return [...new Set([...aliases, ...directModels])];
        return directModels;
    }

    _renderAliasRoutes(aliasRoutes, channelNameById) {
        if (!aliasRoutes.length) return '<div class="empty">No aliases configured.</div>';
        return `<div class="alias-route-list">${aliasRoutes.map(alias => {
            const targets = (alias.targets?.length ? alias.targets : (alias.targetModels || []).map(model => ({ model, channelId: null })))
                .map(target => {
                    const channelLabel = target.channelId
                        ? (channelNameById[target.channelId] ? `${channelNameById[target.channelId]} (${target.channelId})` : target.channelId)
                        : 'Any attached channel';
                    return `${target.model} -> ${channelLabel}`;
                });
            return `
                <div class="alias-route">
                    <div class="alias-route-head"><code>${escapeHtml(alias.aliasName)}</code><span class="tag is-enabled">${targets.length} target${targets.length === 1 ? '' : 's'}</span>${alias.creditMultiplier != null ? `<span class="tag">×${escapeHtml(String(alias.creditMultiplier))} credit</span>` : ''}</div>
                    <div class="alias-route-body">${targets.map(t => escapeHtml(t)).join('<br>')}</div>
                </div>`;
        }).join('')}</div>`;
    }

    _openModal(group) {
        this._editing = group || null;
        this.refs.modalTitle.textContent = group ? `Edit Group · ${group.groupId}` : 'New Group';
        this.refs.fId.value = group?.groupId || '';
        this.refs.fId.disabled = Boolean(group);
        this.refs.fName.value = group?.name || '';
        this.refs.fDesc.value = group?.description || '';
        this.refs.fExposureMode.value = this._normalizeExposure(group?.exposureMode || 'ALL_MODELS');
        this.refs.aliasRows.innerHTML = '';
        this._syncAliasModelSuggestions(group);
        (group?.aliasRoutes || []).forEach(alias => {
            const targets = alias.targets?.length ? alias.targets : (alias.targetModels || []).map(model => ({ model, channelId: '' }));
            targets.forEach(target => this._addAliasRow({ aliasName: alias.aliasName, model: target.model, channelId: target.channelId || '', enabled: alias.enabled, creditMultiplier: alias.creditMultiplier }));
        });
        if (!this.refs.aliasRows.children.length) this._addAliasRow();
        this.refs.fEnabled.checked = group?.enabled ?? true;
        this.refs.error.classList.remove('visible');
        this.refs.error.textContent = '';
        this.refs.overlay.classList.add('open');
    }

    _syncAliasModelSuggestions(group) {
        const allowedChannelIds = new Set(
            this._channels.flatMap(channel => (channel.memberships || []).filter(m => m.groupId === (group?.groupId || '')).map(() => channel.channelId))
        );
        const candidateChannels = group
            ? this._channels.filter(channel => allowedChannelIds.has(channel.channelId))
            : this._channels;
        const suggestions = [...new Set(candidateChannels.flatMap(channel =>
            (channel.models || []).flatMap(model => {
                const names = [model.publicModelName];
                if (model.upstreamModelName && model.upstreamModelName !== model.publicModelName) names.push(model.upstreamModelName);
                return names;
            }).filter(Boolean)
        ))].sort();
        this.refs.aliasModelList.innerHTML = suggestions.map(model => `<option value="${escapeHtml(model)}"></option>`).join('');
    }

    _addAliasRow(row = { aliasName: '', model: '', channelId: '', enabled: true, creditMultiplier: '' }) {
        const el = document.createElement('div');
        el.className = 'alias-row';
        const channelOptions = ['<option value="">Any attached channel</option>'].concat(this._channels.map(c => `<option value="${escapeHtml(c.channelId)}" ${c.channelId === row.channelId ? 'selected' : ''}>${escapeHtml(c.name)} (${escapeHtml(c.channelId)})</option>`)).join('');
        el.innerHTML = `
            <label class="field-mini"><span>Custom Name</span><input class="inline-input" data-alias-name value="${escapeHtml(row.aliasName || '')}" placeholder="smart-claude"></label>
            <label class="field-mini"><span>Target Model</span><input class="inline-input" data-alias-model list="aliasModelList" value="${escapeHtml(row.model || '')}" placeholder="public or upstream model"></label>
            <label class="field-mini"><span>Channel Constraint</span><select class="inline-select" data-alias-channel>${channelOptions}</select></label>
            <label class="field-mini"><span>Credit ×</span><input class="inline-input" data-alias-credit type="number" step="0.1" min="0" value="${escapeHtml(row.creditMultiplier ?? '')}" placeholder="1.0"></label>
            <button class="btn-danger" data-remove-alias>Remove</button>`;
        el.querySelector('[data-remove-alias]').addEventListener('click', () => { el.remove(); if (!this.refs.aliasRows.children.length) this._addAliasRow(); });
        this.refs.aliasRows.appendChild(el);
    }

    _readAliasRows() {
        const grouped = new Map();
        Array.from(this.refs.aliasRows.querySelectorAll('.alias-row')).forEach(row => {
            const aliasName = row.querySelector('[data-alias-name]').value.trim();
            const model = row.querySelector('[data-alias-model]').value.trim();
            const channelId = row.querySelector('[data-alias-channel]').value.trim();
            const creditMultiplierRaw = row.querySelector('[data-alias-credit]').value.trim();
            if (!aliasName || !model) return;
            if (!grouped.has(aliasName)) grouped.set(aliasName, []);
            grouped.get(aliasName).push({ model, channelId: channelId || null, creditMultiplier: creditMultiplierRaw ? parseFloat(creditMultiplierRaw) : null });
        });
        return Array.from(grouped.entries()).map(([aliasName, targets]) => ({
            aliasName,
            targetModels: targets.map(t => t.model),
            targets: targets.map(({ model, channelId }) => ({ model, channelId })),
            creditMultiplier: targets.find(t => t.creditMultiplier != null)?.creditMultiplier ?? null,
            enabled: true,
        }));
    }

    _normalizeExposure(value) {
        if (value === 'ALIAS_ONLY') return 'ALIASES_ONLY';
        if (value === 'ALIAS_AND_MODEL_NAMES') return 'ALIASES_AND_MODELS';
        if (value === 'MODEL_NAMES_ONLY') return 'ALL_MODELS';
        return value || 'ALL_MODELS';
    }

    _closeModal() { this.refs.overlay.classList.remove('open'); }

    async _save() {
        const payload = {
            groupId: this.refs.fId.value.trim() || null,
            name: this.refs.fName.value.trim(),
            description: this.refs.fDesc.value.trim() || null,
            enabled: this.refs.fEnabled.checked,
            exposureMode: this.refs.fExposureMode.value || 'ALL_MODELS',
            aliasRoutes: this._readAliasRows(),
        };
        if (!payload.name) { this._showError('Display name is required.'); return; }
        try {
            if (this._editing) await putJson(`${API.airelay}/admin/groups/${encodeURIComponent(this._editing.groupId)}`, payload);
            else {
                if (!payload.groupId) { this._showError('Group ID is required.'); return; }
                await postJson(`${API.airelay}/admin/groups`, payload);
            }
            this._closeModal();
            this.refresh();
        } catch (e) { this._showError(e.message); }
    }

    async _attachMembership(groupId) {
        const channelId = this.shadowRoot.querySelector(`[data-attach-channel="${CSS.escape(groupId)}"]`)?.value;
        if (!channelId) return;
        const priority = parseInt(this.shadowRoot.querySelector(`[data-attach-priority="${CSS.escape(groupId)}"]`)?.value, 10) || 0;
        const weight = parseInt(this.shadowRoot.querySelector(`[data-attach-weight="${CSS.escape(groupId)}"]`)?.value, 10) || 100;
        const btn = this.shadowRoot.querySelector(`[data-attach-membership="${CSS.escape(groupId)}"]`);
        this._clearCardError(groupId);
        if (btn) { btn.disabled = true; btn.textContent = 'Attaching…'; }
        try { await postJson(`${API.airelay}/admin/groups/${encodeURIComponent(groupId)}/memberships`, { channelId, priority, weight, enabled: true }); await this.refresh(); }
        catch (e) {
            this._showCardError(groupId, e.message);
            if (btn) { btn.disabled = false; btn.textContent = 'Attach'; }
        }
    }

    async _saveMembership(key) {
        const [groupId, channelId] = key.split(':');
        const row = this.shadowRoot.querySelector(`[data-membership-row="${CSS.escape(key)}"]`);
        const priority = parseInt(row?.querySelector('[data-member-priority]')?.value, 10) || 0;
        const weight = parseInt(row?.querySelector('[data-member-weight]')?.value, 10) || 100;
        const btn = this.shadowRoot.querySelector(`[data-save-membership="${CSS.escape(key)}"]`);
        this._clearCardError(groupId);
        if (btn) { btn.disabled = true; btn.textContent = 'Saving…'; }
        try { await putJson(`${API.airelay}/admin/groups/${encodeURIComponent(groupId)}/memberships/${encodeURIComponent(channelId)}`, { priority, weight, enabled: true }); await this.refresh(); }
        catch (e) {
            this._showCardError(groupId, e.message);
            if (btn) { btn.disabled = false; btn.textContent = 'Save'; }
        }
    }

    async _detachMembership(key) {
        const [groupId, channelId] = key.split(':');
        if (!confirm(`Detach channel ${channelId} from group ${groupId}?`)) return;
        const btn = this.shadowRoot.querySelector(`[data-detach-membership="${CSS.escape(key)}"]`);
        this._clearCardError(groupId);
        if (btn) { btn.disabled = true; btn.textContent = 'Detaching…'; }
        try { await deleteJson(`${API.airelay}/admin/groups/${encodeURIComponent(groupId)}/memberships/${encodeURIComponent(channelId)}`); await this.refresh(); }
        catch (e) {
            this._showCardError(groupId, e.message);
            if (btn) { btn.disabled = false; btn.textContent = 'Detach'; }
        }
    }

    async _delete(groupId, btn) {
        if (!confirm(`Delete group "${groupId}"? Detach all channels first; the default group cannot be deleted.`)) return;
        this._clearCardError(groupId);
        if (btn) { btn.disabled = true; btn.textContent = 'Deleting…'; }
        try {
            await deleteJson(`${API.airelay}/admin/groups/${encodeURIComponent(groupId)}`);
            this.refresh();
        } catch (e) {
            this._showCardError(groupId, e.message);
            if (btn) { btn.disabled = false; btn.textContent = 'Delete'; }
        }
    }

    _cardErrorEl(groupId) {
        return this.shadowRoot.querySelector(`[data-card-error="${CSS.escape(groupId)}"]`);
    }

    _showCardError(groupId, message) {
        const errorEl = this._cardErrorEl(groupId);
        if (!errorEl) return;
        errorEl.textContent = message;
        errorEl.style.display = 'block';
    }

    _clearCardError(groupId) {
        const errorEl = this._cardErrorEl(groupId);
        if (!errorEl) return;
        errorEl.textContent = '';
        errorEl.style.display = 'none';
    }

    _showError(message) {
        this.refs.error.textContent = message;
        this.refs.error.classList.add('visible');
    }
}

customElements.define('ai-panel-groups', PanelGroups);
