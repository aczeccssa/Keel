import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RateLimitsPanel } from './RateLimitsPanel';

describe('RateLimitsPanel', () => {
  it('shows rejection activity from the snapshot monitor', async () => {
    const api = {
      rateLimitRules: async () => ({
        rules: [{
          ruleId: 'ip-default',
          scope: 'IP',
          limit: 120,
          refillPerSec: 2
        }]
      }),
      rateLimitSnapshot: async () => ({
        totalRejected: 7,
        totalAllowed: 42,
        bucketCount: 3,
        ruleCount: 1,
        topBuckets: [],
        recentRejections: [{
          ruleId: 'ip-default',
          dimension: 'IP',
          value: '127.0.0.1',
          reason: 'rate_limited',
          retryAfterSeconds: 1
        }]
      })
    };

    render(<RateLimitsPanel api={api as never} />);

    await screen.findByText('127.0.0.1');
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.getByText('rate_limited')).toBeInTheDocument();
  });
});
