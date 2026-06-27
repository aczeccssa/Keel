import { KeelElement } from './base/KeelElement.js';
import { requestJson } from '../api.js';
import { API } from '../config.js';
import { state, setTab } from '../state.js';
import { escapeHtml } from '../utils.js';

const PRESET_OPTIONS = [
    { value: 'today', label: 'Today' },
    { value: '1d', label: '1 Day' },
    { value: '3d', label: '3 Days' },
    { value: '7d', label: '7 Days' },
    { value: '30d', label: '30 Days' },
    { value: 'lastMonth', label: 'Last Month' },
];

const STATUS_OPTIONS = [
    { value: '', label: 'All status' },
    { value: 'success', label: 'Success' },
    { value: 'error', label: 'Error' },
];

const LIMIT_OPTIONS = [
    { value: '50', label: '50 rows' },
    { value: '100', label: '100 rows' },
    { value: '200', label: '200 rows' },
];

const MINUTE_OPTIONS = Array.from({ length: 60 }, (_, minute) => minute);
const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/**
 * Detail view: per-request records with full token + cost breakdown.
 * Filters are server-side selects (group / channel / model / status / time range).
 */
export class PanelUsage extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .layout { display: flex; flex-direction: column; gap: 14px; height: 100%; }
                .hero-meta { display: inline-flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; padding: 2px 0; }
                .hero-chip {
                    display: inline-flex; align-items: center; padding: 6px 8px;
                    border: 1px solid rgba(235, 231, 223, 0.18); background: rgba(11, 11, 11, 0.18);
                    color: var(--on-accent); font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                    letter-spacing: 0.08em; text-transform: uppercase;
                }
                .toolbar {
                    display: grid; gap: 10px; padding: 12px; background: var(--panel-strong);
                    border: 2px solid var(--ink); position: relative;
                }
                .preset-row { display: flex; flex-wrap: wrap; gap: 6px; }
                .preset-chip,
                .clear-btn,
                .pager button,
                .detail-btn,
                .filter-trigger,
                .calendar-nav,
                .calendar-day,
                .calendar-action,
                .time-btn {
                    appearance: none; -webkit-appearance: none; border-radius: 0; box-shadow: none;
                    cursor: pointer; transition: background 120ms var(--ease-smooth), color 120ms var(--ease-smooth), border-color 120ms var(--ease-smooth), transform 120ms var(--ease-smooth);
                }
                .preset-chip {
                    padding: 7px 10px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase;
                }
                .preset-chip.is-active { background: var(--surface-accent); color: var(--on-accent); border-color: var(--surface-accent); }
                .preset-chip:hover,
                .filter-trigger:hover,
                .calendar-nav:hover,
                .calendar-day:hover,
                .calendar-action:hover,
                .time-btn:hover,
                .pager button:hover,
                .detail-btn:hover { transform: translate(-1px, -1px); }
                .filter-grid { display: grid; grid-template-columns: repeat(8, minmax(0, 1fr)); gap: 10px; align-items: end; }
                .field { display: flex; flex-direction: column; gap: 4px; position: relative; min-width: 0; }
                .field.span-2 { grid-column: span 2; }
                .field label {
                    font-family: var(--font-mono); font-size: 8px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted);
                }
                .filter-trigger {
                    width: 100%; min-height: 38px; padding: 8px 10px; border: 2px solid var(--ink);
                    background: var(--paper); color: var(--ink); display: flex; align-items: center; justify-content: space-between; gap: 10px;
                    font-family: var(--font-mono); font-size: 11px; text-align: left;
                }
                .filter-trigger .value { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                .filter-trigger .caret { color: var(--muted); font-size: 11px; }
                .filter-menu {
                    position: absolute; top: calc(100% + 6px); left: 0; right: 0; z-index: 40; background: var(--paper);
                    border: 2px solid var(--ink); box-shadow: var(--shadow-lg); max-height: 260px; overflow: auto;
                }
                .filter-option {
                    width: 100%; padding: 10px 12px; border: 0; border-bottom: 1px solid var(--ink); background: var(--paper); color: var(--ink);
                    display: flex; align-items: center; justify-content: space-between; gap: 12px;
                    font-family: var(--font-mono); font-size: 11px; text-align: left; cursor: pointer;
                }
                .filter-option:last-child { border-bottom: 0; }
                .filter-option:hover,
                .filter-option.is-active { background: var(--surface-accent); color: var(--on-accent); }
                .filter-option .meta { color: inherit; opacity: 0.72; font-size: 10px; letter-spacing: 0.06em; text-transform: uppercase; }
                .filter-action { display: flex; align-items: flex-end; justify-content: flex-end; }
                .clear-btn {
                    min-height: 38px; padding: 8px 14px; border: 2px solid var(--red); background: transparent; color: var(--red);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase;
                }
                .clear-btn:hover { background: var(--red); color: var(--paper); }
                .range-meta {
                    display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
                    font-family: var(--font-mono); font-size: 10px; color: var(--muted);
                }
                .range-chip {
                    display: inline-flex; align-items: center; gap: 6px; padding: 6px 8px;
                    border: 1px solid var(--ink); background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.06em; text-transform: uppercase;
                }
                .date-panel {
                    position: absolute; top: calc(100% + 6px); left: 0; z-index: 45; width: min(420px, 92vw);
                    background: var(--paper); border: 2px solid var(--ink); box-shadow: var(--shadow-lg); padding: 14px; display: grid; gap: 12px;
                }
                .date-panel-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
                .date-panel-title {
                    font-family: var(--font-headline); font-size: 22px; line-height: 1; letter-spacing: -0.03em;
                }
                .calendar-nav {
                    width: 32px; height: 32px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink);
                    display: inline-flex; align-items: center; justify-content: center;
                    font-family: var(--font-mono); font-size: 12px; font-weight: 800;
                }
                .calendar-weekdays,
                .calendar-grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 4px; }
                .calendar-weekdays span {
                    padding: 6px 0; text-align: center; font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                    letter-spacing: 0.12em; text-transform: uppercase; color: var(--muted);
                }
                .calendar-day {
                    min-height: 38px; border: 1px solid var(--ink); background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 11px; font-weight: 800;
                }
                .calendar-day.is-outside { opacity: 0.34; }
                .calendar-day.is-active { background: var(--surface-accent); color: var(--on-accent); border-color: var(--surface-accent); }
                .time-grid { display: grid; grid-template-columns: 110px 1fr; gap: 12px; }
                .time-col { display: grid; gap: 6px; min-width: 0; }
                .time-col span {
                    font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                    letter-spacing: 0.12em; text-transform: uppercase; color: var(--muted);
                }
                .time-list {
                    display: grid; gap: 4px; max-height: 164px; overflow: auto; padding-right: 2px;
                    grid-template-columns: repeat(2, minmax(0, 1fr));
                }
                .time-list.minutes { grid-template-columns: repeat(4, minmax(0, 1fr)); }
                .time-btn {
                    padding: 8px 6px; border: 1px solid var(--ink); background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.06em;
                }
                .time-btn.is-active,
                .calendar-action.is-primary {
                    background: var(--surface-accent); color: var(--on-accent); border-color: var(--surface-accent);
                }
                .date-panel-actions { display: flex; justify-content: space-between; gap: 10px; }
                .date-panel-actions div { display: flex; gap: 8px; }
                .calendar-action {
                    padding: 9px 12px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase;
                }
                .calendar-action.is-danger { border-color: var(--red); color: var(--red); }
                .calendar-action.is-danger:hover { background: var(--red); color: var(--paper); }
                .overview {
                    display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 2px;
                    background: var(--surface-accent); border: 2px solid var(--ink);
                }
                .summary {
                    display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 1px;
                    background: transparent; min-width: 0;
                }
                .summary .cell { background: var(--paper); padding: 9px 10px; font-family: var(--font-mono); min-width: 0; }
                .summary .label { font-size: 8px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--muted); }
                .summary .val { margin-top: 3px; font-family: var(--font-headline); font-size: 15px; line-height: 1; letter-spacing: -0.03em; font-feature-settings: 'tnum'; }
                .pager {
                    display: flex; gap: 10px; align-items: center; justify-content: space-between; flex-wrap: nowrap;
                    padding: 9px 12px; background: var(--paper); min-width: 248px;
                }
                .pager .range { font-family: var(--font-mono); font-size: 10px; font-weight: 800; color: var(--muted); }
                .pager-actions { display: flex; gap: 8px; align-items: center; }
                .pager button {
                    padding: 7px 10px; border: 2px solid var(--ink); background: var(--panel-strong); color: var(--ink);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase;
                }
                .pager button:disabled { opacity: .38; cursor: not-allowed; transform: none; }
                .table-card {
                    background: var(--panel-strong); border: 2px solid var(--ink);
                    flex: 1; min-height: 50vh; overflow: hidden; display: flex; flex-direction: column;
                }
                .table-scroll { overflow: auto; flex: 1; min-height: 0; max-height: none; }
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
                table.usage thead th:last-child {
                    position: sticky; right: 0; z-index: 3; text-align: right;
                    box-shadow: -1px 0 0 rgba(244,244,240,0.28), -12px 0 18px rgba(11, 11, 11, 0.22);
                }
                table.usage tbody td:last-child {
                    position: sticky; right: 0; z-index: 2; text-align: right;
                    background: var(--panel-strong);
                    box-shadow: -1px 0 0 var(--ink), -12px 0 18px rgba(11, 11, 11, 0.18);
                }
                table.usage tbody tr:nth-child(even) td { background: var(--color-surface-container-low, #ebe9e3); }
                table.usage tbody tr:nth-child(even) td:last-child { background: var(--color-surface-container-low, #ebe9e3); }
                table.usage code {
                    font-family: var(--font-mono); font-size: 10.5px; font-weight: 800; letter-spacing: .04em;
                    background: var(--surface-accent); color: var(--on-accent); border: 1px solid var(--surface-accent); padding: 2px 6px;
                }
                .detail-btn {
                    border: 1px solid var(--teal); color: var(--teal); background: transparent; padding: 6px 10px;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase;
                }
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
                @media (max-width: 1480px) {
                    .filter-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
                    .field.span-2 { grid-column: span 1; }
                }
                @media (max-width: 1180px) {
                    .overview { grid-template-columns: 1fr; }
                    .pager { min-width: 0; border-top: 1px solid var(--ink); }
                    .summary { grid-template-columns: repeat(4, minmax(0, 1fr)); }
                }
                @media (max-width: 820px) {
                    .filter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
                    .time-grid { grid-template-columns: 1fr; }
                    .date-panel { width: min(360px, calc(100vw - 32px)); }
                    .summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
                }
                @media (max-width: 620px) {
                    .filter-grid { grid-template-columns: 1fr; }
                    .filter-action { justify-content: stretch; }
                    .filter-action .clear-btn { width: 100%; }
                    .pager { flex-wrap: wrap; }
                }
            </style>
            <div class="layout" data-ref="root">
                <keel-hero data-ref="hero" compact="true"></keel-hero>
                <div class="toolbar" data-ref="toolbar"></div>
                <div class="overview">
                    <div class="summary" data-ref="summary"></div>
                    <div class="pager">
                        <div class="range" data-ref="pageRange">0 records</div>
                        <div class="pager-actions">
                            <button data-ref="prevBtn" disabled>Prev</button>
                            <button data-ref="nextBtn" disabled>Next</button>
                        </div>
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
        this._openMenu = null;
        this._dateDrafts = {};
        this._calendarViews = {};
        this._filters = this._defaultFilters();
        this._filterOptions = {
            group: [{ value: '', label: 'All groups' }],
            channel: [{ value: '', label: 'All channels' }],
            model: [{ value: '', label: 'All models' }],
            status: STATUS_OPTIONS,
            limit: LIMIT_OPTIONS,
        };
        // this.refs.hero.render({ label: 'Request Ledger', title: 'Usage', metaHtml: '' });
        this._renderToolbar();
        this.refs.toolbar.addEventListener('click', (event) => this._handleToolbarClick(event));
        this.refs.prevBtn.addEventListener('click', () => {
            this._offset = Math.max(0, this._offset - this._pageSize);
            this.refresh();
        });
        this.refs.nextBtn.addEventListener('click', () => {
            this._offset = this._offset + this._pageSize;
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
        this._boundDocumentClick = (event) => {
            if (!this._openMenu) return;
            if (event.composedPath().includes(this)) return;
            this._openMenu = null;
            this._renderToolbar();
        };
        this._boundDocumentKeydown = (event) => {
            if (event.key === 'Escape' && this._openMenu) {
                this._openMenu = null;
                this._renderToolbar();
            }
        };
        document.addEventListener('click', this._boundDocumentClick, true);
        document.addEventListener('keydown', this._boundDocumentKeydown);
    }

    disconnectedCallback() {
        document.removeEventListener('click', this._boundDocumentClick, true);
        document.removeEventListener('keydown', this._boundDocumentKeydown);
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

            this._filterOptions.group = [{ value: '', label: 'All groups' }, ...groupList.map(g => ({ value: g.groupId, label: g.name || g.groupId }))];
            this._filterOptions.channel = [{ value: '', label: 'All channels' }, ...channelList.map(c => ({ value: c.channelId, label: c.name || c.channelId }))];
            this._filterOptions.model = [{ value: '', label: 'All models' }, ...models.map(model => ({ value: model, label: model }))];
            this._optionsLoaded = true;
            this._renderToolbar();
        } catch {
            // options are best-effort
        }
    }

    _handleToolbarClick(event) {
        const presetBtn = event.target.closest('[data-preset]');
        if (presetBtn) {
            this._applyPreset(presetBtn.dataset.preset);
            return;
        }

        const clearBtn = event.target.closest('[data-clear-filters]');
        if (clearBtn) {
            this._resetFilters();
            return;
        }

        const openSelectBtn = event.target.closest('[data-open-select]');
        if (openSelectBtn) {
            const field = openSelectBtn.dataset.openSelect;
            this._toggleMenu({ type: 'select', field });
            return;
        }

        const optionBtn = event.target.closest('[data-select-option]');
        if (optionBtn) {
            this._setSelectFilter(optionBtn.dataset.selectOption, optionBtn.dataset.value || '');
            return;
        }

        const openDateBtn = event.target.closest('[data-open-date]');
        if (openDateBtn) {
            const field = openDateBtn.dataset.openDate;
            this._toggleDatePicker(field);
            return;
        }

        const navBtn = event.target.closest('[data-date-nav]');
        if (navBtn) {
            this._shiftCalendarMonth(navBtn.dataset.field, Number(navBtn.dataset.dateNav || '0'));
            return;
        }

        const dayBtn = event.target.closest('[data-date-day]');
        if (dayBtn) {
            this._setDateDraftDay(dayBtn.dataset.field, dayBtn.dataset.dateDay);
            return;
        }

        const hourBtn = event.target.closest('[data-time-hour]');
        if (hourBtn) {
            this._setDateDraftTime(hourBtn.dataset.field, 'hour', Number(hourBtn.dataset.timeHour));
            return;
        }

        const minuteBtn = event.target.closest('[data-time-minute]');
        if (minuteBtn) {
            this._setDateDraftTime(hourBtn ? hourBtn.dataset.field : minuteBtn.dataset.field, 'minute', Number(minuteBtn.dataset.timeMinute));
            return;
        }

        const applyDateBtn = event.target.closest('[data-apply-date]');
        if (applyDateBtn) {
            this._applyDateDraft(applyDateBtn.dataset.applyDate);
            return;
        }

        const clearDateBtn = event.target.closest('[data-clear-date]');
        if (clearDateBtn) {
            this._clearDateFilter(clearDateBtn.dataset.clearDate);
            return;
        }

        const cancelDateBtn = event.target.closest('[data-cancel-date]');
        if (cancelDateBtn) {
            this._openMenu = null;
            this._renderToolbar();
        }
    }

    _toggleMenu(menu) {
        const isSame = this._openMenu?.type === menu.type && this._openMenu?.field === menu.field;
        this._openMenu = isSame ? null : menu;
        if (this._openMenu?.type === 'date') this._ensureDateDraft(menu.field);
        this._renderToolbar();
    }

    _toggleDatePicker(field) {
        const isSame = this._openMenu?.type === 'date' && this._openMenu?.field === field;
        this._openMenu = isSame ? null : { type: 'date', field };
        if (!isSame) this._ensureDateDraft(field);
        this._renderToolbar();
    }

    _setSelectFilter(field, value) {
        this._filters[field] = value;
        if (field !== 'limit') this._offset = 0;
        this._openMenu = null;
        this._renderToolbar();
        this.refresh();
    }

    _ensureDateDraft(field) {
        const source = this._filters[field] ? new Date(this._filters[field]) : new Date();
        const base = Number.isNaN(source.getTime()) ? new Date() : source;
        this._dateDrafts[field] = new Date(base);
        this._calendarViews[field] = new Date(base.getFullYear(), base.getMonth(), 1);
    }

    _shiftCalendarMonth(field, delta) {
        this._ensureDateDraft(field);
        const current = this._calendarViews[field];
        this._calendarViews[field] = new Date(current.getFullYear(), current.getMonth() + delta, 1);
        this._renderToolbar();
    }

    _setDateDraftDay(field, dateKey) {
        this._ensureDateDraft(field);
        const [year, month, day] = String(dateKey).split('-').map(Number);
        const draft = new Date(this._dateDrafts[field]);
        draft.setFullYear(year, month - 1, day);
        this._dateDrafts[field] = draft;
        this._calendarViews[field] = new Date(year, month - 1, 1);
        this._renderToolbar();
    }

    _setDateDraftTime(field, part, value) {
        this._ensureDateDraft(field);
        const draft = new Date(this._dateDrafts[field]);
        if (part === 'hour') draft.setHours(value);
        if (part === 'minute') draft.setMinutes(value);
        draft.setSeconds(0, 0);
        this._dateDrafts[field] = draft;
        this._renderToolbar();
    }

    _applyDateDraft(field) {
        const draft = this._dateDrafts[field];
        if (!draft || Number.isNaN(draft.getTime())) return;
        this._filters[field] = new Date(draft);
        this._filters.preset = 'custom';
        this._offset = 0;
        this._openMenu = null;
        this._renderToolbar();
        this.refresh();
    }

    _clearDateFilter(field) {
        this._filters[field] = field === 'from' ? null : null;
        this._filters.preset = 'custom';
        this._offset = 0;
        this._openMenu = null;
        this._renderToolbar();
        this.refresh();
    }

    _applyPreset(preset) {
        const range = this._rangeForPreset(preset);
        this._filters.preset = preset;
        this._filters.from = range.from;
        this._filters.to = range.to;
        this._offset = 0;
        this._openMenu = null;
        this._renderToolbar();
        this.refresh();
    }

    _resetFilters() {
        this._filters = this._defaultFilters();
        this._offset = 0;
        this._openMenu = null;
        this._renderToolbar();
        if (Object.keys(this._contextQuery()).length) {
            setTab('usage');
            return;
        }
        this.refresh();
    }

    _defaultFilters(now = new Date()) {
        return {
            preset: 'today',
            group: '',
            channel: '',
            model: '',
            status: '',
            from: this._startOfDay(now),
            to: null,
            limit: '50',
        };
    }

    _rangeForPreset(preset, now = new Date()) {
        if (preset === 'today') return { from: this._startOfDay(now), to: null };
        if (preset === '1d') return { from: this._minutesNormalized(this._addDays(now, -1)), to: null };
        if (preset === '3d') return { from: this._minutesNormalized(this._addDays(now, -3)), to: null };
        if (preset === '7d') return { from: this._minutesNormalized(this._addDays(now, -7)), to: null };
        if (preset === '30d') return { from: this._minutesNormalized(this._addDays(now, -30)), to: null };
        if (preset === 'lastMonth') {
            const firstDayThisMonth = new Date(now.getFullYear(), now.getMonth(), 1);
            const from = new Date(firstDayThisMonth.getFullYear(), firstDayThisMonth.getMonth() - 1, 1, 0, 0, 0, 0);
            const to = new Date(firstDayThisMonth.getTime() - 60 * 1000);
            return { from, to };
        }
        return { from: this._filters.from, to: this._filters.to };
    }

    _startOfDay(date) {
        return new Date(date.getFullYear(), date.getMonth(), date.getDate(), 0, 0, 0, 0);
    }

    _addDays(date, delta) {
        const next = new Date(date);
        next.setDate(next.getDate() + delta);
        return next;
    }

    _minutesNormalized(date) {
        const next = new Date(date);
        next.setSeconds(0, 0);
        return next;
    }

    _buildQuery() {
        this._pageSize = parseInt(this._filters.limit, 10) || 50;
        const params = new URLSearchParams({
            limit: String(this._pageSize),
            offset: String(this._offset),
        });
        const context = this._contextQuery();
        if (this._filters.group) params.set('routingGroupId', this._filters.group);
        if (this._filters.channel) params.set('channelId', this._filters.channel);
        if (this._filters.model) params.set('model', this._filters.model);
        if (this._filters.status) params.set('statusFilter', this._filters.status);
        const from = this._toUtcIso(this._filters.from);
        const to = this._toUtcExclusiveIso(this._filters.to);
        if (from) params.set('from', from);
        if (to) params.set('to', to);
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

    _renderToolbar() {
        const hasTo = Boolean(this._filters.to);
        this.refs.toolbar.innerHTML = `
            <div class="field">
                <label>Preset Range</label>
                <div class="preset-row">
                    ${PRESET_OPTIONS.map(option => `
                        <button class="preset-chip ${this._filters.preset === option.value ? 'is-active' : ''}" data-preset="${escapeHtml(option.value)}">
                            ${escapeHtml(option.label)}
                        </button>
                    `).join('')}
                </div>
            </div>
            <!-- <div class="range-meta">
                <span class="range-chip">From ${escapeHtml(this._displayDateTime(this._filters.from))}</span>
                <span class="range-chip">${hasTo ? `To ${escapeHtml(this._displayDateTime(this._filters.to))}` : 'To Open Ended'}</span>
                <span class="range-chip">${escapeHtml(this._timezoneLabel())}</span>
            </div> -->
            <div class="filter-grid">
                ${this._selectFieldHtml('group', 'Group', this._filterOptions.group)}
                ${this._selectFieldHtml('channel', 'Channel', this._filterOptions.channel)}
                ${this._selectFieldHtml('model', 'Model', this._filterOptions.model)}
                ${this._selectFieldHtml('status', 'Status', this._filterOptions.status)}
                ${this._dateFieldHtml('from', 'From')}
                ${this._dateFieldHtml('to', 'To')}
                ${this._selectFieldHtml('limit', 'Page Size', this._filterOptions.limit)}
                <div class="field filter-action">
                    <label>Action</label>
                    <button class="clear-btn" data-clear-filters>Reset Filters</button>
                </div>
            </div>
        `;
    }

    _selectFieldHtml(field, label, options) {
        const selected = options.find(option => option.value === this._filters[field]) || options[0];
        const isOpen = this._openMenu?.type === 'select' && this._openMenu?.field === field;
        return `
            <div class="field">
                <label>${escapeHtml(label)}</label>
                <button class="filter-trigger" data-open-select="${escapeHtml(field)}" aria-expanded="${isOpen ? 'true' : 'false'}">
                    <span class="value">${escapeHtml(selected?.label || 'Select')}</span>
                    <span class="caret">${isOpen ? 'Close' : 'Open'}</span>
                </button>
                ${isOpen ? `
                    <div class="filter-menu" role="listbox">
                        ${options.map(option => `
                            <button
                                class="filter-option ${this._filters[field] === option.value ? 'is-active' : ''}"
                                data-select-option="${escapeHtml(field)}"
                                data-value="${escapeHtml(option.value)}"
                            >
                                <span>${escapeHtml(option.label)}</span>
                                ${this._filters[field] === option.value ? '<span class="meta">Active</span>' : ''}
                            </button>
                        `).join('')}
                    </div>
                ` : ''}
            </div>
        `;
    }

    _dateFieldHtml(field, label) {
        const isOpen = this._openMenu?.type === 'date' && this._openMenu?.field === field;
        const currentValue = this._filters[field];
        return `
            <div class="field">
                <label>${escapeHtml(label)}</label>
                <button class="filter-trigger" data-open-date="${escapeHtml(field)}" aria-expanded="${isOpen ? 'true' : 'false'}">
                    <span class="value">${escapeHtml(currentValue ? this._displayDateTime(currentValue) : 'No limit')}</span>
                    <span class="caret">${isOpen ? 'Close' : 'Open'}</span>
                </button>
                ${isOpen ? this._datePanelHtml(field) : ''}
            </div>
        `;
    }

    _datePanelHtml(field) {
        this._ensureDateDraft(field);
        const month = this._calendarViews[field];
        const draft = this._dateDrafts[field];
        const monthTitle = new Intl.DateTimeFormat(undefined, {
            month: 'long',
            year: 'numeric'
        }).format(month);
        const selectedKey = this._dateKey(draft);
        const monthGrid = this._calendarGrid(month);
        return `
            <div class="date-panel">
                <div class="date-panel-head">
                    <button class="calendar-nav" data-date-nav="-1" data-field="${escapeHtml(field)}" aria-label="Previous month">Prev</button>
                    <div class="date-panel-title">${escapeHtml(monthTitle)}</div>
                    <button class="calendar-nav" data-date-nav="1" data-field="${escapeHtml(field)}" aria-label="Next month">Next</button>
                </div>
                <div class="calendar-weekdays">
                    ${WEEKDAY_LABELS.map(day => `<span>${escapeHtml(day)}</span>`).join('')}
                </div>
                <div class="calendar-grid">
                    ${monthGrid.map(cell => `
                        <button
                            class="calendar-day ${cell.isOutside ? 'is-outside' : ''} ${cell.key === selectedKey ? 'is-active' : ''}"
                            data-date-day="${cell.key}"
                            data-field="${escapeHtml(field)}"
                        >
                            ${cell.date.getDate()}
                        </button>
                    `).join('')}
                </div>
                <div class="time-grid">
                    <div class="time-col">
                        <span>Hour</span>
                        <div class="time-list">
                            ${Array.from({ length: 24 }, (_, hour) => `
                                <button class="time-btn ${draft.getHours() === hour ? 'is-active' : ''}" data-time-hour="${hour}" data-field="${escapeHtml(field)}">
                                    ${String(hour).padStart(2, '0')}
                                </button>
                            `).join('')}
                        </div>
                    </div>
                    <div class="time-col">
                        <span>Minute</span>
                        <div class="time-list minutes">
                            ${MINUTE_OPTIONS.map(minute => `
                                <button class="time-btn ${draft.getMinutes() === minute ? 'is-active' : ''}" data-time-minute="${minute}" data-field="${escapeHtml(field)}">
                                    ${String(minute).padStart(2, '0')}
                                </button>
                            `).join('')}
                        </div>
                    </div>
                </div>
                <div class="date-panel-actions">
                    <div>
                        <button class="calendar-action is-danger" data-clear-date="${escapeHtml(field)}">${field === 'to' ? 'No Limit' : 'Clear'}</button>
                    </div>
                    <div>
                        <button class="calendar-action" data-cancel-date="${escapeHtml(field)}">Cancel</button>
                        <button class="calendar-action is-primary" data-apply-date="${escapeHtml(field)}">Apply</button>
                    </div>
                </div>
            </div>
        `;
    }

    _calendarGrid(month) {
        const firstDay = new Date(month.getFullYear(), month.getMonth(), 1);
        const start = new Date(firstDay);
        start.setDate(firstDay.getDate() - firstDay.getDay());
        return Array.from({ length: 42 }, (_, index) => {
            const date = new Date(start);
            date.setDate(start.getDate() + index);
            return {
                date,
                key: this._dateKey(date),
                isOutside: date.getMonth() !== month.getMonth(),
            };
        });
    }

    _dateKey(date) {
        return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    }

    _displayDateTime(value) {
        if (!value) return 'No limit';
        const date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        return new Intl.DateTimeFormat(undefined, {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            hour12: false,
        }).format(date);
    }

    _renderPager() {
        const start = this._records.length === 0 ? 0 : this._offset + 1;
        const end = this._offset + this._records.length;
        const context = this._contextQuery();
        const suffix = this._hasActiveFilters(context) ? ` (filtered${this._contextSummary(context)})` : '';
        this.refs.pageRange.textContent = `${start}-${end} of ${this._total} records${suffix}`;
        this.refs.prevBtn.disabled = this._offset <= 0;
        this.refs.nextBtn.disabled = end >= this._total;
    }

    _hasActiveFilters(context = this._contextQuery()) {
        return Boolean(
            this._filters.group ||
            this._filters.channel ||
            this._filters.model ||
            this._filters.status ||
            this._filters.preset !== 'today' ||
            !this._isSameMinute(this._filters.from, this._defaultFilters().from) ||
            this._filters.to ||
            context.keyId ||
            context.customerId
        );
    }

    _isSameMinute(a, b) {
        if (!a && !b) return true;
        if (!a || !b) return false;
        return this._toUtcIso(a) === this._toUtcIso(b);
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
            metaHtml: `<div class="hero-meta"><span class="hero-chip">${this._total} records</span><span class="hero-chip">${hit} cache hit</span><span class="hero-chip">${escapeHtml(this._timezoneLabel())}</span></div>`
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
            this.refs.tableWrap.innerHTML = `<div class="empty">${this._hasActiveFilters(context) ? '// NO RECORDS MATCH FILTERS' : '// NO USAGE RECORDS YET'}</div>`;
            return;
        }

        const headers = ['Model', 'Time', 'Group', 'Channel', 'In', 'Out', 'CR', 'CW', 'CP', 'Reason', 'In$', 'Out$', 'CW$', 'CR$', 'Total$', 'Hit', 'Status', 'Latency', 'Detail'];
        const rows = filtered.map(r => {
            const u = r.usage || {};
            const c = r.cost || {};
            const id = r.requestId || r.recordId || '';
            return `<tr>
                <td>${this._modelCell(r.model)}</td>
                <td>${escapeHtml(this._formatLocalTimestamp(r.createdAt))}</td>
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
                ${kv('Timestamp (Local)', this._formatLocalTimestamp(record.createdAt))}
                ${kv('Timestamp (UTC)', record.createdAt)}
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

    _formatLocalTimestamp(value) {
        if (!value) return '—';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        return new Intl.DateTimeFormat(undefined, {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hour12: false,
            timeZoneName: 'short'
        }).format(date);
    }

    _toUtcIso(value) {
        if (!value) return null;
        const date = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(date.getTime())) return null;
        return date.toISOString();
    }

    _toUtcExclusiveIso(value) {
        if (!value) return null;
        const date = value instanceof Date ? new Date(value) : new Date(value);
        if (Number.isNaN(date.getTime())) return null;
        date.setMinutes(date.getMinutes() + 1);
        return date.toISOString();
    }

    _timezoneLabel() {
        const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Local time';
        return `Timezone ${zone}`;
    }
}

customElements.define('ai-panel-usage', PanelUsage);
