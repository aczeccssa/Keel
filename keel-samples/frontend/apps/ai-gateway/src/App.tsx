import { useMemo, useState } from 'react';
import { AppShell } from '@keel/sample-ui';
import { AiGatewayApi } from './api/aiGatewayApi';
import { clearAdminAuth, loadAdminAuth, saveAdminAuth } from './state/adminAuth';
import { AI_GATEWAY_TABS, tabFromHash, writeTabHash, type AiGatewayTabId } from './state/navigation';

import { CustomersPanel } from './panels/CustomersPanel';
import { DashboardPanel } from './panels/DashboardPanel';
import { GroupsPanel } from './panels/GroupsPanel';
import { KeysPanel } from './panels/KeysPanel';
import { LandingPage } from './panels/LandingPage';
import { LoginPanel } from './panels/LoginPanel';
import { PoolsPanel } from './panels/PoolsPanel';
import { PricingPanel } from './panels/PricingPanel';
import { ProvidersPanel } from './panels/ProvidersPanel';
import { RateLimitsPanel } from './panels/RateLimitsPanel';
import { RedemptionCodesPanel } from './panels/RedemptionCodesPanel';
import { UsagePanel } from './panels/UsagePanel';
import { UsersPanel } from './panels/UsersPanel';

function renderPanel(activeTab: AiGatewayTabId, api: AiGatewayApi) {
  switch (activeTab) {
    case 'usage': return <UsagePanel api={api} />;
    case 'channels': return <ProvidersPanel api={api} />;
    case 'groups': return <GroupsPanel api={api} />;
    case 'keys': return <KeysPanel api={api} />;
    case 'pricing': return <PricingPanel api={api} />;
    case 'ratelimits': return <RateLimitsPanel api={api} />;
    case 'users': return <UsersPanel api={api} />;
    case 'customers': return <CustomersPanel api={api} />;
    case 'codes': return <RedemptionCodesPanel api={api} />;
    case 'dashboard':
    default: return <DashboardPanel api={api} />;
  }
}

export function App() {
  const [auth, setAuth] = useState(loadAdminAuth);
  const [authView, setAuthView] = useState<'landing' | 'login' | 'register'>('landing');
  const [activeTab, setActiveTab] = useState<AiGatewayTabId>(tabFromHash());
  const api = useMemo(() => new AiGatewayApi(auth.accessToken), [auth.accessToken]);

  const selectTab = (tab: string) => {
    const next = tab as AiGatewayTabId;
    setActiveTab(next);
    writeTabHash(next);
  };

  const logout = () => setAuth(clearAdminAuth());

  if (!auth.accessToken) {
    if (authView === 'landing') {
      return <LandingPage onSignIn={() => setAuthView('login')} onRegister={() => setAuthView('register')} />;
    }
    return (
      <LoginPanel
        initialMode={authView}
        onBack={() => setAuthView('landing')}
        onAuthenticated={(response) => setAuth(saveAdminAuth(response))}
      />
    );
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
      {renderPanel(activeTab, api)}
    </AppShell>
  );
}
