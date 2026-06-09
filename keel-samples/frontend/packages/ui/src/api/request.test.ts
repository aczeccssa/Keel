import { afterEach, describe, expect, it, vi } from 'vitest';
import { requestJson } from './request';

describe('requestJson', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('sends bearer token and parses JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 })));

    const result = await requestJson<{ ok: boolean }>('/api/plugins/token/v1/keys', {
      token: 'abc123'
    });

    expect(result.ok).toBe(true);
    expect(fetch).toHaveBeenCalledWith('/api/plugins/token/v1/keys', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer abc123' })
    }));
  });

  it('throws a structured error for non-2xx JSON responses', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ message: 'Nope' }), { status: 403 })));

    await expect(requestJson('/api/plugins/account/admin/users')).rejects.toMatchObject({
      status: 403,
      message: 'Nope'
    });
  });
});
