import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
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

  it('shows rolling pool metrics and the latest request trace', async () => {
    const poolApi = {
      groups: vi.fn(async () => ({ groups: [{ groupId: 'default' }] })),
      groupPools: vi.fn(async () => ({
        pools: [{
          groupId: 'default', aliasOrModel: 'gpt-5', routingPolicy: 'POOL_BALANCE', priority: 100,
          metrics1m: { selectedRequests: 12, successRequests: 10, failedRequests: 2, errorRate: 0.1667, p95LatencyMs: 420 },
          channels: [{
            channelId: 'ch-a', channelName: 'Primary', effectiveStatus: 'HEALTHY', weight: 100,
            currentConcurrency: 1, maxConcurrency: 10, trafficShare1m: 0.67, expectedShare: 0.67, shareDeviation: 0,
            metrics1m: { selectedRequests: 8, successRequests: 7, failedRequests: 1, errorRate: 0.125, p95LatencyMs: 400 }
          }]
        }]
      })),
      explainGroupPool: vi.fn(async () => ({
        selectedChannelId: 'ch-a', routingPolicy: 'POOL_BALANCE',
        requestTrace: { requestId: 'req-42', outcome: 'SUCCESS', failoverCount: 1, attempts: [
          { channelId: 'ch-b', outcome: 'FAILED', status: 502, reason: 'bad gateway' },
          { channelId: 'ch-a', outcome: 'SUCCESS', status: 200 }
        ] }
      }))
    } as unknown as AiGatewayApi;

    render(<PoolsPanel api={poolApi} />);

    expect(await screen.findByText('12 req / 1m')).toBeInTheDocument();
    expect(screen.getByText('p95 420 ms')).toBeInTheDocument();
    expect(screen.getByText(/share 67\.0% \/ expected 67\.0%/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Explain' }));

    await waitFor(() => expect(poolApi.explainGroupPool).toHaveBeenCalledWith('default', 'gpt-5', {}));
    expect(await screen.findByText('req-42')).toBeInTheDocument();
    expect(screen.getByText(/ch-b · FAILED · 502/)).toBeInTheDocument();
  });
});
