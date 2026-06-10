import { afterEach, describe, expect, it, vi } from 'vitest';
import { CustomerPortalApi } from './customerPortalApi';

describe('CustomerPortalApi', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('logs in through the existing customer auth endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ accessToken: 'token', refreshToken: 'refresh', customer: { email: 'demo@example.com' } }), { status: 200 })));

    const api = new CustomerPortalApi();
    await api.login({ email: 'demo@example.com', password: 'demo123' });

    expect(fetch).toHaveBeenCalledWith('/api/plugins/customer-portal/v1/customer/auth/login', expect.objectContaining({ method: 'POST' }));
  });

  it('registers with the displayName field expected by the backend', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ accessToken: 'token', refreshToken: 'refresh' }), { status: 200 })));

    const api = new CustomerPortalApi();
    await api.register({ email: 'demo@example.com', password: 'demo123', displayName: 'Demo' });

    expect(fetch).toHaveBeenCalledWith('/api/plugins/customer-portal/v1/customer/auth/register', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ email: 'demo@example.com', password: 'demo123', displayName: 'Demo' })
    }));
  });

  it('creates keys with the routingGroupId field expected by the backend', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ key: { keyId: 'key_1', name: 'Default' }, rawKey: 'sk-keel-cust-test' }), { status: 200 })));

    const api = new CustomerPortalApi('abc');
    await api.createKey({ name: 'Default', routingGroupId: 'default', monthlyBudgetCredits: 100 });

    expect(fetch).toHaveBeenCalledWith('/api/plugins/customer-portal/v1/customer/keys', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ Authorization: 'Bearer abc' }),
      body: JSON.stringify({ name: 'Default', routingGroupId: 'default', monthlyBudgetCredits: 100 })
    }));
  });

  it('loads AI Relay groups for key routing choices', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ groups: [] }), { status: 200 })));

    const api = new CustomerPortalApi('abc');
    await api.listRelayGroups();

    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/groups', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer abc' })
    }));
  });
});
