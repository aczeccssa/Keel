export const API = {
    account: '/api/plugins/account',
    token: '/api/plugins/token',
    airelay: '/api/plugins/airelay',
    riskcontrol: '/api/plugins/riskcontrol',
};

export const TABS = [
    { id: 'dashboard', label: 'Dashboard', icon: 'dashboard', hint: 'Cost & usage overview' },
    { id: 'keys', label: 'API Keys', icon: 'key', hint: 'Manage virtual keys' },
    { id: 'pools', label: 'Pool Chains', icon: 'dns', hint: 'Upstream health & config' },
    { id: 'ratelimits', label: 'Rate Limits', icon: 'speed', hint: 'Token bucket rules' },
    { id: 'users', label: 'Users & Groups', icon: 'group', hint: 'Account management' },
    { id: 'playground', label: 'Playground', icon: 'terminal', hint: 'Test AI requests' },
];

export const DEMO_MODELS = [
    'gpt-4o-mini', 'gpt-4o', 'gpt-5',
    'claude-sonnet-4-20250514',
    'gpt-chat-only', 'gpt-responses-only', 'claude-only',
];
