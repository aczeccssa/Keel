export const state = {
    activeTab: 'dashboard',
    loggedIn: false,
    user: null,
    accessToken: null,
    refreshToken: null,
    autoRefreshMs: 0,
    _refreshTimer: null,
};

export function setTab(tabId) {
    state.activeTab = tabId;
    window.location.hash = tabId;
}

export function hydrateHash() {
    const hash = window.location.hash.slice(1);
    if (hash) state.activeTab = hash;
}
