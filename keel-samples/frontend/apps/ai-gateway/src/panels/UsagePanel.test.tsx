import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { UsagePanel } from './UsagePanel';

describe('UsagePanel', () => {
  it('surfaces upstream failure detail for operators', async () => {
    const api = {
      usageRecords: async () => ({
        records: [{
          recordId: 'use-1',
          model: 'claude-opus-4-6 -> gpt-5.4',
          createdAt: '2026-06-15T14:43:00',
          status: 503,
          outcome: 'ERROR',
          errorCode: 'pool_exhausted',
          errorDetail: 'target channel ch-c1559f8d0ffa497f8e22 saturated at 10/10',
          upstreamKeyId: 'ch-c1559f8d0ffa497f8e22',
          poolLevelId: 'default',
          streamed: true,
          failoverCount: 0,
          usage: { promptTokens: 0, completionTokens: 0, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 },
          cost: { totalCostUsd: 0, inputCostUsd: 0, outputCostUsd: 0 }
        }]
      })
    };

    render(<UsagePanel api={api as never} />);

    await screen.findByText('pool_exhausted');
    expect(screen.getByText('target channel ch-c1559f8d0ffa497f8e22 saturated at 10/10')).toBeInTheDocument();
    expect(screen.getAllByText('ch-c1559f8d0ffa497f8e22').length).toBeGreaterThan(0);

    await userEvent.click(screen.getByRole('button', { name: 'View' }));
    expect(screen.getByRole('dialog', { name: 'Usage detail' })).toBeInTheDocument();
    expect(screen.getByText('Request detail')).toBeInTheDocument();
    expect(screen.getByText('Tokens')).toBeInTheDocument();
  });
});
