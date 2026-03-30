import { KeelElement } from './base/KeelElement.js';
import { escapeHtml, formatBytes, formatPercent, formatTime } from '../utils.js';
import { healthTone, nodeLabel } from '../formatters.js';

function metricText(value, suffix = '%') {
    if (value == null || Number.isNaN(Number(value))) return 'n/a';
    return `${Math.round(Number(value))}${suffix}`;
}

function bytesText(value) {
    if (value == null || Number.isNaN(Number(value))) return 'n/a';
    return formatBytes(value);
}

function statusLabel(item) {
    const health = item && item.node ? (item.node.healthState || item.node.lifecycleState || 'UNKNOWN') : 'UNKNOWN';
    const h = health.toUpperCase();
    if (h === 'HEALTHY') return 'Healthy';
    if (h === 'RUNNING') return 'Active';
    if (h === 'DEGRADED') return 'Degraded';
    if (h === 'FAILED' || h === 'ERROR') return 'Failed';
    if (h === 'UNREACHABLE') return 'Unreachable';
    if (h === 'STOPPED') return 'Stopped';
    return health.replace(/_/g, ' ');
}

function toneOf(item) {
    return healthTone(item && item.node ? (item.node.healthState || item.node.lifecycleState) : 'UNKNOWN');
}

function statusValue(value, field) {
    if (value == null) return { tone: 'neutral', label: 'n/a' };
    const v = String(value).toUpperCase();
    const n = Number(value);

    let tone;
    let label;

    switch (field) {
    case 'runtimeMode':
        if (v === 'IN_PROCESS') { tone = 'ok'; label = 'In-Process'; }
        else if (v === 'EXTERNAL_JVM') { tone = 'warn'; label = 'External JVM'; }
        else if (v === 'STOPPED' || v === 'TERMINATED') { tone = 'neutral'; label = v; }
        else { tone = 'neutral'; label = String(value); }
        break;
    case 'lifecycle':
        if (v === 'RUNNING') { tone = 'ok'; label = 'Active'; }
        else if (v === 'DEGRADED') { tone = 'warn'; label = 'Degraded'; }
        else if (v === 'FAILED' || v === 'ERROR' || v === 'UNREACHABLE') { tone = 'err'; label = v; }
        else if (v === 'STOPPED' || v === 'TERMINATED') { tone = 'neutral'; label = v; }
        else { tone = 'neutral'; label = String(value); }
        break;
    case 'health':
        if (v === 'HEALTHY' || v === 'OK') { tone = 'ok'; label = 'Healthy'; }
        else if (v === 'DEGRADED' || v === 'WARN') { tone = 'warn'; label = 'Degraded'; }
        else if (v === 'FAILED' || v === 'ERROR' || v === 'UNREACHABLE') { tone = 'err'; label = v; }
        else { tone = 'neutral'; label = String(value); }
        break;
    case 'pid':
        tone = 'neutral';
        label = String(value);
        break;
    case 'processAlive':
        if (v === 'TRUE' || v === 'TRUE') { tone = 'ok'; label = 'Alive'; }
        else if (v === 'FALSE') { tone = 'err'; label = 'Dead'; }
        else { tone = 'neutral'; label = String(value); }
        break;
    case 'cpu':
    case 'memory':
        if (n >= 85) { tone = 'err'; label = `${Math.round(n)}%`; }
        else if (n >= 60) { tone = 'warn'; label = `${Math.round(n)}%`; }
        else { tone = 'ok'; label = `${Math.round(n)}%`; }
        break;
    case 'errors':
        if (n > 0) { tone = 'err'; label = String(value); }
        else { tone = 'ok'; label = String(value); }
        break;
    case 'inflight':
        if (n >= 10) { tone = 'err'; label = String(value); }
        else if (n >= 5) { tone = 'warn'; label = String(value); }
        else { tone = 'ok'; label = String(value); }
        break;
    case 'queueDepth':
        if (n >= 10) { tone = 'err'; label = String(value); }
        else if (n >= 5) { tone = 'warn'; label = String(value); }
        else { tone = 'neutral'; label = String(value); }
        break;
    case 'droppedLogs':
        if (n > 0) { tone = 'err'; label = String(value); }
        else { tone = 'ok'; label = String(value); }
        break;
    case 'healthLatency':
    case 'adminLatency':
        if (n >= 500) { tone = 'err'; label = `${n}ms`; }
        else if (n >= 250) { tone = 'warn'; label = `${n}ms`; }
        else { tone = 'ok'; label = `${n}ms`; }
        break;
    default:
        tone = 'neutral';
        label = String(value);
    }

    return { tone, label };
}

function summaryLatency(item) {
    return item && item.summary ? (item.summary.lastHealthLatencyMs ?? item.summary.lastAdminLatencyMs) : null;
}

function actionLabel(action) {
    switch (action) {
    case 'inspect': return 'Inspect';
    case 'start': return 'Start';
    case 'stop': return 'Stop';
    case 'reload': return 'Reload';
    default: return action || 'Open';
    }
}

function detailLine(item) {
    const left = item.asset.address || item.node.id || 'n/a';
    const right = item.asset.zone || item.asset.region || item.node.runtimeMode || 'n/a';
    return `${left} • ${right}`;
}

function lifecycleAction(item) {
    return item && item.secondaryAction ? item.secondaryAction : null;
}

export class PanelNodes extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <style>
                .panel-root {
                    min-height: 100%;
                    overflow-y: auto;
                    padding: 32px 48px 40px;
                    background: transparent;
                }
                .header {
                    display: flex;
                    justify-content: space-between;
                    align-items: flex-end;
                    gap: 24px;
                    margin-bottom: 40px;
                }
                .title {
                    font-family: "Newsreader", serif;
                    font-size: 42px;
                    line-height: 1;
                    color: var(--ink);
                    margin: 0 0 12px;
                    font-weight: 500;
                }
                .copy {
                    margin: 0;
                    max-width: 760px;
                    color: var(--muted);
                    font-size: 14px;
                    line-height: 1.6;
                }
                .pill-row {
                    display: flex;
                    gap: 12px;
                    flex-wrap: wrap;
                }
                .pill {
                    display: inline-flex;
                    align-items: center;
                    gap: 10px;
                    padding: 10px 16px;
                    border-radius: 14px;
                    background: var(--panel);
                    color: var(--ink);
                    border: 1px solid var(--line);
                    font-size: 11px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                }
                .pill-dot {
                    width: 9px;
                    height: 9px;
                    border-radius: 999px;
                    display: inline-block;
                }
                .pill-dot.ok { background: var(--green); }
                .pill-dot.warn { background: var(--amber); }
                .overview-grid {
                    display: grid;
                    grid-template-columns: minmax(0, 2fr) minmax(320px, 1fr);
                    gap: 24px;
                    margin-bottom: 28px;
                }
                .card {
                    background: rgba(255, 255, 255, 0.74);
                    backdrop-filter: blur(12px);
                    border: 1px solid rgba(117, 119, 124, 0.12);
                    border-radius: 20px;
                    box-shadow: 0 12px 40px rgba(26, 28, 26, 0.04);
                    padding: 24px;
                    min-width: 0;
                }
                .card.dark {
                    background: linear-gradient(135deg, #1a2233 0%, #24334d 100%);
                    border-color: rgba(219, 226, 249, 0.14);
                    color: #fff;
                }
                .card.dark .section-title,
                .card.dark .hero-copy,
                .card.dark .hero-copy p,
                .card.dark .hero-stat-label,
                .card.dark .hero-stat-value { color: #fff; }
                .section-title {
                    margin: 0;
                    font-family: "Newsreader", serif;
                    color: var(--ink);
                    font-size: 26px;
                    line-height: 1.1;
                    font-weight: 600;
                }
                .section-title.small { font-size: 22px; }
                .hero-copy p {
                    margin: 12px 0 0;
                    max-width: 560px;
                    font-size: 14px;
                    line-height: 1.6;
                    color: rgba(255, 255, 255, 0.82);
                }
                .hero-stats {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 24px;
                    margin-top: 28px;
                }
                .hero-stat-label {
                    display: block;
                    margin-bottom: 6px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                    color: rgba(255, 255, 255, 0.68);
                }
                .hero-stat-value {
                    font-family: "Newsreader", serif;
                    font-size: 28px;
                    font-style: italic;
                    color: #fff;
                }
                .featured {
                    display: flex;
                    flex-direction: column;
                    justify-content: space-between;
                    gap: 16px;
                }
                .featured-icon {
                    width: 60px;
                    height: 60px;
                    border-radius: 999px;
                    background: rgba(111, 251, 190, 0.18);
                    color: var(--green);
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 24px;
                    font-weight: 700;
                }
                .subcopy {
                    margin: 6px 0 0;
                    color: var(--muted);
                    font-size: 12px;
                    line-height: 1.5;
                }
                .featured-box {
                    width: 100%;
                    border-radius: 14px;
                    background: var(--bg);
                    padding: 14px 16px;
                }
                .featured-row {
                    display: flex;
                    justify-content: space-between;
                    gap: 12px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                }
                .summary-list {
                    display: grid;
                    gap: 12px;
                    margin-top: 18px;
                }
                .summary-row {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 12px;
                    padding: 14px;
                    border-radius: 14px;
                    background: var(--bg);
                }
                .summary-left {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                    min-width: 0;
                }
                .summary-icon {
                    width: 28px;
                    height: 28px;
                    border-radius: 999px;
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 15px;
                    font-weight: 700;
                }
                .summary-icon.ok { color: var(--green); background: rgba(92, 174, 98, 0.12); }
                .summary-icon.warn { color: var(--amber); background: rgba(197, 106, 27, 0.12); }
                .summary-icon.err { color: var(--red); background: rgba(198, 40, 40, 0.12); }
                .summary-label { font-size: 12px; font-weight: 700; color: var(--ink); }
                .summary-value { font-size: 11px; color: var(--muted); white-space: nowrap; }
                .inventory-head {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    gap: 16px;
                    margin: 8px 0 16px;
                }
                .inventory-grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
                    gap: 16px;
                }
                .node-card {
                    position: relative;
                    display: flex;
                    flex-direction: column;
                    gap: 18px;
                    padding: 22px;
                    border-radius: 18px;
                    border: 1px solid rgba(117, 119, 124, 0.14);
                    background: rgba(255, 255, 255, 0.78);
                    box-shadow: 0 10px 30px rgba(26, 28, 26, 0.04);
                    cursor: pointer;
                    transition: transform 120ms ease, border-color 120ms ease, box-shadow 120ms ease;
                }
                .node-card:hover {
                    transform: translateY(-1px);
                    border-color: rgba(5, 12, 27, 0.24);
                    box-shadow: 0 14px 30px rgba(26, 28, 26, 0.06);
                }
                .node-card.is-selected {
                    border-color: rgba(5, 12, 27, 0.5);
                    box-shadow: 0 0 0 2px rgba(5, 12, 27, 0.08), 0 12px 30px rgba(26, 28, 26, 0.06);
                }
                .node-card-head {
                    display: flex;
                    justify-content: space-between;
                    gap: 12px;
                    align-items: flex-start;
                }
                .eyebrow {
                    display: block;
                    margin-bottom: 6px;
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                }
                .node-name {
                    margin: 0;
                    font-family: "Newsreader", serif;
                    font-size: 22px;
                    line-height: 1.1;
                    color: var(--ink);
                    font-weight: 600;
                }
                .status {
                    border-radius: 999px;
                    padding: 6px 12px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                    white-space: nowrap;
                }
                .status.ok {
                    background: rgba(92, 174, 98, 0.14);
                    color: #2d7a3a;
                    box-shadow: 0 0 10px rgba(92, 174, 98, 0.3), 0 0 20px rgba(92, 174, 98, 0.1);
                }
                .status.ok::before {
                    content: '';
                    display: inline-block;
                    width: 6px;
                    height: 6px;
                    border-radius: 50%;
                    background: #2d7a3a;
                    margin-right: 6px;
                    vertical-align: middle;
                    animation: statusPulse 2s ease-in-out infinite;
                }
                .status.warn {
                    background: rgba(197, 106, 27, 0.14);
                    color: #b8590a;
                    box-shadow: 0 0 8px rgba(197, 106, 27, 0.2);
                }
                .status.warn::before {
                    content: '';
                    display: inline-block;
                    width: 6px;
                    height: 6px;
                    border-radius: 50%;
                    background: #b8590a;
                    margin-right: 6px;
                    vertical-align: middle;
                }
                .status.err {
                    background: rgba(198, 40, 40, 0.14);
                    color: #c01c1c;
                    box-shadow: 0 0 8px rgba(198, 40, 40, 0.2);
                }
                .status.err::before {
                    content: '';
                    display: inline-block;
                    width: 6px;
                    height: 6px;
                    border-radius: 50%;
                    background: #c01c1c;
                    margin-right: 6px;
                    vertical-align: middle;
                }
                .status.neutral {
                    background: rgba(100, 116, 139, 0.12);
                    color: #4a5568;
                    box-shadow: 0 0 6px rgba(100, 116, 139, 0.15);
                }
                .status.neutral::before {
                    content: '';
                    display: inline-block;
                    width: 6px;
                    height: 6px;
                    border-radius: 50%;
                    background: #4a5568;
                    margin-right: 6px;
                    vertical-align: middle;
                }
                @keyframes statusPulse {
                    0%, 100% { opacity: 1; box-shadow: 0 0 4px rgba(45, 122, 58, 0.6); }
                    50% { opacity: 0.6; box-shadow: 0 0 12px rgba(45, 122, 58, 0.9); }
                }
                .detail-status {
                    display: inline-flex;
                    align-items: center;
                    gap: 5px;
                    border-radius: 999px;
                    padding: 3px 10px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.06em;
                    white-space: nowrap;
                    width: fit-content;
                }
                .detail-status.ok {
                    background: rgba(92, 174, 98, 0.14);
                    color: #2d7a3a;
                    box-shadow: 0 0 8px rgba(92, 174, 98, 0.25);
                }
                .detail-status.ok::before {
                    content: '';
                    display: inline-block;
                    width: 5px;
                    height: 5px;
                    border-radius: 50%;
                    background: #2d7a3a;
                    animation: statusPulse 2s ease-in-out infinite;
                }
                .detail-status.warn {
                    background: rgba(197, 106, 27, 0.14);
                    color: #b8590a;
                    box-shadow: 0 0 6px rgba(197, 106, 27, 0.18);
                }
                .detail-status.warn::before {
                    content: '';
                    display: inline-block;
                    width: 5px;
                    height: 5px;
                    border-radius: 50%;
                    background: #b8590a;
                }
                .detail-status.err {
                    background: rgba(198, 40, 40, 0.14);
                    color: #c01c1c;
                    box-shadow: 0 0 6px rgba(198, 40, 40, 0.18);
                }
                .detail-status.err::before {
                    content: '';
                    display: inline-block;
                    width: 5px;
                    height: 5px;
                    border-radius: 50%;
                    background: #c01c1c;
                }
                .detail-status.neutral {
                    background: rgba(100, 116, 139, 0.12);
                    color: #4a5568;
                    box-shadow: 0 0 4px rgba(100, 116, 139, 0.12);
                }
                .detail-status.neutral::before {
                    content: '';
                    display: inline-block;
                    width: 5px;
                    height: 5px;
                    border-radius: 50%;
                    background: #4a5568;
                }
                /* Compact inline variant for detail card — uses margin instead of padding */
                .detail-status-sm {
                    display: inline-flex;
                    align-items: center;
                    gap: 5px;
                    border-radius: 999px;
                    margin: 2px 0;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.06em;
                    white-space: nowrap;
                }
                .detail-status-sm.ok {
                    background: rgba(92, 174, 98, 0.14);
                    color: #2d7a3a;
                    box-shadow: 0 0 6px rgba(92, 174, 98, 0.2);
                    padding: 1px 8px;
                }
                .detail-status-sm.ok::before {
                    content: '';
                    display: inline-block;
                    width: 5px;
                    height: 5px;
                    border-radius: 50%;
                    background: #2d7a3a;
                    animation: statusPulse 2s ease-in-out infinite;
                }
                .detail-status-sm.warn {
                    background: rgba(197, 106, 27, 0.14);
                    color: #b8590a;
                    box-shadow: 0 0 4px rgba(197, 106, 27, 0.15);
                    padding: 1px 8px;
                }
                .detail-status-sm.warn::before {
                    content: '';
                    display: inline-block;
                    width: 5px;
                    height: 5px;
                    border-radius: 50%;
                    background: #b8590a;
                }
                .detail-status-sm.err {
                    background: rgba(198, 40, 40, 0.14);
                    color: #c01c1c;
                    box-shadow: 0 0 4px rgba(198, 40, 40, 0.15);
                    padding: 1px 8px;
                }
                .detail-status-sm.err::before {
                    content: '';
                    display: inline-block;
                    width: 5px;
                    height: 5px;
                    border-radius: 50%;
                    background: #c01c1c;
                }
                .detail-status-sm.neutral {
                    background: rgba(100, 116, 139, 0.12);
                    color: #4a5568;
                    box-shadow: 0 0 3px rgba(100, 116, 139, 0.1);
                    padding: 1px 8px;
                }
                .detail-status-sm.neutral::before {
                    content: '';
                    display: inline-block;
                    width: 5px;
                    height: 5px;
                    border-radius: 50%;
                    background: #4a5568;
                }
                @keyframes statusPulse {
                    0%, 100% { opacity: 1; box-shadow: 0 0 4px rgba(45, 122, 58, 0.6); }
                    50% { opacity: 0.6; box-shadow: 0 0 12px rgba(45, 122, 58, 0.9); }
                }
                .metric-stack {
                    display: grid;
                    gap: 12px;
                }
                .metric-row { display: grid; gap: 8px; }
                .metric-meta {
                    display: flex;
                    justify-content: space-between;
                    gap: 12px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                    color: var(--ink);
                }
                .metric-bar {
                    width: 100%;
                    height: 8px;
                    border-radius: 999px;
                    background: var(--line);
                    overflow: hidden;
                }
                .metric-bar > span {
                    display: block;
                    height: 100%;
                    border-radius: inherit;
                    background: var(--ink);
                }
                .metric-bar > span.ok { background: var(--green); }
                .metric-bar > span.warn { background: var(--amber); }
                .metric-bar > span.err { background: var(--red); }
                .node-meta {
                    display: grid;
                    gap: 8px;
                    color: var(--muted);
                    font-size: 11px;
                }
                .button-row {
                    display: flex;
                    gap: 10px;
                    margin-top: auto;
                }
                .btn {
                    appearance: none;
                    border: 1px solid var(--ink);
                    background: var(--ink);
                    color: #fff;
                    border-radius: 999px;
                    padding: 10px 14px;
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                    cursor: pointer;
                    flex: 1;
                }
                .btn.secondary {
                    background: transparent;
                    color: var(--ink);
                    border-color: rgba(5, 12, 27, 0.18);
                }
                .detail-card {
                    margin-top: 28px;
                    display: grid;
                    gap: 20px;
                }
                .detail-head {
                    display: flex;
                    justify-content: space-between;
                    gap: 16px;
                    align-items: flex-start;
                }
                .detail-title {
                    margin: 0;
                    font-family: "Newsreader", serif;
                    font-size: 30px;
                    line-height: 1.1;
                    color: var(--ink);
                }
                .detail-grid {
                    display: grid;
                    grid-template-columns: repeat(4, minmax(0, 1fr));
                    gap: 16px;
                }
                .detail-block {
                    border-radius: 16px;
                    background: var(--bg);
                    padding: 16px;
                    display: grid;
                    gap: 12px;
                }
                .detail-label {
                    font-size: 10px;
                    font-weight: 700;
                    text-transform: uppercase;
                    letter-spacing: 0.1em;
                    color: var(--muted);
                }
                .detail-value {
                    font-size: 14px;
                    font-weight: 600;
                    color: var(--ink);
                    word-break: break-word;
                }
                .detail-kv {
                    display: grid;
                    gap: 10px;
                }
                .detail-row {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    gap: 16px;
                    font-size: 12px;
                    color: var(--ink);
                }
                .detail-row span:first-child {
                    color: var(--muted);
                    font-weight: 600;
                }
                .footer {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    gap: 16px;
                    margin-top: 28px;
                    color: var(--muted);
                }
                .empty {
                    padding: 24px;
                    border-radius: 20px;
                    background: var(--panel);
                    color: var(--muted);
                    border: 1px solid var(--line);
                }
                @media (max-width: 1200px) {
                    .overview-grid,
                    .detail-grid { grid-template-columns: 1fr; }
                }
                @media (max-width: 900px) {
                    .header,
                    .footer,
                    .inventory-head { flex-direction: column; align-items: flex-start; }
                    .panel-root { padding: 24px; }
                }
            </style>
            <div class="panel-root">
                <div data-ref="content"></div>
            </div>
        `;
    }

    afterMount() {
        this.shadowRoot.addEventListener('click', (event) => {
            const button = event.target.closest('[data-node-action]');
            if (button) {
                event.stopPropagation();
                const action = button.dataset.nodeAction;
                const nodeId = button.dataset.nodeId;
                const pluginId = button.dataset.pluginId;
                if (action === 'inspect') {
                    this.emit('keel:node-select', { nodeId });
                    return;
                }
                if (pluginId && action) {
                    this.emit('keel:plugin-toggle', { pluginId, action });
                }
                return;
            }

            const card = event.target.closest('[data-node-select]');
            if (!card) return;
            this.emit('keel:node-select', { nodeId: card.dataset.nodeSelect });
        });
    }

    render(appState) {
        this.ensureInitialized();
        const dashboard = appState.nodeDashboard;
        if (!dashboard || !dashboard.items || !dashboard.items.length) {
            this.refs.content.innerHTML = '<div class="empty">Nodes dashboard not loaded yet.</div>';
            return;
        }

        const items = dashboard.items;
        const selected = items.find((item) => item.node.id === appState.selectedNodeId) || items[0];
        const featured = items.find((item) => item.node.id === dashboard.featuredNodeId) || items[0];
        const summaryItems = items.slice(0, 4);
        const recentTraces = appState.nodeOverview ? appState.nodeOverview.recentTraceCount : 'n/a';
        const recentFlows = appState.nodeOverview ? appState.nodeOverview.recentFlowCount : 'n/a';
        const droppedLogs = appState.nodeOverview ? appState.nodeOverview.droppedLogCount : 'n/a';

        this.refs.content.innerHTML = `
            <header class="header">
                <div>
                    <h1 class="title">Cluster Nodes</h1>
                    <p class="copy">
                        Real-time inventory of all current kernel and plugin runtimes. Click any node card to inspect live details
                        without leaving this page.
                    </p>
                </div>
                <div class="pill-row">
                    <span class="pill"><span class="pill-dot ok"></span><span>${dashboard.activeCount} Active Nodes</span></span>
                    <span class="pill"><span class="pill-dot warn"></span><span>${dashboard.degradedCount} Degraded</span></span>
                </div>
            </header>

            <section class="overview-grid">
                <div class="card dark">
                    <div class="hero-copy">
                        <div>
                            <h3 class="section-title">Infrastructure Insight</h3>
                            <p>
                                Live runtime state sourced from the current kernel and plugin observability snapshots.
                                Current traffic is sparse, so this header now surfaces stable inventory/runtime counters instead of empty throughput slots.
                            </p>
                        </div>
                        <div class="hero-stats">
                            <div>
                                <span class="hero-stat-label">Active Nodes</span>
                                <span class="hero-stat-value">${dashboard.activeCount}</span>
                            </div>
                            <div>
                                <span class="hero-stat-label">Recent Traces</span>
                                <span class="hero-stat-value">${recentTraces}</span>
                            </div>
                            <div>
                                <span class="hero-stat-label">Recent Flows</span>
                                <span class="hero-stat-value">${recentFlows}</span>
                            </div>
                            <div>
                                <span class="hero-stat-label">Dropped Logs</span>
                                <span class="hero-stat-value">${droppedLogs}</span>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="card featured">
                    <div class="featured-icon">${featured.node.id === 'kernel' ? 'K' : 'N'}</div>
                    <div>
                        <h3 class="section-title small">${escapeHtml(nodeLabel(featured.node))}</h3>
                        <p class="subcopy">${escapeHtml(featured.asset.roleDescription || featured.asset.role || featured.node.runtimeMode || 'Node')}</p>
                    </div>
                    <div class="featured-box">
                        <div class="featured-row">
                            <span>Selected</span>
                            <span>${escapeHtml(selected.node.id)}</span>
                        </div>
                    </div>
                    <div class="summary-list">
                        ${summaryItems.map((item) => this.renderSummaryRow(item)).join('')}
                    </div>
                </div>
            </section>

            <section class="inventory-head">
                <div>
                    <h3 class="section-title">Node Inventory</h3>
                    <p class="subcopy">Showing all ${dashboard.pageTotal} current runtime nodes.</p>
                </div>
            </section>

            <section class="inventory-grid">
                ${items.map((item) => this.renderNodeCard(item, selected)).join('')}
            </section>

            <section class="card detail-card">
                ${this.renderDetail(selected)}
            </section>

            <footer class="footer">
                <span>Showing 1-${dashboard.pageTotal} of ${dashboard.pageTotal} cluster entities</span>
            </footer>
        `;
    }

    renderSummaryRow(item) {
        const tone = toneOf(item);
        const icon = tone === 'err' ? '!' : tone === 'warn' ? '!' : '✓';
        const rightText = item.degradationReason
            ? item.degradationReason
            : (summaryLatency(item) != null ? `${summaryLatency(item)}ms latency` : `${item.node.runtimeMode || 'n/a'}`);
        return `
            <div class="summary-row">
                <div class="summary-left">
                    <span class="summary-icon ${tone}">${icon}</span>
                    <span class="summary-label">${escapeHtml(item.asset.assetId || item.node.id)}</span>
                </div>
                <span class="summary-value">${escapeHtml(rightText)}</span>
            </div>
        `;
    }

    renderNodeCard(item, selected) {
        const tone = toneOf(item);
        const cpuPercent = item.resource.cpuPercent;
        const memPercent = item.resource.memoryPercent;
        const cpuTone = cpuPercent != null && Number(cpuPercent) >= 85 ? 'err' : tone;
        const memTone = memPercent != null && Number(memPercent) >= 85 ? 'err' : (memPercent != null && Number(memPercent) >= 60 ? 'warn' : tone);
        return `
            <article class="node-card ${selected.node.id === item.node.id ? 'is-selected' : ''}" data-node-select="${escapeHtml(item.node.id)}">
                <div class="node-card-head">
                    <div>
                        <span class="eyebrow">ID: ${escapeHtml(item.asset.assetId || item.node.id)}</span>
                        <h3 class="node-name">${escapeHtml(nodeLabel(item.node))}</h3>
                        <p class="subcopy">${escapeHtml(detailLine(item))}</p>
                    </div>
                    <span class="status ${tone}">${escapeHtml(statusLabel(item))}</span>
                </div>

                <div class="metric-stack">
                    <div class="metric-row">
                        <div class="metric-meta">
                            <span>CPU Usage</span>
                            <span>${escapeHtml(metricText(cpuPercent))}</span>
                        </div>
                        <div class="metric-bar"><span class="${cpuTone}" style="width:${cpuPercent == null ? 0 : Math.max(0, Math.min(100, Number(cpuPercent)))}%"></span></div>
                    </div>
                    <div class="metric-row">
                        <div class="metric-meta">
                            <span>Memory Allocation</span>
                            <span>${escapeHtml(metricText(memPercent))}</span>
                        </div>
                        <div class="metric-bar"><span class="${memTone}" style="width:${memPercent == null ? 0 : Math.max(0, Math.min(100, Number(memPercent)))}%"></span></div>
                    </div>
                </div>

                <div class="node-meta">
                    ${this.renderNodeMetaBadge('JVM', item.node.runtimeMode, 'runtimeMode')}
                    <span class="detail-status neutral" style="padding: 3px 8px; font-size: 9px;">PID ${escapeHtml(item.node.pid || 'n/a')}</span>
                    <div>${escapeHtml(item.degradationReason || 'No active degradation signal')}</div>
                </div>

                <div class="button-row">
                    <button
                        class="btn secondary"
                        type="button"
                        data-node-action="inspect"
                        data-node-id="${escapeHtml(item.node.id)}"
                        data-plugin-id="${escapeHtml(item.node.pluginId || '')}">
                        ${escapeHtml(actionLabel('inspect'))}
                    </button>
                    ${this.renderLifecycleButton(item)}
                </div>
            </article>
        `;
    }

    renderLifecycleButton(item) {
        const action = lifecycleAction(item);
        if (!action) return '';
        return `
            <button
                class="btn"
                type="button"
                data-node-action="${escapeHtml(action)}"
                data-node-id="${escapeHtml(item.node.id)}"
                data-plugin-id="${escapeHtml(item.node.pluginId || '')}">
                ${escapeHtml(actionLabel(action))}
            </button>
        `;
    }

    renderNodeMetaBadge(label, value, field) {
        const sv = statusValue(value, field);
        return `<span class="detail-status ${sv.tone}" title="${label}">${escapeHtml(sv.label)}</span>`;
    }

    renderDetailRow(label, value, statusField) {
        if (statusField) {
            const sv = statusValue(value, statusField);
            return `
                <div class="detail-row">
                    <span>${escapeHtml(label)}</span>
                    <span class="detail-status-sm ${sv.tone}">${escapeHtml(sv.label)}</span>
                </div>
            `;
        }
        return `
            <div class="detail-row">
                <span>${escapeHtml(label)}</span>
                <span class="detail-value">${escapeHtml(value == null ? 'n/a' : String(value))}</span>
            </div>
        `;
    }

    renderDetail(item) {
        const action = lifecycleAction(item);
        return `
            <div class="detail-head">
                <div>
                    <h3 class="detail-title">${escapeHtml(nodeLabel(item.node))}</h3>
                    <p class="subcopy">${escapeHtml(item.degradationReason || 'Inspecting live runtime state for the selected node.')}</p>
                </div>
                <div class="button-row" style="min-width:260px;">
                    <button
                        class="btn secondary"
                        type="button"
                        data-node-action="inspect"
                        data-node-id="${escapeHtml(item.node.id)}"
                        data-plugin-id="${escapeHtml(item.node.pluginId || '')}">
                        ${escapeHtml(actionLabel('inspect'))}
                    </button>
                    ${action ? `
                    <button
                        class="btn"
                        type="button"
                        data-node-action="${escapeHtml(action)}"
                        data-node-id="${escapeHtml(item.node.id)}"
                        data-plugin-id="${escapeHtml(item.node.pluginId || '')}">
                        ${escapeHtml(actionLabel(action))}
                    </button>` : ''}
                </div>
            </div>

            <div class="detail-grid">
                <div class="detail-block">
                    <div class="detail-label">Identity</div>
                    <div class="detail-kv">
                        ${this.renderDetailRow('Node ID', item.node.id)}
                        ${this.renderDetailRow('Plugin ID', item.node.pluginId || 'n/a')}
                        ${this.renderDetailRow('Kind', item.node.kind || 'n/a')}
                        ${this.renderDetailRow('Runtime', item.node.runtimeMode, 'runtimeMode')}
                        ${this.renderDetailRow('PID', item.node.pid || 'n/a', 'pid')}
                    </div>
                </div>

                <div class="detail-block">
                    <div class="detail-label">Lifecycle</div>
                    <div class="detail-kv">
                        ${this.renderDetailRow('Lifecycle', item.node.lifecycleState, 'lifecycle')}
                        ${this.renderDetailRow('Health', item.node.healthState, 'health')}
                        ${this.renderDetailRow('Process Alive', item.summary.processAlive, 'processAlive')}
                        ${this.renderDetailRow('Last Event', formatTime(item.summary.lastEventAtEpochMs))}
                    </div>
                </div>

                <div class="detail-block">
                    <div class="detail-label">Resources</div>
                    <div class="detail-kv">
                        ${this.renderDetailRow('CPU', item.resource.cpuPercent, 'cpu')}
                        ${this.renderDetailRow('Memory %', item.resource.memoryPercent, 'memory')}
                        ${this.renderDetailRow('Heap Used', bytesText(item.resource.memoryUsedBytes))}
                        ${this.renderDetailRow('Heap Max', bytesText(item.resource.memoryMaxBytes))}
                    </div>
                </div>

                <div class="detail-block">
                    <div class="detail-label">Traffic & Diagnostics</div>
                    <div class="detail-kv">
                        ${this.renderDetailRow('Recent Traces', item.summary.recentTraceCount)}
                        ${this.renderDetailRow('Recent Flows', item.summary.recentFlowCount)}
                        ${this.renderDetailRow('Errors', item.summary.errorCount, 'errors')}
                        ${this.renderDetailRow('Inflight', item.node.inflightInvocations || 0, 'inflight')}
                        ${this.renderDetailRow('Queue Depth', item.node.eventQueueDepth || 0, 'queueDepth')}
                        ${this.renderDetailRow('Dropped Logs', item.node.droppedLogCount || 0, 'droppedLogs')}
                        ${this.renderDetailRow('Health Latency', item.summary.lastHealthLatencyMs, 'healthLatency')}
                        ${this.renderDetailRow('Admin Latency', item.summary.lastAdminLatencyMs, 'adminLatency')}
                    </div>
                </div>
            </div>
        `;
    }
}

if (!customElements.get('keel-panel-nodes')) {
    customElements.define('keel-panel-nodes', PanelNodes);
}
