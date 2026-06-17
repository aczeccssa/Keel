import { requestJson } from '@keel/sample-ui';

const ACCOUNT_BASE = '/api/plugins/account';
const TOKEN_BASE = '/api/plugins/token';
const AIRELAY_BASE = '/api/plugins/airelay';
const RISK_BASE = '/api/plugins/riskcontrol';
const CUSTOMER_BASE = '/api/plugins/customer-portal';

export interface AdminCredentials { email: string; password: string; }
export interface AdminRegisterRequest extends AdminCredentials { displayName: string; }
export interface AdminAuthResponse { accessToken: string; refreshToken: string; user?: { email?: string; userId?: string }; }

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
  usageRecords(limit = 200, filters?: { groupId?: string; channelId?: string; model?: string; statusFilter?: string }) {
    const params = new URLSearchParams({ limit: String(limit) });
    if (filters?.groupId) params.append('groupId', filters.groupId);
    if (filters?.channelId) params.append('channelId', filters.channelId);
    if (filters?.model) params.append('model', filters.model);
    if (filters?.statusFilter) params.append('statusFilter', filters.statusFilter);
    return requestJson(`${TOKEN_BASE}/admin/usage/records?${params}`, { token: this.token });
  }
  dashboardStats(window = '24h') { return requestJson(`${AIRELAY_BASE}/admin/stats/dashboard?window=${window}`, { token: this.token }); }
  channels() { return requestJson(`${AIRELAY_BASE}/admin/channels`, { token: this.token }); }
  channelStats(channelId: string, window = '7d') { return requestJson(`${AIRELAY_BASE}/admin/channels/${channelId}/stats?window=${window}`, { token: this.token }); }
  groups() { return requestJson(`${AIRELAY_BASE}/admin/groups`, { token: this.token }); }
  keys() { return requestJson(`${TOKEN_BASE}/admin/keys`, { token: this.token }); }
  pricing() { return requestJson(`${AIRELAY_BASE}/admin/pricing`, { token: this.token }); }
  pools() { return requestJson(`${AIRELAY_BASE}/admin/pools`, { token: this.token }); }
  rateLimitRules() { return requestJson(`${RISK_BASE}/v1/rules`, { token: this.token }); }
  rateLimitSnapshot() { return requestJson(`${RISK_BASE}/v1/snapshot`, { token: this.token }); }
  users() { return requestJson(`${ACCOUNT_BASE}/admin/users`, { token: this.token }); }
  customers() { return requestJson(`${CUSTOMER_BASE}/admin/customers`, { token: this.token }); }
  redemptionCodes() { return requestJson(`${CUSTOMER_BASE}/admin/codes`, { token: this.token }); }
  chatCompletions(body: unknown) { return requestJson(`${AIRELAY_BASE}/v1/chat/completions`, { method: 'POST', body, token: this.token }); }
  responses(body: unknown) { return requestJson(`${AIRELAY_BASE}/v1/responses`, { method: 'POST', body, token: this.token }); }
  messages(body: unknown) { return requestJson(`${AIRELAY_BASE}/v1/messages`, { method: 'POST', body, token: this.token }); }
}
