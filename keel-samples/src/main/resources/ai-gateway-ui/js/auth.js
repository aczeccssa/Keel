import { state } from './state.js';
import { API } from './config.js';

const TOKEN_KEY = 'ai_gateway_token';
const REFRESH_KEY = 'ai_gateway_refresh';

export function loadStoredAuth() {
    const token = localStorage.getItem(TOKEN_KEY);
    const refresh = localStorage.getItem(REFRESH_KEY);
    if (token) {
        state.accessToken = token;
        state.refreshToken = refresh;
        state.loggedIn = true;
    }
}

export async function login(email, password) {
    const resp = await fetch(`${API.account}/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
    });
    if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.error || `Login failed (${resp.status})`);
    }
    const data = await resp.json();
    applyAuth(data);
    return data;
}

export async function register(email, password, displayName) {
    const resp = await fetch(`${API.account}/v1/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password, displayName }),
    });
    if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.error || `Register failed (${resp.status})`);
    }
    const data = await resp.json();
    applyAuth(data);
    return data;
}

export async function refreshAuth() {
    if (!state.refreshToken) throw new Error('No refresh token');
    const resp = await fetch(`${API.account}/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: state.refreshToken }),
    });
    if (!resp.ok) throw new Error('Refresh failed');
    const data = await resp.json();
    applyAuth(data);
    return data;
}

export function logout() {
    state.accessToken = null;
    state.refreshToken = null;
    state.loggedIn = false;
    state.user = null;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
}

function applyAuth(data) {
    state.accessToken = data.accessToken;
    state.refreshToken = data.refreshToken;
    state.loggedIn = true;
    state.user = data.user || null;
    localStorage.setItem(TOKEN_KEY, data.accessToken);
    if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken);
}
