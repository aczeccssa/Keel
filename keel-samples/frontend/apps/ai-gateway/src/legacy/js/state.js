export const state = {
    activeTab: 'overview',
    tabQuery: {},
    loggedIn: false,
    user: null,
    accessToken: null,
    refreshToken: null,
    autoRefreshMs: 0,
    _refreshTimer: null,
};

const LEGACY_TAB_ALIASES = {
    providers: 'channels',
    pools: 'groups',
};

function normalizeTabId(tabId) {
    return LEGACY_TAB_ALIASES[tabId] || tabId;
}

function normalizeTabQuery(query = {}) {
    return Object.fromEntries(
        Object.entries(query).filter(([, value]) => value != null && String(value).trim() !== '')
    );
}

export function buildHash(tabId, query = {}) {
    const normalized = normalizeTabId(tabId);
    const params = new URLSearchParams(normalizeTabQuery(query));
    const suffix = params.toString();
    return `#${normalized}${suffix ? `?${suffix}` : ''}`;
}

export function parseHashState(hash = window.location.hash) {
    const raw = String(hash || '').replace(/^#/, '');
    if (!raw) {
        return { activeTab: state.activeTab, tabQuery: {} };
    }
    const [tabId, rawQuery = ''] = raw.split('?', 2);
    return {
        activeTab: normalizeTabId(tabId),
        tabQuery: Object.fromEntries(new URLSearchParams(rawQuery)),
    };
}

export function setTab(tabId, query = {}) {
    const normalized = normalizeTabId(tabId);
    const normalizedQuery = normalizeTabQuery(query);
    state.activeTab = normalized;
    state.tabQuery = normalizedQuery;
    window.location.hash = buildHash(normalized, normalizedQuery);
}

export function hydrateHash() {
    const next = parseHashState(window.location.hash);
    state.activeTab = next.activeTab;
    state.tabQuery = next.tabQuery;
}
