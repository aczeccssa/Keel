import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { AiGatewayApi } from '../api/aiGatewayApi';
import { CustomersPanel } from './CustomersPanel';
import { DashboardPanel } from './DashboardPanel';
import { GroupsPanel } from './GroupsPanel';
import { KeysPanel } from './KeysPanel';
import { LoginPanel } from './LoginPanel';
import { PoolsPanel } from './PoolsPanel';
import { PricingPanel } from './PricingPanel';
import { ProvidersPanel } from './ProvidersPanel';
import { RateLimitsPanel } from './RateLimitsPanel';
import { RedemptionCodesPanel } from './RedemptionCodesPanel';
import { UsagePanel } from './UsagePanel';
import { UsersPanel } from './UsersPanel';

const api = {
  usageRecords: async () => ({ records: [] }),
  channels: async () => ({ channels: [] }),
  groups: async () => ({ groups: [] }),
  keys: async () => ({ keys: [] }),
  pools: async () => ({ chains: [] }),
  pricing: async () => ({ pricing: [] }),
  rateLimitRules: async () => ({ rules: [] }),
  users: async () => ({ users: [] }),
  customers: async () => ({ customers: [] }),
  redemptionCodes: async () => ({ codes: [] })
} as unknown as AiGatewayApi;

describe('AI Gateway panels', () => {
  it('renders login actions', () => {
    render(<LoginPanel onAuthenticated={() => undefined} />);
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
  });

  it.each([
    [DashboardPanel, 'Dashboard'],
    [UsagePanel, 'Usage'],
    [ProvidersPanel, 'Channels'],
    [GroupsPanel, 'Groups'],
    [KeysPanel, 'API Keys'],
    [PoolsPanel, 'Pools'],
    [PricingPanel, 'Pricing'],
    [RateLimitsPanel, 'Rate Limits'],
    [UsersPanel, 'Users'],
    [CustomersPanel, 'Customers'],
    [RedemptionCodesPanel, 'Redemption']
  ])('renders %s heading', async (Panel, heading) => {
    render(<Panel api={api} />);
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
    if (heading !== 'Dashboard') {
      expect(await screen.findByText(/No .* yet|No records yet/i)).toBeInTheDocument();
    }
  });
});
