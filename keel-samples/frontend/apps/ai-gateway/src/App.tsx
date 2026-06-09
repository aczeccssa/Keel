import { useMemo, useState } from 'react';
import { AppShell, Card } from '@keel/sample-ui';
import { AiGatewayApi } from './api/aiGatewayApi';
import { clearAdminAuth, loadAdminAuth } from './state/adminAuth';
import { AI_GATEWAY_TABS, tabFromHash, writeTabHash, type AiGatewayTabId } from './state/navigation';

export function App() {
  const [auth, setAuth] = useState(loadAdminAuth);
  const [activeTab, setActiveTab] = useState<AiGatewayTabId>(tabFromHash());
  const api = useMemo(() => new AiGatewayApi(auth.accessToken), [auth.accessToken]);

  const selectTab = (tab: string) => {
    const next = tab as AiGatewayTabId;
    setActiveTab(next);
    writeTabHash(next);
  };

  const logout = () => setAuth(clearAdminAuth());

  if (!auth.accessToken) {
    return <Card className="admin-login-card"><h1>AI Relay Console</h1><p>Login panel will be ported in the panel parity task.</p></Card>;
  }

  return (
    <AppShell
      productLabel="Keel AI Relay"
      tabs={[...AI_GATEWAY_TABS]}
      activeTab={activeTab}
      onSelectTab={selectTab}
      userLabel={auth.email ?? 'admin'}
      onLogout={logout}
    >
      <Card>
        <h1>{AI_GATEWAY_TABS.find((tab) => tab.id === activeTab)?.label}</h1>
        <p data-testid="ai-gateway-active-tab">{activeTab}</p>
      </Card>
    </AppShell>
  );
}
