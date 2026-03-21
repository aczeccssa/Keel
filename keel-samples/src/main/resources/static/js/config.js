export const API_BASE = '/api';
// export const API_BASE = 'http://localhost:8080/api';

export const TABS = [
    { id: "topology", label: "Topology", note: "Graph + selected node" },
    { id: "traces", label: "Traces", note: "Grouped waterfall" },
    { id: "logs", label: "Logs", note: "Structured explorer" },
    { id: "nodes", label: "Nodes", note: "Cluster inventory" },
    { id: "metrics", label: "Metrics", note: "Runtime snapshot" },
    { id: "openapi", label: "OpenAPI", note: "Contract explorer" }
];
export const VIEWBOX = 1000;
export const LOG_LIMIT = 80;
export const REFRESH_DEBOUNCE_MS = 220;
export const SNAPSHOT_WINDOW_MS = 60 * 60 * 1000;
export const REFRESH_INTERVALS = [
    { label: "1s", ms: 1_000 },
    { label: "5s", ms: 5_000 },
    { label: "10s", ms: 10_000 },
    { label: "30s", ms: 30_000 },
    { label: "1min", ms: 60_000 },
    { label: "5min", ms: 300_000 }
];
