export interface NavTab {
  id: string;
  label: string;
  hint: string;
  section: string;
  icon: string;
}

export const CUSTOMER_TABS: readonly NavTab[] = [
  { id: 'home', label: 'Dashboard', hint: 'Balance & overview', section: 'OVERVIEW', icon: 'dashboard' },
  { id: 'keys', label: 'API Keys', hint: 'Manage your keys', section: 'ACCOUNT', icon: 'vpn_key' },
  { id: 'billing', label: 'Credits', hint: 'Ledger & redeem', section: 'ACCOUNT', icon: 'savings' },
  { id: 'pricing', label: 'Rates', hint: 'Model pricing', section: 'ACCOUNT', icon: 'attach_money' }
] as const;

export type CustomerTabId = (typeof CUSTOMER_TABS)[number]['id'];

export function tabFromHash(hash = window.location.hash): CustomerTabId {
  const id = hash.replace(/^#/, '');
  return (CUSTOMER_TABS as readonly NavTab[]).some((tab) => tab.id === id) ? (id as CustomerTabId) : 'home';
}

export function writeTabHash(tab: CustomerTabId): void {
  window.location.hash = tab;
}
