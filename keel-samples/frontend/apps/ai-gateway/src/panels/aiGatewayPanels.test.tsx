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
import { PlaygroundPanel } from './PlaygroundPanel';
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
  ,
  createChannel: async () => ({}),
  testChannel: async () => ({}),
  createGroup: async () => ({}),
  attachGroupMembership: async () => ({}),
  upsertPricing: async () => ({}),
  createRateLimitRule: async () => ({}),
  createRedemptionCode: async () => ({}),
  createApiKey: async () => ({}),
  chatCompletions: async () => ({ id: 'chatcmpl_test' }),
  responses: async () => ({ id: 'resp_test' }),
  messages: async () => ({ id: 'msg_test' })
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

  it('renders provider management actions', async () => {
    const apiWithChannel = {
      ...api,
      channels: async () => ({ channels: [{ channelId: 'ch_1', name: 'Primary', protocol: 'OPENAI_CHAT', baseUrl: 'mock://provider' }] })
    } as unknown as AiGatewayApi;
    render(<ProvidersPanel api={apiWithChannel} />);
    expect(await screen.findByRole('heading', { name: /Channels/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Add channel/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Test/i })).toBeInTheDocument();
  });

  it('renders group membership actions', async () => {
    render(<GroupsPanel api={api} />);
    expect(await screen.findByRole('heading', { name: /Groups/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New group/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Attach/i })).toBeInTheDocument();
  });

  it('renders pricing and risk controls', async () => {
    render(<PricingPanel api={api} />);
    expect(await screen.findByRole('button', { name: /Add rate/i })).toBeInTheDocument();
    render(<RateLimitsPanel api={api} />);
    expect(await screen.findByRole('button', { name: /New rule/i })).toBeInTheDocument();
  });

  it('renders playground relay controls', () => {
    render(<PlaygroundPanel api={api} />);
    expect(screen.getByRole('heading', { name: /Playground/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Send/i })).toBeInTheDocument();
  });
});
