import { describe, expect, it, beforeEach } from 'vitest';
// @ts-ignore legacy module has no TypeScript declarations
import { buildHash, hydrateHash, parseHashState, setTab, state } from './state.js';

describe('legacy hash state', () => {
  beforeEach(() => {
    state.activeTab = 'overview';
    state.tabQuery = {};
    window.location.hash = '';
  });

  it('parses a tab hash with query parameters', () => {
    expect(parseHashState('#usage?keyId=key-1&customerId=cust-1')).toEqual({
      activeTab: 'usage',
      tabQuery: {
        keyId: 'key-1',
        customerId: 'cust-1',
      },
    });
  });

  it('writes query parameters when switching tabs', () => {
    setTab('usage', { keyId: 'key-9', customerId: 'cust-9' });

    expect(state.activeTab).toBe('usage');
    expect(state.tabQuery).toEqual({ keyId: 'key-9', customerId: 'cust-9' });
    expect(window.location.hash).toBe(buildHash('usage', { keyId: 'key-9', customerId: 'cust-9' }));
  });

  it('hydrates the active tab and query from location.hash', () => {
    window.location.hash = '#customers?customerId=cust-77';

    hydrateHash();

    expect(state.activeTab).toBe('customers');
    expect(state.tabQuery).toEqual({ customerId: 'cust-77' });
  });
});
