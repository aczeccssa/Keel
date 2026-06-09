// Auth re-exports — the actual implementations live alongside api.js so the JWT
// refresh path can call them without circular imports.
export { loadStoredAuth, login, register, refreshAuth, logout } from './api.js';
import { API } from './config.js';
import { state } from './state.js';

const TOKEN_KEY = 'cp_token';
const REFRESH_KEY = 'cp_refresh';
const CUSTOMER_KEY = 'cp_customer';

/** OAuth stub — pops a small prompt to fake an Apple/Google/GitHub sign-in. */
export async function oauthStub(provider) {
    const email = prompt(`Mock ${provider} email:`);
    if (!email) return null;
    const displayName = email.split('@')[0];
    const r = await fetch(`${API.customerPortal}/v1/customer/auth/oauth/stub`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider, email, displayName }),
    });
    if (!r.ok) throw new Error((await r.json().catch(() => ({}))).message || 'OAuth stub failed');
    const data = await r.json();
    state.accessToken = data.accessToken;
    state.refreshToken = data.refreshToken;
    state.loggedIn = true;
    state.customer = { customerId: data.customerId, email: data.email, displayName: data.displayName };
    localStorage.setItem(TOKEN_KEY, data.accessToken);
    if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken);
    localStorage.setItem(CUSTOMER_KEY, JSON.stringify(state.customer));
    return data;
}
