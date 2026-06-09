export const CUSTOMER_TABS = [
  { id: 'home', label: 'Dashboard', hint: 'Balance & overview', section: 'OVERVIEW' },
  { id: 'keys', label: 'API Keys', hint: 'Manage your keys', section: 'ACCOUNT' },
  { id: 'billing', label: 'Credits', hint: 'Ledger & redeem', section: 'ACCOUNT' },
  { id: 'pricing', label: 'Rates', hint: 'Model pricing', section: 'ACCOUNT' }
] as const;

export type CustomerTabId = typeof CUSTOMER_TABS[number]['id'];

export function tabFromHash(hash = window.location.hash): CustomerTabId {
  const id = hash.replace(/^#/, '');
  return CUSTOMER_TABS.some((tab) => tab.id === id) ? id as CustomerTabId : 'home';
}

export function writeTabHash(tab: CustomerTabId): void {
  window.location.hash = tab;
}
