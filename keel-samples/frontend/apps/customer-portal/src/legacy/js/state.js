export const state = {
    activeTab: 'home',
    loggedIn: false,
    customer: null,
    accessToken: null,
    refreshToken: null,
};

export function setTab(tabId) {
    state.activeTab = tabId;
    window.location.hash = tabId;
}

export function hydrateHash() {
    const hash = window.location.hash.slice(1);
    if (hash && ['home', 'keys', 'billing', 'pricing'].includes(hash)) state.activeTab = hash;
}
