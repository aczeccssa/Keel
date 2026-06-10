import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AiGatewayApi } from '../api/aiGatewayApi';
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

const api = new AiGatewayApi('test-token');

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
  ])('renders %s heading', (Panel, heading) => {
    render(<Panel api={api} />);
    expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument();
  });
});
