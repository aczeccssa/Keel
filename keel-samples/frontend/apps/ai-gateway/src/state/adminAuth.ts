import type { AdminAuthResponse } from '../api/aiGatewayApi';

const STORAGE_KEY = 'keel-ai-gateway-auth';

export interface AdminAuthState {
  accessToken: string | null;
  refreshToken: string | null;
  email: string | null;
}

export function loadAdminAuth(): AdminAuthState {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return { accessToken: null, refreshToken: null, email: null };
  try {
    const parsed = JSON.parse(raw) as AdminAuthState;
    return { accessToken: parsed.accessToken ?? null, refreshToken: parsed.refreshToken ?? null, email: parsed.email ?? null };
  } catch {
    return { accessToken: null, refreshToken: null, email: null };
  }
}

export function saveAdminAuth(response: AdminAuthResponse): AdminAuthState {
  const state = {
    accessToken: response.accessToken,
    refreshToken: response.refreshToken,
    email: response.user?.email ?? null
  };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  return state;
}

export function clearAdminAuth(): AdminAuthState {
  localStorage.removeItem(STORAGE_KEY);
  return { accessToken: null, refreshToken: null, email: null };
}
