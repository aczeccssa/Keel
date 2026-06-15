import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { AppShell } from '@keel/sample-ui';
import { AiGatewayApi } from './api/aiGatewayApi';
import { clearAdminAuth, loadAdminAuth, saveAdminAuth } from './state/adminAuth';
import { AI_GATEWAY_TABS, tabFromHash, writeTabHash, type AiGatewayTabId } from './state/navigation';

import { CustomersPanel } from './panels/CustomersPanel';
import { DashboardPanel } from './panels/DashboardPanel';
import { GroupsPanel } from './panels/GroupsPanel';
import { KeysPanel } from './panels/KeysPanel';
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

function RefreshGate({ nonce, children }: { nonce: boolean; children: ReactNode }) {
  // Remount children when refresh starts, so panels re-fetch on mount.
  return <div key={String(nonce)}>{children}</div>;
}

export function App() {
  const [auth, setAuth] = useState(loadAdminAuth);
  const [activeTab, setActiveTab] = useState<AiGatewayTabId>(tabFromHash());
  const [navCounts, setNavCounts] = useState<Record<string, number>>({});
  const [refreshing, setRefreshing] = useState(false);
  const api = useMemo(() => new AiGatewayApi(auth.accessToken), [auth.accessToken]);

  const refreshNavCounts = useCallback(() => {
    if (!auth.accessToken) return;
    api
      .navCounts()
      .then((data) => {
        const d = data as {
          customers?: number;
          redemptionCodes?: number;
          apiKeys?: number;
          groups?: number;
          users?: number;
        };
        setNavCounts({
          customers: d.customers ?? 0,
          codes: d.redemptionCodes ?? 0,
          keys: d.apiKeys ?? 0,
          groups: d.groups ?? 0,
          users: d.users ?? 0
        });
      })
      .catch(() => {
        // nav counts are non-critical; the API may not be reachable in offline mode
      });
  }, [api, auth.accessToken]);

  useEffect(() => {
    if (!auth.accessToken) return;
    refreshNavCounts();
    const t = setInterval(refreshNavCounts, 30_000);
    return () => clearInterval(t);
  }, [refreshNavCounts, auth.accessToken]);

  const selectTab = (tab: string) => {
    const next = tab as AiGatewayTabId;
    setActiveTab(next);
    writeTabHash(next);
  };

  const refresh = useCallback(() => {
    if (refreshing) return;
    setRefreshing(true);
    refreshNavCounts();
    setTimeout(() => setRefreshing(false), 600);
  }, [refreshNavCounts, refreshing]);

  const logout = () => setAuth(clearAdminAuth());

  if (!auth.accessToken) {
    return <LoginPanel onAuthenticated={(response) => setAuth(saveAdminAuth(response))} />;
  }

  return (
    <AppShell
      productLabel="Keel AI Relay"
      tabs={[...AI_GATEWAY_TABS]}
      activeTab={activeTab}
      onSelectTab={selectTab}
      userLabel={auth.email ?? 'admin'}
      userEmail={auth.email ?? 'admin'}
      onLogout={logout}
      onRefresh={refresh}
      isRefreshing={refreshing}
      isLive
      navCounts={navCounts}
    >
      <RefreshGate nonce={refreshing}>{renderPanel(activeTab, api)}</RefreshGate>
    </AppShell>
  );
}
