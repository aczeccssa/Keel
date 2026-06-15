export interface NavTab {
  id: string;
  label: string;
  hint: string;
  section: string;
  icon: string;
}

export const AI_GATEWAY_TABS: readonly NavTab[] = [
  { id: 'dashboard', label: 'Dashboard', hint: 'Cost & usage overview', section: 'OVERVIEW', icon: 'dashboard' },
  { id: 'usage', label: 'Usage', hint: 'Per-request detail log', section: 'OVERVIEW', icon: 'receipt_long' },
  { id: 'channels', label: 'Channels', hint: 'Upstream providers', section: 'ROUTING', icon: 'hub' },
  { id: 'groups', label: 'Groups', hint: 'Routing pools', section: 'ROUTING', icon: 'groups' },
  { id: 'keys', label: 'API Keys', hint: 'Virtual tokens', section: 'BILLING', icon: 'vpn_key' },
  { id: 'pricing', label: 'Pricing', hint: 'Model rate cards', section: 'BILLING', icon: 'attach_money' },
  { id: 'customers', label: 'Customers', hint: 'End-customer directory', section: 'BILLING', icon: 'group' },
  { id: 'codes', label: 'Redemption', hint: 'Credit top-up codes', section: 'BILLING', icon: 'card_giftcard' },
  { id: 'ratelimits', label: 'Rate Limits', hint: 'Token bucket rules', section: 'SYSTEM', icon: 'tune' },
  { id: 'users', label: 'Users', hint: 'Account management', section: 'SYSTEM', icon: 'admin_panel_settings' }
] as const;

export type AiGatewayTabId = (typeof AI_GATEWAY_TABS)[number]['id'];

export function tabFromHash(hash = window.location.hash): AiGatewayTabId {
  const id = hash.replace(/^#/, '');
  return (AI_GATEWAY_TABS as readonly NavTab[]).some((tab) => tab.id === id) ? (id as AiGatewayTabId) : 'dashboard';
}

export function writeTabHash(tab: AiGatewayTabId): void {
  window.location.hash = tab;
}
