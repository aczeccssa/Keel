import { requestJson } from '@keel/sample-ui';

const CUSTOMER_BASE = '/api/plugins/customer-portal';
const AIRELAY_BASE = '/api/plugins/airelay';

export interface CustomerCredentials { email: string; password: string; }
export interface CustomerRegisterRequest extends CustomerCredentials { displayName: string; }
export interface CustomerAuthResponse { accessToken: string; refreshToken: string; email?: string; displayName?: string; customerId?: string; }
export interface CustomerKeyCreateRequest { name: string; routingGroupId?: string; monthlyBudgetCredits?: number; }
export interface CustomerKeyView { keyId: string; name: string; prefix?: string; createdAt?: string; }
export interface CustomerKeyCreatedResponse { key: CustomerKeyView; rawKey: string; }

export class CustomerPortalApi {
  constructor(private token: string | null = null) {}

  withToken(token: string | null) {
    return new CustomerPortalApi(token);
  }

  login(body: CustomerCredentials) {
    return requestJson<CustomerAuthResponse>(`${CUSTOMER_BASE}/v1/customer/auth/login`, { method: 'POST', body });
  }

  register(body: CustomerRegisterRequest) {
    return requestJson<CustomerAuthResponse>(`${CUSTOMER_BASE}/v1/customer/auth/register`, { method: 'POST', body });
  }

  refresh(refreshToken: string) {
    return requestJson<CustomerAuthResponse>(`${CUSTOMER_BASE}/v1/customer/auth/refresh`, { method: 'POST', body: { refreshToken } });
  }

  profile() {
    return requestJson(`${CUSTOMER_BASE}/v1/customer/auth/me`, { token: this.token });
  }

  credits() {
    return requestJson(`${CUSTOMER_BASE}/v1/customer/credits`, { token: this.token });
  }

  creditLedger() {
    return requestJson(`${CUSTOMER_BASE}/v1/customer/credits/ledger`, { token: this.token });
  }

  redeem(code: string) {
    return requestJson(`${CUSTOMER_BASE}/v1/customer/credits/redeem`, { method: 'POST', body: { code }, token: this.token });
  }

  usage() {
    return requestJson(`${CUSTOMER_BASE}/v1/customer/usage`, { token: this.token });
  }

  keys() {
    return requestJson(`${CUSTOMER_BASE}/v1/customer/keys`, { token: this.token });
  }

  createKey(body: CustomerKeyCreateRequest) {
    return requestJson<CustomerKeyCreatedResponse>(`${CUSTOMER_BASE}/v1/customer/keys`, { method: 'POST', body, token: this.token });
  }

  deleteKey(keyId: string) {
    return requestJson(`${CUSTOMER_BASE}/v1/customer/keys/${encodeURIComponent(keyId)}`, { method: 'DELETE', token: this.token });
  }

  pricing() {
    return requestJson(`${CUSTOMER_BASE}/v1/customer/pricing`, { token: this.token });
  }

  listRelayGroups() {
    return requestJson(`${AIRELAY_BASE}/admin/groups`, { token: this.token });
  }
}
