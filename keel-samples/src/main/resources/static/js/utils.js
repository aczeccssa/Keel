export function payloadOf(result) {
    const body = result && typeof result === "object" ? result.data : null;
    if (body && typeof body === "object" && "code" in body && "data" in body) {
        return body.data;
    }
    return body;
}

export async function requestJson(url) {
    const response = await fetch(url);
    const text = await response.text();
    const parsed = text ? JSON.parse(text) : null;
    return payloadOf(parsed);
}

export function buildUrl(path, params = {}) {
    const url = new URL(path, window.location.origin);
    Object.entries(params).forEach(([key, value]) => {
        if (value !== null && value !== undefined && value !== "") {
            url.searchParams.set(key, String(value));
        }
    });
    return url.pathname + url.search;
}

export function sinceFromWindow(windowKey) {
    const now = Date.now();
    const mapping = {
        "15m": 15 * 60 * 1000,
        "1h": 60 * 60 * 1000,
        "6h": 6 * 60 * 60 * 1000,
        "24h": 24 * 60 * 60 * 1000
    };
    return now - (mapping[windowKey] || mapping["1h"]);
}

export function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
}

export function escapeHtml(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

export function formatTime(epochMs) {
    if (!epochMs) return "n/a";
    return new Date(epochMs).toLocaleTimeString([], {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false
    });
}

export function formatDate(epochMs) {
    if (!epochMs) return "n/a";
    return new Date(epochMs).toLocaleString([], { hour12: false });
}

export function formatBytes(bytes) {
    const value = Number(bytes || 0);
    if (value >= 1024 * 1024 * 1024) return `${(value / (1024 * 1024 * 1024)).toFixed(1)} GB`;
    if (value >= 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
    if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`;
    return `${value} B`;
}

export function formatDuration(trace) {
    if (trace.durationMs != null) return `${trace.durationMs} ms`;
    if (trace.startEpochMs && trace.endEpochMs) return `${Math.max(trace.endEpochMs - trace.startEpochMs, 0)} ms`;
    return "live";
}

export function formatPercent(value) {
    if (value == null || Number.isNaN(Number(value))) return "n/a";
    return `${Number(value).toFixed(1)}%`;
}

export function sum(items, key) {
    return (items || []).reduce((total, item) => total + Number(item && item[key] || 0), 0);
}
