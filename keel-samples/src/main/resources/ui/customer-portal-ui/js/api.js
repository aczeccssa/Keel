import { state } from './state.js';
import { API } from './config.js';

export async function requestJson(url, options = {}) {
    const headers = { ...options.headers };
    if (state.accessToken) headers['Authorization'] = `Bearer ${state.accessToken}`;
    if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
    const resp = await fetch(url, { ...options, headers });
    if (resp.status === 401) {
        try {
            await refreshAuth();
            headers['Authorization'] = `Bearer ${state.accessToken}`;
            const retry = await fetch(url, { ...options, headers });
            if (!retry.ok) throw new Error(`HTTP ${retry.status}`);
            return retry.json();
        } catch { logout(); throw new Error('Session expired'); }
    }
    if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.message || err.error || `HTTP ${resp.status}`);
    }
    if (resp.status === 204) return null;
    return resp.json();
}

export async function postJson(url, body) { return requestJson(url, { method: 'POST', body: JSON.stringify(body) }); }
export async function putJson(url, body) { return requestJson(url, { method: 'PUT', body: JSON.stringify(body) }); }
export async function deleteJson(url) { return requestJson(url, { method: 'DELETE' }); }
export async function getJson(url) { return requestJson(url); }

/* ---- auth ---- */

const TOKEN_KEY = 'cp_token';
const REFRESH_KEY = 'cp_refresh';
const CUSTOMER_KEY = 'cp_customer';

export function loadStoredAuth() {
    const token = localStorage.getItem(TOKEN_KEY);
    const refresh = localStorage.getItem(REFRESH_KEY);
    const customer = localStorage.getItem(CUSTOMER_KEY);
    if (token) {
        state.accessToken = token;
        state.refreshToken = refresh;
        state.customer = customer ? JSON.parse(customer) : null;
        state.loggedIn = true;
    }
}

export async function login(email, password) {
    const resp = await fetch(`${API.customerPortal}/v1/customer/auth/login`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
    });
    if (!resp.ok) { const e = await resp.json().catch(() => ({})); throw new Error(e.message || 'Login failed'); }
    const data = await resp.json();
    applyAuth(data);
    return data;
}

export async function register(email, password, displayName) {
    const resp = await fetch(`${API.customerPortal}/v1/customer/auth/register`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password, displayName }),
    });
    if (!resp.ok) { const e = await resp.json().catch(() => ({})); throw new Error(e.message || 'Registration failed'); }
    const data = await resp.json();
    applyAuth(data);
    return data;
}

export async function refreshAuth() {
    if (!state.refreshToken) throw new Error('No refresh token');
    const resp = await fetch(`${API.customerPortal}/v1/customer/auth/refresh`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: state.refreshToken }),
    });
    if (!resp.ok) throw new Error('Refresh failed');
    const data = await resp.json();
    applyAuth(data);
    return data;
}

export function logout() {
    state.accessToken = null; state.refreshToken = null; state.loggedIn = false; state.customer = null;
    localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(REFRESH_KEY); localStorage.removeItem(CUSTOMER_KEY);
    window.location.hash = 'login';
}

function applyAuth(data) {
    state.accessToken = data.accessToken;
    state.refreshToken = data.refreshToken;
    state.loggedIn = true;
    state.customer = { customerId: data.customerId, email: data.email, displayName: data.displayName };
    localStorage.setItem(TOKEN_KEY, data.accessToken);
    if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken);
    localStorage.setItem(CUSTOMER_KEY, JSON.stringify(state.customer));
}
