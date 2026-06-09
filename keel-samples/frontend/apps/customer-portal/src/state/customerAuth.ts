import type { CustomerAuthResponse } from '../api/customerPortalApi';

const STORAGE_KEY = 'keel-customer-portal-auth';

export interface CustomerAuthState {
  accessToken: string | null;
  refreshToken: string | null;
  email: string | null;
}

export function loadCustomerAuth(): CustomerAuthState {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return { accessToken: null, refreshToken: null, email: null };
  try {
    const parsed = JSON.parse(raw) as CustomerAuthState;
    return { accessToken: parsed.accessToken ?? null, refreshToken: parsed.refreshToken ?? null, email: parsed.email ?? null };
  } catch {
    return { accessToken: null, refreshToken: null, email: null };
  }
}

export function saveCustomerAuth(response: CustomerAuthResponse): CustomerAuthState {
  const state = {
    accessToken: response.accessToken,
    refreshToken: response.refreshToken,
    email: response.email ?? null
  };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  return state;
}

export function clearCustomerAuth(): CustomerAuthState {
  localStorage.removeItem(STORAGE_KEY);
  return { accessToken: null, refreshToken: null, email: null };
}
