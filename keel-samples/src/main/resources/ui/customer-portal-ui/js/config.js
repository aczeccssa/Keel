export const API = {
    customerPortal: '/api/plugins/customer-portal',
};

export const TABS = [
    { id: 'home',     label: 'Dashboard',     icon: 'dashboard', hint: 'Balance & overview',     section: 'OVERVIEW' },
    { id: 'keys',     label: 'API Keys',      icon: 'key',       hint: 'Manage your keys',       section: 'ACCOUNT' },
    { id: 'billing',  label: 'Credits',       icon: 'receipt',   hint: 'Ledger & redeem',        section: 'ACCOUNT' },
    { id: 'pricing',  label: 'Rates',         icon: 'pricing',   hint: 'Model pricing',          section: 'ACCOUNT' },
];

export const ICONS = {
    dashboard: '<path d="M3 3v18h18"/><path d="m7 14 3-3 3 2 4-6"/>',
    key: '<path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>',
    receipt: '<path d="M4 2v20l3-2 3 2 3-2 3 2 3-2 3 2V2l-3 2-3-2-3 2-3-2-3 2-3-2z"/><path d="M8 10h8"/><path d="M8 14h4"/>',
    pricing: '<line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>',
};
