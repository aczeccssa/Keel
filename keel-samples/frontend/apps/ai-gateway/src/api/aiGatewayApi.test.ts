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

  it('creates, updates, deletes, toggles, and tests channels through airelay admin endpoints', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 })));

    const api = new AiGatewayApi('admin-token');
    await api.createChannel({
      name: 'Primary OpenAI',
      protocol: 'OPENAI_CHAT',
      baseUrl: 'mock://openai',
      apiKey: 'sk-test',
      groupId: 'default',
      models: [{ publicModelName: 'gpt-4o-mini', upstreamModelName: 'gpt-4o-mini', enabled: true }]
    });
    await api.updateChannel('ch_1', {
      name: 'Updated',
      protocol: 'OPENAI_CHAT',
      baseUrl: 'mock://openai',
      groupId: 'default',
      models: []
    });
    await api.deleteChannel('ch_1');
    await api.toggleChannel('ch_1', false);
    await api.testChannel('ch_1');
    await api.discoverChannelModels({ protocol: 'OPENAI_CHAT', baseUrl: 'mock://openai', apiKey: 'sk-test' });
    await api.discoverSavedChannelModels('ch_1');
    await api.testChannelModel('ch_1', { publicModelName: 'gpt-4o-mini' });

    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/channels', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/channels/ch_1', expect.objectContaining({ method: 'PUT' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/channels/ch_1', expect.objectContaining({ method: 'DELETE' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/channels/ch_1/enabled/false', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/channels/ch_1/test', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/channels/discover-models', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/channels/ch_1/discover-models', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/channels/ch_1/test-model', expect.objectContaining({ method: 'POST' }));
  });

  it('manages groups, memberships, pricing, rules, account groups, customers, codes, and token keys', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 })));

    const api = new AiGatewayApi('admin-token');
    await api.createGroup({ groupId: 'pro', name: 'Pro', enabled: true });
    await api.updateGroup('pro', { groupId: 'pro', name: 'Pro Plus', enabled: true });
    await api.attachGroupMembership('pro', { channelId: 'ch_1', priority: 0, weight: 100, enabled: true });
    await api.updateGroupMembership('pro', 'ch_1', { priority: 1, weight: 80, enabled: true });
    await api.detachGroupMembership('pro', 'ch_1');
    await api.deleteGroup('pro');
    await api.upsertPricing({ model: 'gpt-4o-mini', inputCostPerMTok: 0.15, outputCostPerMTok: 0.6 });
    await api.deletePricing('gpt-4o-mini', 'default');
    await api.createRateLimitRule({ ruleId: 'strict', name: 'Strict', dimension: 'API_KEY', capacity: 10, refillRatePerSec: 1, priority: 10 });
    await api.updateRateLimitRule('strict', { ruleId: 'strict', name: 'Strict', dimension: 'API_KEY', capacity: 20, refillRatePerSec: 1, priority: 10 });
    await api.deleteRateLimitRule('strict');
    await api.createAccountGroup({ groupId: 'team', name: 'Team' });
    await api.accountGroups();
    await api.customerDetail('cust_1');
    await api.createRedemptionCode({ faceValueCredits: 1000, code: 'WELCOME1000', expiresInDays: 30 });
    await api.deleteRedemptionCode('WELCOME1000');
    await api.createApiKey({ displayName: 'Operations', maxBudgetUsd: 100 });
    await api.deleteApiKey('key_1');

    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/groups', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/groups/pro', expect.objectContaining({ method: 'PUT' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/groups/pro/memberships', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/groups/pro/memberships/ch_1', expect.objectContaining({ method: 'PUT' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/groups/pro/memberships/ch_1', expect.objectContaining({ method: 'DELETE' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/groups/pro', expect.objectContaining({ method: 'DELETE' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/pricing', expect.objectContaining({ method: 'PUT' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/pricing/gpt-4o-mini?variantKey=default', expect.objectContaining({ method: 'DELETE' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/riskcontrol/v1/rules', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/riskcontrol/v1/rules/strict', expect.objectContaining({ method: 'PUT' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/riskcontrol/v1/rules/strict', expect.objectContaining({ method: 'DELETE' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/account/admin/groups', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/account/admin/groups', expect.objectContaining({ method: 'GET' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/customer-portal/admin/customers/cust_1', expect.objectContaining({ method: 'GET' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/customer-portal/admin/codes', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/customer-portal/admin/codes/WELCOME1000', expect.objectContaining({ method: 'DELETE' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/token/v1/keys', expect.objectContaining({ method: 'POST' }));
    expect(fetch).toHaveBeenCalledWith('/api/plugins/token/v1/keys/key_1', expect.objectContaining({ method: 'DELETE' }));
  });
});
