import { useMemo, useState } from 'react';
import { AppShell } from '@keel/sample-ui';
import { CustomerPortalApi } from './api/customerPortalApi';
import { clearCustomerAuth, loadCustomerAuth, saveCustomerAuth } from './state/customerAuth';
import { CUSTOMER_TABS, tabFromHash, writeTabHash, type CustomerTabId } from './state/navigation';

import { BillingPanel } from './panels/BillingPanel';
import { DashboardPanel } from './panels/DashboardPanel';
import { KeysPanel } from './panels/KeysPanel';
import { LoginPanel } from './panels/LoginPanel';
import { PricingPanel } from './panels/PricingPanel';

function renderPanel(activeTab: CustomerTabId, api: CustomerPortalApi) {
  switch (activeTab) {
    case 'keys': return <KeysPanel api={api} />;
    case 'billing': return <BillingPanel api={api} />;
    case 'pricing': return <PricingPanel api={api} />;
    case 'home':
    default: return <DashboardPanel api={api} />;
  }
}

export function App() {
  const [auth, setAuth] = useState(loadCustomerAuth);
  const [activeTab, setActiveTab] = useState<CustomerTabId>(tabFromHash());
  const api = useMemo(() => new CustomerPortalApi(auth.accessToken), [auth.accessToken]);

  const selectTab = (tab: string) => {
    const next = tab as CustomerTabId;
    setActiveTab(next);
    writeTabHash(next);
  };

  const logout = () => setAuth(clearCustomerAuth());

  if (!auth.accessToken) {
    return <LoginPanel onAuthenticated={(response) => setAuth(saveCustomerAuth(response))} />;
  }

  return (
    <AppShell
      productLabel="Keel Customer Portal"
      tabs={[...CUSTOMER_TABS]}
      activeTab={activeTab}
      onSelectTab={selectTab}
      userLabel={auth.email ?? 'customer'}
      onLogout={logout}
    >
      {renderPanel(activeTab, api)}
    </AppShell>
  );
}
