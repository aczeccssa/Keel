import { describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelCustomers } from './PanelCustomers.js';

describe('legacy customers panel', () => {
  it('renders required customer usage diagnostics', () => {
    const panel = new PanelCustomers();
    const html = panel._usageTableHtml([
      {
        createdAt: '2026-06-20T00:00:00Z',
        model: 'gpt-4o-mini',
        status: 200,
        keyId: 'ckey-1',
        requestId: 'req-1',
        inputTokens: 10,
        outputTokens: 20,
        creditCost: 30,
        usdMicrosCost: 125000,
      },
    ]);

    expect(html).toContain('<th>Status</th>');
    expect(html).toContain('<th>Key</th>');
    expect(html).toContain('<th>Cost</th>');
    expect(html).toContain('ckey-1');
    expect(html).toContain('$0.1250');
  });

  it('builds pagination state from cursor responses', () => {
    const panel = new PanelCustomers();
    const state = panel._pageState({
      records: [{ requestId: 'req-1' }, { requestId: 'req-2' }],
      total: 5,
      nextCursor: 'cursor-2',
    }, {
      cursor: null,
      previous: [],
      pageSize: 2,
    });

    expect(state.rangeLabel).toBe('1-2 of 5');
    expect(state.hasPrev).toBe(false);
    expect(state.hasNext).toBe(true);
    expect(state.nextCursor).toBe('cursor-2');
  });

  it('builds usage navigation queries from the selected customer', () => {
    const panel = new PanelCustomers();
    panel._selected = { customerId: 'cust-1' };

    expect(panel._usageNavigationQuery('ckey-1')).toEqual({
      customerId: 'cust-1',
      keyId: 'ckey-1',
    });
  });
});
