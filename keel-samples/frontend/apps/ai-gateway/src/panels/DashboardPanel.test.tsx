import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DashboardPanel } from './DashboardPanel';

describe('DashboardPanel', () => {
  it('renders dashboard stats, distributions, and time window selector', async () => {
    const api = {
      usageGlobal: async () => ({ totalRequests: 2, costLast24hUsd: 1.23 }),
      dashboardStats: async () => ({
        overview: {
          totalRequests: 2,
          successRate: 0.5,
          totalCostUsd: 1.23,
          totalTokens: 1000,
          avgLatencyMs: 500,
          p50LatencyMs: 400,
          p95LatencyMs: 900,
          p99LatencyMs: 1200,
          cacheHitRate: 0.25,
          healthyChannels: 1,
          cooldownChannels: 1,
          disabledChannels: 0
        },
        trends: {
          requestsByHour: [{ timestamp: 1780000000000, requests: 2, successRate: 0.5 }],
          tokensByHour: [{ timestamp: 1780000000000, promptTokens: 100, completionTokens: 50, cacheWriteTokens: 10, cacheReadTokens: 40, costUsd: 1.23 }],
          latencyByHour: [{ timestamp: 1780000000000, p50: 400, p95: 900, p99: 1200 }]
        },
        distributions: {
          modelDistribution: [{ model: 'claude-test', requests: 2, percentage: 100, totalCostUsd: 1.23 }],
          channelDistribution: [{ channelId: 'ch-1', channelName: 'primary', requests: 2, successRate: 0.5, avgLatencyMs: 500 }],
          groupDistribution: [{ groupId: 'default', groupName: 'default', requests: 2, totalCostUsd: 1.23, topModels: ['claude-test'] }],
          errorDistribution: [{ errorType: '500', count: 1, percentage: 50 }]
        }
      }),
      usageRecords: async () => ({
        records: [
          { recordId: 'r1', model: 'claude-test', createdAt: '2026-06-17T10:00:00', status: 200, latencyMs: 400, cost: { totalCostUsd: 1.0 } },
          { recordId: 'r2', model: 'claude-test', createdAt: '2026-06-17T10:01:00', status: 500, latencyMs: 900, cost: { totalCostUsd: 0.23 } }
        ]
      })
    };

    render(<DashboardPanel api={api as never} />);

    await screen.findByText('Success Rate');
    expect(screen.getByText('50.0%')).toBeInTheDocument();
    expect(screen.getByText('Time window:')).toBeInTheDocument();
    expect(screen.getByText('Time-series trends')).toBeInTheDocument();
    expect(screen.getByText('Distribution analysis')).toBeInTheDocument();
    expect(screen.getByText('Real-time monitor')).toBeInTheDocument();
  });
});
