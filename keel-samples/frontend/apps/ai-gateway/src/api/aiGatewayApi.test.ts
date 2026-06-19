import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiGatewayApi } from './aiGatewayApi';

describe('AiGatewayApi', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('logs in through the existing account endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ accessToken: 'token', refreshToken: 'refresh' }), { status: 200 })));

    const api = new AiGatewayApi();
    await api.login({ email: 'admin@example.com', password: 'admin123' });

    expect(fetch).toHaveBeenCalledWith('/api/plugins/account/v1/auth/login', expect.objectContaining({ method: 'POST' }));
  });

  it('registers with the displayName field required by account auth', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ accessToken: 'token', refreshToken: 'refresh' }), { status: 200 })));

    const api = new AiGatewayApi();
    await api.register({ email: 'new@example.com', password: 'admin123', displayName: 'New Admin' });

    expect(fetch).toHaveBeenCalledWith('/api/plugins/account/v1/auth/register', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ email: 'new@example.com', password: 'admin123', displayName: 'New Admin' })
    }));
  });

  it('loads nav counts from airelay admin endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ customers: 2 }), { status: 200 })));

    const api = new AiGatewayApi('abc');
    await api.navCounts();

    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/nav-counts', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer abc' })
    }));
  });

  it('loads group-scoped pool data from the new admin endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ pools: [] }), { status: 200 })));

    const api = new AiGatewayApi('abc');
    await api.groupPools('default');

    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/groups/default/pools', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer abc' })
    }));
  });

  it('requests a specific pool trace from explain', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ requestTrace: null }), { status: 200 })));

    const api = new AiGatewayApi('abc');
    await api.explainGroupPool('default', 'gpt-5', { requestedModel: 'gpt-5', requestId: 'req-42' });

    expect(fetch).toHaveBeenCalledWith(
      '/api/plugins/airelay/admin/groups/default/pools/gpt-5/explain',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ requestedModel: 'gpt-5', requestId: 'req-42' })
      })
    );
  });
});
