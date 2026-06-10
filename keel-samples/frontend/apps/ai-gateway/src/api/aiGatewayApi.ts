import { requestJson } from '@keel/sample-ui';

const ACCOUNT_BASE = '/api/plugins/account';
const TOKEN_BASE = '/api/plugins/token';
const AIRELAY_BASE = '/api/plugins/airelay';
const RISK_BASE = '/api/plugins/riskcontrol';
const CUSTOMER_BASE = '/api/plugins/customer-portal';

export interface AdminCredentials { email: string; password: string; }
export interface AdminRegisterRequest extends AdminCredentials { displayName: string; }
export interface AdminAuthResponse { accessToken: string; refreshToken: string; user?: { email?: string; userId?: string }; }
export type JsonRecord = Record<string, unknown>;
export interface ChannelModelRequest { publicModelName: string; upstreamModelName?: string; enabled?: boolean; }
export interface ChannelRequest {
  name: string;
  protocol: string;
  baseUrl: string;
  apiKey?: string;
  apiKeyEnv?: string;
  groupId: string;
  priority?: number;
  weight?: number;
  enabled?: boolean;
  models: ChannelModelRequest[];
}
export interface GroupRequest {
  groupId?: string;
  name: string;
  description?: string;
  enabled?: boolean;
  exposureMode?: string;
  aliasRoutes?: JsonRecord[];
}
export interface MembershipRequest { channelId?: string; priority?: number; weight?: number; enabled?: boolean; }
export interface PricingRequest {
  model: string;
  variantKey?: string;
  inputCostPerMTok?: number;
  outputCostPerMTok?: number;
  creditMultiplier?: number;
}
export interface RateLimitRuleRequest {
  ruleId?: string;
  name: string;
  dimension: string;
  pathPattern?: string;
  methods?: string[];
  capacity: number;
  refillRatePerSec: number;
  priority?: number;
}
export interface AccountGroupRequest {
  groupId: string;
  name: string;
  costMultiplier?: number;
  defaultRpm?: number;
  defaultTpm?: number;
  defaultBudgetUsd?: number;
}
export interface RedemptionCodeRequest { faceValueCredits: number; code?: string; expiresInDays?: number; }

export class AiGatewayApi {
  constructor(private token: string | null = null) {}

  withToken(token: string | null) {
    return new AiGatewayApi(token);
  }

  login(body: AdminCredentials) {
    return requestJson<AdminAuthResponse>(`${ACCOUNT_BASE}/v1/auth/login`, { method: 'POST', body });
  }

  register(body: AdminRegisterRequest) {
    return requestJson<AdminAuthResponse>(`${ACCOUNT_BASE}/v1/auth/register`, { method: 'POST', body });
  }

  refresh(refreshToken: string) {
    return requestJson<AdminAuthResponse>(`${ACCOUNT_BASE}/v1/auth/refresh`, { method: 'POST', body: { refreshToken } });
  }

  navCounts() { return requestJson(`${AIRELAY_BASE}/admin/nav-counts`, { token: this.token }); }
  usageGlobal() { return requestJson(`${TOKEN_BASE}/admin/usage/global`, { token: this.token }); }
  usageRecords(limit = 200) { return requestJson(`${TOKEN_BASE}/admin/usage/records?limit=${limit}`, { token: this.token }); }
  channels() { return requestJson(`${AIRELAY_BASE}/admin/channels`, { token: this.token }); }
  groups() { return requestJson(`${AIRELAY_BASE}/admin/groups`, { token: this.token }); }
  keys() { return requestJson(`${TOKEN_BASE}/admin/keys`, { token: this.token }); }
  pricing() { return requestJson(`${AIRELAY_BASE}/admin/pricing`, { token: this.token }); }
  pools() { return requestJson(`${AIRELAY_BASE}/admin/pools`, { token: this.token }); }
  rateLimitRules() { return requestJson(`${RISK_BASE}/v1/rules`, { token: this.token }); }
  users() { return requestJson(`${ACCOUNT_BASE}/admin/users`, { token: this.token }); }
  customers() { return requestJson(`${CUSTOMER_BASE}/admin/customers`, { token: this.token }); }
  redemptionCodes() { return requestJson(`${CUSTOMER_BASE}/admin/codes`, { token: this.token }); }
  chatCompletions(body: unknown) { return requestJson(`${AIRELAY_BASE}/v1/chat/completions`, { method: 'POST', body, token: this.token }); }
  responses(body: unknown) { return requestJson(`${AIRELAY_BASE}/v1/responses`, { method: 'POST', body, token: this.token }); }
  messages(body: unknown) { return requestJson(`${AIRELAY_BASE}/v1/messages`, { method: 'POST', body, token: this.token }); }

  createChannel(body: ChannelRequest) {
    return requestJson(`${AIRELAY_BASE}/admin/channels`, { method: 'POST', body, token: this.token });
  }

  updateChannel(channelId: string, body: ChannelRequest) {
    return requestJson(`${AIRELAY_BASE}/admin/channels/${encodeURIComponent(channelId)}`, { method: 'PUT', body, token: this.token });
  }

  deleteChannel(channelId: string) {
    return requestJson(`${AIRELAY_BASE}/admin/channels/${encodeURIComponent(channelId)}`, { method: 'DELETE', token: this.token });
  }

  toggleChannel(channelId: string, enabled: boolean) {
    return requestJson(`${AIRELAY_BASE}/admin/channels/${encodeURIComponent(channelId)}/enabled/${enabled}`, { method: 'POST', token: this.token });
  }

  testChannel(channelId: string) {
    return requestJson(`${AIRELAY_BASE}/admin/channels/${encodeURIComponent(channelId)}/test`, { method: 'POST', token: this.token });
  }

  discoverChannelModels(body: Pick<ChannelRequest, 'protocol' | 'baseUrl' | 'apiKey' | 'apiKeyEnv'>) {
    return requestJson(`${AIRELAY_BASE}/admin/channels/discover-models`, { method: 'POST', body, token: this.token });
  }

  discoverSavedChannelModels(channelId: string) {
    return requestJson(`${AIRELAY_BASE}/admin/channels/${encodeURIComponent(channelId)}/discover-models`, { method: 'POST', token: this.token });
  }

  testChannelModel(channelId: string, body: JsonRecord) {
    return requestJson(`${AIRELAY_BASE}/admin/channels/${encodeURIComponent(channelId)}/test-model`, { method: 'POST', body, token: this.token });
  }

  createGroup(body: GroupRequest) {
    return requestJson(`${AIRELAY_BASE}/admin/groups`, { method: 'POST', body, token: this.token });
  }

  updateGroup(groupId: string, body: GroupRequest) {
    return requestJson(`${AIRELAY_BASE}/admin/groups/${encodeURIComponent(groupId)}`, { method: 'PUT', body, token: this.token });
  }

  deleteGroup(groupId: string) {
    return requestJson(`${AIRELAY_BASE}/admin/groups/${encodeURIComponent(groupId)}`, { method: 'DELETE', token: this.token });
  }

  attachGroupMembership(groupId: string, body: MembershipRequest) {
    return requestJson(`${AIRELAY_BASE}/admin/groups/${encodeURIComponent(groupId)}/memberships`, { method: 'POST', body, token: this.token });
  }

  updateGroupMembership(groupId: string, channelId: string, body: MembershipRequest) {
    return requestJson(`${AIRELAY_BASE}/admin/groups/${encodeURIComponent(groupId)}/memberships/${encodeURIComponent(channelId)}`, { method: 'PUT', body, token: this.token });
  }

  detachGroupMembership(groupId: string, channelId: string) {
    return requestJson(`${AIRELAY_BASE}/admin/groups/${encodeURIComponent(groupId)}/memberships/${encodeURIComponent(channelId)}`, { method: 'DELETE', token: this.token });
  }

  upsertPricing(body: PricingRequest) {
    return requestJson(`${AIRELAY_BASE}/admin/pricing`, { method: 'PUT', body, token: this.token });
  }

  deletePricing(model: string, variantKey?: string) {
    const suffix = variantKey ? `?variantKey=${encodeURIComponent(variantKey)}` : '';
    return requestJson(`${AIRELAY_BASE}/admin/pricing/${encodeURIComponent(model)}${suffix}`, { method: 'DELETE', token: this.token });
  }

  createRateLimitRule(body: RateLimitRuleRequest) {
    return requestJson(`${RISK_BASE}/v1/rules`, { method: 'POST', body, token: this.token });
  }

  updateRateLimitRule(ruleId: string, body: RateLimitRuleRequest) {
    return requestJson(`${RISK_BASE}/v1/rules/${encodeURIComponent(ruleId)}`, { method: 'PUT', body, token: this.token });
  }

  deleteRateLimitRule(ruleId: string) {
    return requestJson(`${RISK_BASE}/v1/rules/${encodeURIComponent(ruleId)}`, { method: 'DELETE', token: this.token });
  }

  accountGroups() {
    return requestJson(`${ACCOUNT_BASE}/admin/groups`, { token: this.token });
  }

  createAccountGroup(body: AccountGroupRequest) {
    return requestJson(`${ACCOUNT_BASE}/admin/groups`, { method: 'POST', body, token: this.token });
  }

  customerDetail(customerId: string) {
    return requestJson(`${CUSTOMER_BASE}/admin/customers/${encodeURIComponent(customerId)}`, { token: this.token });
  }

  createRedemptionCode(body: RedemptionCodeRequest) {
    return requestJson(`${CUSTOMER_BASE}/admin/codes`, { method: 'POST', body, token: this.token });
  }

  deleteRedemptionCode(code: string) {
    return requestJson(`${CUSTOMER_BASE}/admin/codes/${encodeURIComponent(code)}`, { method: 'DELETE', token: this.token });
  }

  createApiKey(body: JsonRecord) {
    return requestJson(`${TOKEN_BASE}/v1/keys`, { method: 'POST', body, token: this.token });
  }

  deleteApiKey(keyId: string) {
    return requestJson(`${TOKEN_BASE}/v1/keys/${encodeURIComponent(keyId)}`, { method: 'DELETE', token: this.token });
  }
}
