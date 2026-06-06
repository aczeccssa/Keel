export const state = {
    activeTab: 'dashboard',
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

export function setTab(tabId) {
    const normalized = normalizeTabId(tabId);
    state.activeTab = normalized;
    window.location.hash = normalized;
}

export function hydrateHash() {
    const hash = window.location.hash.slice(1);
    if (hash) state.activeTab = normalizeTabId(hash);
}
