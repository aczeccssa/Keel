#!/usr/bin/env node

const DEFAULT_BASE_URL = 'http://localhost:8080';
const baseUrl = (process.env.KEEL_API_BASE_URL ?? DEFAULT_BASE_URL).replace(/\/$/, '');
const docsUrl = `${baseUrl}/api/_system/docs/openapi.json`;

const matrix = [
  row('AiGatewayApi.login', 'POST', '/api/plugins/account/v1/auth/login', 'account', 'public', 'AuthResponse: accessToken, refreshToken, user', '401 empty/text or JSON error for invalid credentials', 'unit + openapi + runtime-login'),
  row('AiGatewayApi.register', 'POST', '/api/plugins/account/v1/auth/register', 'account', 'public', 'AuthResponse: accessToken, refreshToken, user', '400/409 empty/text or JSON error for invalid or duplicate account', 'unit + openapi'),
  row('AiGatewayApi.refresh', 'POST', '/api/plugins/account/v1/auth/refresh', 'account', 'public refresh token', 'AuthResponse: accessToken, refreshToken, user', '401 empty/text or JSON error for invalid refresh token', 'openapi + runtime-invalid-refresh'),
  row('AiGatewayApi.navCounts', 'GET', '/api/plugins/airelay/admin/nav-counts', 'airelay', 'manager token sent; backend currently open', 'NavCountsResponse: customers', 'JSON error if backend dependency unavailable', 'openapi + runtime-get'),
  row('AiGatewayApi.usageGlobal', 'GET', '/api/plugins/token/admin/usage/global', 'token', 'account admin JWT', 'UsageSnapshot', '401/403 empty/text or JSON error without admin JWT', 'openapi + runtime-auth'),
  row('AiGatewayApi.usageRecords', 'GET', '/api/plugins/token/admin/usage/records', 'token', 'account admin JWT', 'UsageListResponse: records, total', '401/403 empty/text or JSON error without admin JWT', 'openapi + runtime-auth'),
  row('AiGatewayApi.channels', 'GET', '/api/plugins/airelay/admin/channels', 'airelay', 'manager token sent; backend currently open', 'ChannelListResponse: channels', 'JSON error if channel store unavailable', 'openapi + runtime-get'),
  row('AiGatewayApi.groups', 'GET', '/api/plugins/airelay/admin/groups', 'airelay', 'manager token sent; backend currently open', 'GroupListResponse: groups', 'JSON error if channel store unavailable', 'openapi + runtime-get'),
  row('AiGatewayApi.keys', 'GET', '/api/plugins/token/admin/keys', 'token', 'account admin JWT', 'ApiKeyListResponse: keys, total', '401/403 empty/text or JSON error without admin JWT', 'openapi + runtime-auth'),
  row('AiGatewayApi.pricing', 'GET', '/api/plugins/airelay/admin/pricing', 'airelay', 'manager token sent; backend currently open', 'PricingListResponse: pricings', 'JSON error if channel store unavailable', 'openapi + runtime-get'),
  row('AiGatewayApi.pools', 'GET', '/api/plugins/airelay/admin/pools', 'airelay', 'manager token sent; backend currently open', 'PoolChainSnapshot: chains', 'JSON error if pool manager unavailable', 'openapi + runtime-get'),
  row('AiGatewayApi.rateLimitRules', 'GET', '/api/plugins/riskcontrol/v1/rules', 'riskcontrol', 'account admin JWT', 'RateLimitRuleListResponse: rules, total', '401/403 empty/text or JSON error without admin JWT', 'openapi + runtime-auth'),
  row('AiGatewayApi.users', 'GET', '/api/plugins/account/admin/users', 'account', 'account admin JWT', 'AccountUserListResponse: users, total', '401/403 empty/text or JSON error without admin JWT', 'openapi + runtime-auth'),
  row('AiGatewayApi.customers', 'GET', '/api/plugins/customer-portal/admin/customers', 'customer-portal', 'account admin JWT', 'CustomerListResponse: customers, total', '401/403 empty/text or JSON error without admin JWT', 'openapi + runtime-auth'),
  row('AiGatewayApi.redemptionCodes', 'GET', '/api/plugins/customer-portal/admin/codes', 'customer-portal', 'account admin JWT', 'RedemptionCodeListResponse: codes, total', '401/403 empty/text or JSON error without admin JWT', 'openapi + runtime-auth'),
  row('AiGatewayApi.chatCompletions', 'POST', '/api/plugins/airelay/v1/chat/completions', 'airelay', 'AI gateway API key', 'OpenAI chat completion envelope', '400/401/402/403/404/429/503 empty/text or JSON error', 'openapi + runtime-safe-relay + gradle integration'),
  row('AiGatewayApi.responses', 'POST', '/api/plugins/airelay/v1/responses', 'airelay', 'AI gateway API key', 'OpenAI Responses envelope', '400/401/402/403/404/429/503 empty/text or JSON error', 'openapi + runtime-safe-relay + gradle integration'),
  row('AiGatewayApi.messages', 'POST', '/api/plugins/airelay/v1/messages', 'airelay', 'AI gateway API key or x-api-key', 'Anthropic Messages envelope', '400/401/402/403/404/429/503 empty/text or JSON error', 'openapi + runtime-safe-relay + gradle integration'),

  row('CustomerPortalApi.login', 'POST', '/api/plugins/customer-portal/v1/customer/auth/login', 'customer-portal', 'public', 'CustomerAuthResponse: accessToken, refreshToken, customerId, email, displayName', '401 empty/text or JSON error for invalid credentials', 'unit + openapi'),
  row('CustomerPortalApi.register', 'POST', '/api/plugins/customer-portal/v1/customer/auth/register', 'customer-portal', 'public', 'CustomerAuthResponse: accessToken, refreshToken, customerId, email, displayName', '400/409 empty/text or JSON error for invalid or duplicate customer', 'unit + openapi + runtime-customer'),
  row('CustomerPortalApi.refresh', 'POST', '/api/plugins/customer-portal/v1/customer/auth/refresh', 'customer-portal', 'public refresh token', 'CustomerAuthResponse', '401 empty/text or JSON error for invalid refresh token', 'openapi + runtime-invalid-refresh'),
  row('CustomerPortalApi.profile', 'GET', '/api/plugins/customer-portal/v1/customer/auth/me', 'customer-portal', 'customer JWT', 'CustomerProfile', '401 empty/text or JSON error without customer JWT', 'openapi + runtime-customer'),
  row('CustomerPortalApi.credits', 'GET', '/api/plugins/customer-portal/v1/customer/credits', 'customer-portal', 'customer JWT', 'CreditBalanceResponse: balanceCredits', '401 empty/text or JSON error without customer JWT', 'openapi + runtime-customer'),
  row('CustomerPortalApi.creditLedger', 'GET', '/api/plugins/customer-portal/v1/customer/credits/ledger', 'customer-portal', 'customer JWT', 'CreditLedgerResponse: entries, total, nextCursor', '401 empty/text or JSON error without customer JWT', 'openapi + runtime-customer'),
  row('CustomerPortalApi.redeem', 'POST', '/api/plugins/customer-portal/v1/customer/credits/redeem', 'customer-portal', 'customer JWT', 'RedeemCodeResponse', '400/404/409/410 JSON error for invalid code', 'openapi + runtime-customer'),
  row('CustomerPortalApi.usage', 'GET', '/api/plugins/customer-portal/v1/customer/usage', 'customer-portal', 'customer JWT', 'CustomerUsageListResponse: records, total', '401 empty/text or JSON error without customer JWT', 'openapi + runtime-customer'),
  row('CustomerPortalApi.keys', 'GET', '/api/plugins/customer-portal/v1/customer/keys', 'customer-portal', 'customer JWT', 'CustomerKeyListResponse: keys, total', '401 empty/text or JSON error without customer JWT', 'openapi + runtime-customer'),
  row('CustomerPortalApi.createKey', 'POST', '/api/plugins/customer-portal/v1/customer/keys', 'customer-portal', 'customer JWT', 'CustomerKeyCreatedResponse: key, rawKey', '400/401 empty/text or JSON error for invalid key request', 'unit + openapi + runtime-customer'),
  row('CustomerPortalApi.deleteKey', 'DELETE', '/api/plugins/customer-portal/v1/customer/keys/{keyId}', 'customer-portal', 'customer JWT', 'CustomerKeyView', '401/403/404 empty/text or JSON error', 'openapi + runtime-customer'),
  row('CustomerPortalApi.pricing', 'GET', '/api/plugins/customer-portal/v1/customer/pricing', 'customer-portal', 'customer JWT', 'ModelPricingListResponse: summaries', '401 empty/text or JSON error without customer JWT', 'openapi + runtime-customer'),
  row('CustomerPortalApi.listRelayGroups', 'GET', '/api/plugins/airelay/admin/groups', 'airelay', 'customer token sent; backend currently open', 'GroupListResponse: groups', 'JSON error if channel store unavailable', 'unit + openapi + runtime-customer'),

  row('LegacyAiGateway.PanelProviders.create', 'POST', '/api/plugins/airelay/admin/channels', 'airelay', 'manager token sent; backend currently open', 'ChannelView', '400/503 JSON error for invalid channel', 'openapi'),
  row('LegacyAiGateway.PanelProviders.update', 'PUT', '/api/plugins/airelay/admin/channels/{channelId}', 'airelay', 'manager token sent; backend currently open', 'ChannelView', '400/404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelProviders.delete', 'DELETE', '/api/plugins/airelay/admin/channels/{channelId}', 'airelay', 'manager token sent; backend currently open', 'DeleteChannelResponse', '404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelProviders.toggle', 'POST', '/api/plugins/airelay/admin/channels/{channelId}/enabled/{enabled}', 'airelay', 'manager token sent; backend currently open', 'ToggleChannelResponse', '400/404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelProviders.test', 'POST', '/api/plugins/airelay/admin/channels/{channelId}/test', 'airelay', 'manager token sent; backend currently open', 'ChannelTestResponse', '404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelProviders.discoverDraft', 'POST', '/api/plugins/airelay/admin/channels/discover-models', 'airelay', 'manager token sent; backend currently open', 'DiscoverModelsResponse', '400/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelProviders.discoverSaved', 'POST', '/api/plugins/airelay/admin/channels/{channelId}/discover-models', 'airelay', 'manager token sent; backend currently open', 'DiscoverModelsResponse', '404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelProviders.testModel', 'POST', '/api/plugins/airelay/admin/channels/{channelId}/test-model', 'airelay', 'manager token sent; backend currently open', 'ChannelTestResponse', '400/404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelKeys.create', 'POST', '/api/plugins/token/v1/keys', 'token', 'account JWT', 'ApiKeyCreatedResponse: key, rawKey', '400/401 empty/text or JSON error', 'openapi + runtime-token-key'),
  row('LegacyAiGateway.PanelKeys.delete', 'DELETE', '/api/plugins/token/v1/keys/{keyId}', 'token', 'account JWT', 'ApiKeyView', '401/403/404 empty/text or JSON error', 'openapi + runtime-token-key'),
  row('LegacyAiGateway.PanelGroups.create', 'POST', '/api/plugins/airelay/admin/groups', 'airelay', 'manager token sent; backend currently open', 'GroupView', '400/409/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelGroups.update', 'PUT', '/api/plugins/airelay/admin/groups/{groupId}', 'airelay', 'manager token sent; backend currently open', 'GroupView', '400/404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelGroups.delete', 'DELETE', '/api/plugins/airelay/admin/groups/{groupId}', 'airelay', 'manager token sent; backend currently open', 'DeleteGroupResponse', '400/404/409/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelGroups.attachMembership', 'POST', '/api/plugins/airelay/admin/groups/{groupId}/memberships', 'airelay', 'manager token sent; backend currently open', 'GroupMembershipView', '400/404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelGroups.updateMembership', 'PUT', '/api/plugins/airelay/admin/groups/{groupId}/memberships/{channelId}', 'airelay', 'manager token sent; backend currently open', 'GroupMembershipView', '404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelGroups.detachMembership', 'DELETE', '/api/plugins/airelay/admin/groups/{groupId}/memberships/{channelId}', 'airelay', 'manager token sent; backend currently open', 'DeleteChannelResponse', '404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelPricing.upsert', 'PUT', '/api/plugins/airelay/admin/pricing', 'airelay', 'manager token sent; backend currently open', 'PricingView', '400/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelPricing.delete', 'DELETE', '/api/plugins/airelay/admin/pricing/{model}', 'airelay', 'manager token sent; backend currently open', 'DeletePricingResponse', '404/503 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelRateLimits.create', 'POST', '/api/plugins/riskcontrol/v1/rules', 'riskcontrol', 'account admin JWT', 'RateLimitRuleView', '400/401/403 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelRateLimits.update', 'PUT', '/api/plugins/riskcontrol/v1/rules/{ruleId}', 'riskcontrol', 'account admin JWT', 'RateLimitRuleView', '400/401/403 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelRateLimits.delete', 'DELETE', '/api/plugins/riskcontrol/v1/rules/{ruleId}', 'riskcontrol', 'account admin JWT', 'ResetRateLimitResponse', '401/403/404 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelUsers.createGroup', 'POST', '/api/plugins/account/admin/groups', 'account', 'account admin JWT', 'AccountGroupView', '400/401/403/409 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelCustomers.detail', 'GET', '/api/plugins/customer-portal/admin/customers/{customerId}', 'customer-portal', 'account admin JWT', 'CustomerAdminDetailView', '401/403/404 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelRedemptionCodes.create', 'POST', '/api/plugins/customer-portal/admin/codes', 'customer-portal', 'account admin JWT', 'RedemptionCodeView', '400/401/403/409 JSON error', 'openapi'),
  row('LegacyAiGateway.PanelRedemptionCodes.delete', 'DELETE', '/api/plugins/customer-portal/admin/codes/{code}', 'customer-portal', 'account admin JWT', 'DeleteResponse', '401/403/404 JSON error', 'openapi'),
  row('LegacyCustomerPortal.oauthStub', 'POST', '/api/plugins/customer-portal/v1/customer/auth/oauth/stub', 'customer-portal', 'public', 'CustomerAuthResponse', '400 JSON error for invalid OAuth stub request', 'openapi'),
  row('LegacyCustomerPortal.keyDetail', 'GET', '/api/plugins/customer-portal/v1/customer/keys/{keyId}', 'customer-portal', 'customer JWT', 'CustomerKeyView', '401/403/404 JSON error', 'openapi'),
  row('LegacyCustomerPortal.keyUpdate', 'PUT', '/api/plugins/customer-portal/v1/customer/keys/{keyId}', 'customer-portal', 'customer JWT', 'CustomerKeyView', '400/401/403/404 JSON error', 'openapi')
];

function row(frontendMethod, method, path, backendOwner, authRequirement, successShape, failureBehavior, verification) {
  return { frontendMethod, method, path, backendOwner, authRequirement, successShape, failureBehavior, verification };
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      ...(options.body == null ? {} : { 'Content-Type': 'application/json' }),
      ...(options.headers ?? {})
    }
  });
  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  return { response, body, text };
}

async function loadOpenApi() {
  const response = await fetch(docsUrl);
  assert(response.ok, `Unable to load ${docsUrl}: ${response.status} ${response.statusText}`);
  return response.json();
}

function verifyOpenApi(spec) {
  const missing = [];
  for (const contract of matrix) {
    const operation = spec.paths?.[contract.path]?.[contract.method.toLowerCase()];
    if (!operation) missing.push(`${contract.method} ${contract.path} (${contract.frontendMethod})`);
  }
  assert(missing.length === 0, `Missing OpenAPI paths:\n${missing.join('\n')}`);
}

function assertJsonObject(label, value) {
  assert(value && typeof value === 'object' && !Array.isArray(value), `${label} expected a JSON object`);
}

function assertFailureBody(label, value) {
  assert(value == null || typeof value === 'string' || (typeof value === 'object' && !Array.isArray(value)), `${label} expected an empty, text, or JSON error body`);
}

function assertStatus(label, actual, expected) {
  assert(expected.includes(actual), `${label} expected status ${expected.join('/')} but got ${actual}`);
}

async function verifyRuntime() {
  const accountLogin = await request('/api/plugins/account/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: 'admin@example.com', password: 'admin123' })
  });
  assertStatus('account login', accountLogin.response.status, [200]);
  assertJsonObject('account login', accountLogin.body);
  assert(typeof accountLogin.body.accessToken === 'string', 'account login missing accessToken');
  assert(typeof accountLogin.body.refreshToken === 'string', 'account login missing refreshToken');
  const adminToken = accountLogin.body.accessToken;

  const invalidAccountLogin = await request('/api/plugins/account/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: 'admin@example.com', password: 'wrong-password' })
  });
  assertStatus('invalid account login', invalidAccountLogin.response.status, [401]);
  assertFailureBody('invalid account login error', invalidAccountLogin.body);

  const invalidAccountRefresh = await request('/api/plugins/account/v1/auth/refresh', {
    method: 'POST',
    body: JSON.stringify({ refreshToken: 'not-a-refresh-token' })
  });
  assertStatus('invalid account refresh', invalidAccountRefresh.response.status, [401]);
  assertFailureBody('invalid account refresh error', invalidAccountRefresh.body);

  for (const [label, path, token, keys] of [
    ['nav counts', '/api/plugins/airelay/admin/nav-counts', null, ['customers']],
    ['channels', '/api/plugins/airelay/admin/channels', null, ['channels']],
    ['groups', '/api/plugins/airelay/admin/groups', null, ['groups']],
    ['pricing', '/api/plugins/airelay/admin/pricing', null, ['pricings']],
    ['pools', '/api/plugins/airelay/admin/pools', null, ['chains']],
    ['token admin keys', '/api/plugins/token/admin/keys', adminToken, ['keys', 'total']],
    ['usage global', '/api/plugins/token/admin/usage/global', adminToken, ['totalRequests']],
    ['usage records', '/api/plugins/token/admin/usage/records?limit=5', adminToken, ['records', 'total']],
    ['rate limit rules', '/api/plugins/riskcontrol/v1/rules', adminToken, ['rules', 'total']],
    ['account users', '/api/plugins/account/admin/users', adminToken, ['users', 'total']],
    ['customers', '/api/plugins/customer-portal/admin/customers', adminToken, ['customers', 'total']],
    ['redemption codes', '/api/plugins/customer-portal/admin/codes', adminToken, ['codes', 'total']]
  ]) {
    const response = await request(path, token ? { headers: { Authorization: `Bearer ${token}` } } : {});
    assertStatus(label, response.response.status, [200]);
    assertJsonObject(label, response.body);
    for (const key of keys) assert(key in response.body, `${label} missing ${key}`);
  }

  for (const [label, path] of [
    ['token admin keys without token', '/api/plugins/token/admin/keys'],
    ['risk rules without token', '/api/plugins/riskcontrol/v1/rules'],
    ['account users without token', '/api/plugins/account/admin/users'],
    ['customers without token', '/api/plugins/customer-portal/admin/customers']
  ]) {
    const response = await request(path);
    assertStatus(label, response.response.status, [401, 403]);
    assertFailureBody(`${label} error`, response.body);
  }

  const keyResponse = await request('/api/plugins/token/v1/keys', {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminToken}` },
    body: JSON.stringify({ displayName: `Step2 contract ${Date.now()}`, maxBudgetUsd: 100 })
  });
  assertStatus('create gateway key', keyResponse.response.status, [200]);
  assertJsonObject('create gateway key', keyResponse.body);
  assert(typeof keyResponse.body.rawKey === 'string', 'create gateway key missing rawKey');
  const gatewayKey = keyResponse.body.rawKey;
  const gatewayKeyId = keyResponse.body.key?.keyId;

  const modelsResponse = await request('/api/plugins/airelay/v1/models', {
    headers: { Authorization: `Bearer ${gatewayKey}` }
  });
  assertStatus('relay models', modelsResponse.response.status, [200]);
  assertJsonObject('relay models', modelsResponse.body);
  assert(Array.isArray(modelsResponse.body.data), 'relay models missing data array');

  for (const [label, path, body] of [
    ['chat completions invalid model', '/api/plugins/airelay/v1/chat/completions', { model: '__keel_missing_model__', messages: [{ role: 'user', content: 'Hello' }] }],
    ['responses invalid model', '/api/plugins/airelay/v1/responses', { model: '__keel_missing_model__', input: 'Hello', store: false }],
    ['messages invalid model', '/api/plugins/airelay/v1/messages', { model: '__keel_missing_model__', max_tokens: 64, messages: [{ role: 'user', content: 'Hello' }] }]
  ]) {
    const response = await request(path, {
      method: 'POST',
      headers: { Authorization: `Bearer ${gatewayKey}` },
      body: JSON.stringify(body)
    });
    assertStatus(label, response.response.status, [400, 404]);
    assertJsonObject(label, response.body);
    const inner = typeof response.body.response === 'string' ? JSON.parse(response.body.response) : response.body;
    assert(inner.error || inner.message, `${label} missing structured error`);
  }

  if (gatewayKeyId) {
    const deleteGatewayKey = await request(`/api/plugins/token/v1/keys/${encodeURIComponent(gatewayKeyId)}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${adminToken}` }
    });
    assertStatus('delete gateway key', deleteGatewayKey.response.status, [200]);
    assertJsonObject('delete gateway key', deleteGatewayKey.body);
  }

  const unique = Date.now();
  const customerRegister = await request('/api/plugins/customer-portal/v1/customer/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email: `step2-${unique}@example.com`, password: 'password123', displayName: 'Step Two Probe' })
  });
  assertStatus('customer register', customerRegister.response.status, [200]);
  assertJsonObject('customer register', customerRegister.body);
  assert(typeof customerRegister.body.accessToken === 'string', 'customer register missing accessToken');
  assert(typeof customerRegister.body.refreshToken === 'string', 'customer register missing refreshToken');
  const customerToken = customerRegister.body.accessToken;

  const customerRefresh = await request('/api/plugins/customer-portal/v1/customer/auth/refresh', {
    method: 'POST',
    body: JSON.stringify({ refreshToken: customerRegister.body.refreshToken })
  });
  assertStatus('customer refresh', customerRefresh.response.status, [200]);
  assertJsonObject('customer refresh', customerRefresh.body);

  for (const [label, path, keys] of [
    ['customer profile', '/api/plugins/customer-portal/v1/customer/auth/me', ['customerId', 'email', 'displayName']],
    ['customer credits', '/api/plugins/customer-portal/v1/customer/credits', ['balanceCredits']],
    ['customer ledger', '/api/plugins/customer-portal/v1/customer/credits/ledger', ['entries', 'total', 'nextCursor']],
    ['customer usage', '/api/plugins/customer-portal/v1/customer/usage', ['records', 'total']],
    ['customer keys', '/api/plugins/customer-portal/v1/customer/keys', ['keys', 'total']],
    ['customer pricing', '/api/plugins/customer-portal/v1/customer/pricing', ['summaries']],
    ['customer relay groups', '/api/plugins/airelay/admin/groups', ['groups']]
  ]) {
    const response = await request(path, { headers: { Authorization: `Bearer ${customerToken}` } });
    assertStatus(label, response.response.status, [200]);
    assertJsonObject(label, response.body);
    for (const key of keys) assert(key in response.body, `${label} missing ${key}`);
  }

  const customerKey = await request('/api/plugins/customer-portal/v1/customer/keys', {
    method: 'POST',
    headers: { Authorization: `Bearer ${customerToken}` },
    body: JSON.stringify({ name: 'Step 2 probe key', routingGroupId: 'default', monthlyBudgetCredits: 100 })
  });
  assertStatus('create customer key', customerKey.response.status, [200]);
  assertJsonObject('create customer key', customerKey.body);
  assertJsonObject('create customer key.key', customerKey.body.key);
  assert(typeof customerKey.body.rawKey === 'string', 'create customer key missing rawKey');

  const customerKeyId = customerKey.body.key.keyId;
  const deleteCustomerKey = await request(`/api/plugins/customer-portal/v1/customer/keys/${encodeURIComponent(customerKeyId)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${customerToken}` }
  });
  assertStatus('delete customer key', deleteCustomerKey.response.status, [200]);
  assertJsonObject('delete customer key', deleteCustomerKey.body);
  assert(deleteCustomerKey.body.keyId === customerKeyId, 'delete customer key returned a different key');

  const invalidRedeem = await request('/api/plugins/customer-portal/v1/customer/credits/redeem', {
    method: 'POST',
    headers: { Authorization: `Bearer ${customerToken}` },
    body: JSON.stringify({ code: `missing-${unique}` })
  });
  assertStatus('invalid redeem', invalidRedeem.response.status, [404]);
  assertFailureBody('invalid redeem error', invalidRedeem.body);
}

function printMatrix() {
  console.log('| Frontend method | Method | Path | Backend owner | Auth | Success shape | Failure behavior | Verification |');
  console.log('| --- | --- | --- | --- | --- | --- | --- | --- |');
  for (const item of matrix) {
    console.log(`| ${item.frontendMethod} | ${item.method} | \`${item.path}\` | ${item.backendOwner} | ${item.authRequirement} | ${item.successShape} | ${item.failureBehavior} | ${item.verification} |`);
  }
}

const mode = process.argv.includes('--matrix') ? 'matrix' : 'verify';
if (mode === 'matrix') {
  printMatrix();
} else {
  const spec = await loadOpenApi();
  verifyOpenApi(spec);
  await verifyRuntime();
  console.log(`Verified ${matrix.length} frontend API contracts against ${baseUrl}.`);
}
