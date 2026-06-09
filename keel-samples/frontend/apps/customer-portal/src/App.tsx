import { useMemo, useState } from 'react';
import { AppShell, Card } from '@keel/sample-ui';
import { CustomerPortalApi } from './api/customerPortalApi';
import { clearCustomerAuth, loadCustomerAuth } from './state/customerAuth';
import { CUSTOMER_TABS, tabFromHash, writeTabHash, type CustomerTabId } from './state/navigation';

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
    return <Card className="customer-login-card"><h1>Customer Portal</h1><p>Login panel will be ported in the panel parity task.</p></Card>;
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
      <Card>
        <h1>{CUSTOMER_TABS.find((tab) => tab.id === activeTab)?.label}</h1>
        <p data-testid="customer-active-tab">{activeTab}</p>
      </Card>
    </AppShell>
  );
}
