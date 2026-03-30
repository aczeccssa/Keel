export function healthTone(status) {
    const value = (status || "").toUpperCase();
    if (value === "ERROR" || value === "UNREACHABLE" || value === "FAILED") return "err";
    if (value === "DEGRADED" || value === "WARN" || value === "STOPPED") return "warn";
    if (value === "HEALTHY" || value === "RUNNING" || value === "OK" || value === "INFO") return "ok";
    return "neutral";
}

export function healthColor(status) {
    const tone = healthTone(status);
    if (tone === "err") return "#c62828";
    if (tone === "warn") return "#c56a1b";
    if (tone === "ok") return "#13824f";
    return "#64748b";
}

export function nodeLabel(node) {
    return node && (node.label || node.pluginId || node.id) || "Unknown";
}
