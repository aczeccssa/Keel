import { beforeEach, describe, expect, it, vi } from 'vitest';

const { requestJson, postJson, deleteJson } = vi.hoisted(() => ({
  requestJson: vi.fn(),
  postJson: vi.fn(),
  deleteJson: vi.fn(),
}));

vi.mock('../api.js', () => ({
  requestJson,
  postJson,
  deleteJson,
}));

// @ts-ignore legacy custom element module has no TypeScript declarations
import './shared/KeelHero.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './shared/KeelDataTable.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './PanelRateLimits.js';

describe('legacy rate limits panel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    requestJson.mockReset();
    postJson.mockReset();
    deleteJson.mockReset();
    window.confirm = vi.fn(() => true);
  });

  it('keeps runtime copy clear and renders inline delete failures', async () => {
    requestJson.mockImplementation(async (url: string) => {
      if (url.endsWith('/v1/rules')) {
        return {
          rules: [{
            ruleId: 'rule-1',
            name: 'Primary rule',
            dimension: 'IP',
            pathPattern: '/v1/*',
            capacity: 120,
            refillRatePerSec: 2,
            priority: 0,
            enabled: true,
          }],
        };
      }
      if (url.endsWith('/v1/snapshot')) {
        return {
          ruleCount: 1,
          bucketCount: 0,
          totalAllowed: 10,
          totalRejected: 1,
          topBuckets: [],
        };
      }
      throw new Error(`Unexpected request ${url}`);
    });
    deleteJson.mockRejectedValue(new Error('Rule delete failed'));

    const panel = document.createElement('ai-panel-rate-limits') as any;
    document.body.appendChild(panel);

    await panel.refresh();

    expect(panel.shadowRoot.textContent).toContain('Runtime Rules');
    expect(panel.shadowRoot.textContent).toContain('Currently Matched Traffic');

    const btn = panel.refs.rulesTable.shadowRoot.querySelector('[data-del-rule="rule-1"]');
    btn.click();
    await Promise.resolve();
    await Promise.resolve();

    expect(deleteJson).toHaveBeenCalledWith('/api/plugins/riskcontrol/v1/rules/rule-1');
    expect(panel.shadowRoot.textContent).toContain('Rule delete failed');
  });
});
