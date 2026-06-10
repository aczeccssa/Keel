export const AI_GATEWAY_TABS = [
  { id: 'dashboard', label: 'Dashboard', hint: 'Cost & usage overview', section: 'OVERVIEW' },
  { id: 'usage', label: 'Usage', hint: 'Per-request detail log', section: 'OVERVIEW' },
  { id: 'channels', label: 'Channels', hint: 'Upstream providers', section: 'ROUTING' },
  { id: 'groups', label: 'Groups', hint: 'Routing pools', section: 'ROUTING' },
  { id: 'playground', label: 'Playground', hint: 'Relay request probe', section: 'ROUTING' },
  { id: 'keys', label: 'API Keys', hint: 'Virtual tokens', section: 'BILLING' },
  { id: 'pricing', label: 'Pricing', hint: 'Model rate cards', section: 'BILLING' },
  { id: 'customers', label: 'Customers', hint: 'End-customer directory', section: 'BILLING' },
  { id: 'codes', label: 'Redemption', hint: 'Credit top-up codes', section: 'BILLING' },
  { id: 'ratelimits', label: 'Rate Limits', hint: 'Token bucket rules', section: 'SYSTEM' },
  { id: 'users', label: 'Users', hint: 'Account management', section: 'SYSTEM' }
] as const;

export type AiGatewayTabId = typeof AI_GATEWAY_TABS[number]['id'];

export function tabFromHash(hash = window.location.hash): AiGatewayTabId {
  const id = hash.replace(/^#/, '');
  return AI_GATEWAY_TABS.some((tab) => tab.id === id) ? id as AiGatewayTabId : 'dashboard';
}

export function writeTabHash(tab: AiGatewayTabId): void {
  window.location.hash = tab;
}
