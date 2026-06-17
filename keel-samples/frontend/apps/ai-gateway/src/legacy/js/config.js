export const API = {
    account: '/api/plugins/account',
    token: '/api/plugins/token',
    airelay: '/api/plugins/airelay',
    riskcontrol: '/api/plugins/riskcontrol',
};

export const TABS = [
    { id: 'overview',     label: 'Overview',        icon: 'dashboard', hint: 'Cost & usage overview',    section: 'OVERVIEW' },
    { id: 'dashboard',    label: 'Dashboard',       icon: 'speed',     hint: 'Operations metrics',       section: 'OVERVIEW' },
    { id: 'usage',        label: 'Usage',           icon: 'usage',     hint: 'Per-request detail log',   section: 'OVERVIEW' },
    { id: 'availability', label: 'Availability',    icon: 'dns',       hint: 'Channel reliability',      section: 'ROUTING' },
    { id: 'channels',     label: 'Channels',        icon: 'dns',       hint: 'Upstream providers',       section: 'ROUTING' },
    { id: 'groups',       label: 'Groups',          icon: 'hub',       hint: 'Routing pools',            section: 'ROUTING' },
    { id: 'keys',         label: 'API Keys',        icon: 'key',       hint: 'Virtual tokens',           section: 'BILLING' },
    { id: 'pricing',      label: 'Pricing',         icon: 'pricing',   hint: 'Model rate cards',         section: 'BILLING', badge: 'customers' },
    { id: 'customers',    label: 'Customers',       icon: 'group',     hint: 'End-customer directory',   section: 'BILLING', badge: 'customers' },
    { id: 'codes',        label: 'Redemption',      icon: 'gift',      hint: 'Credit top-up codes',      section: 'BILLING' },
    { id: 'ratelimits',   label: 'Rate Limits',     icon: 'speed',     hint: 'Token bucket rules',       section: 'SYSTEM' },
    { id: 'users',        label: 'Users',           icon: 'group',     hint: 'Account management',       section: 'SYSTEM' },
];

export const DEMO_MODELS = [
    'gpt-4o-mini', 'gpt-4o', 'gpt-5',
    'claude-sonnet-4-20250514',
    'gpt-chat-only', 'gpt-responses-only', 'claude-only',
];
