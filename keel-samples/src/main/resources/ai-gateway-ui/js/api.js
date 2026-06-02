import { state } from './state.js';
import { refreshAuth, logout } from './auth.js';

export async function requestJson(url, options = {}) {
    const headers = { ...options.headers };
    if (state.accessToken) {
        headers['Authorization'] = `Bearer ${state.accessToken}`;
    }
    if (options.body && !headers['Content-Type']) {
        headers['Content-Type'] = 'application/json';
    }
    const resp = await fetch(url, { ...options, headers });
    if (resp.status === 401) {
        try {
            await refreshAuth();
            headers['Authorization'] = `Bearer ${state.accessToken}`;
            const retry = await fetch(url, { ...options, headers });
            if (!retry.ok) throw new Error(`HTTP ${retry.status}`);
            return retry.json();
        } catch {
            logout();
            throw new Error('Session expired');
        }
    }
    if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.error || `HTTP ${resp.status}`);
    }
    if (resp.status === 204) return null;
    return resp.json();
}

export async function postJson(url, body) {
    return requestJson(url, { method: 'POST', body: JSON.stringify(body) });
}

export async function putJson(url, body) {
    return requestJson(url, { method: 'PUT', body: JSON.stringify(body) });
}

export async function deleteJson(url) {
    return requestJson(url, { method: 'DELETE' });
}

export async function fetchWithAuth(url, options = {}) {
    const headers = { ...options.headers };
    if (state.accessToken) {
        headers['Authorization'] = `Bearer ${state.accessToken}`;
    }
    return fetch(url, { ...options, headers });
}
