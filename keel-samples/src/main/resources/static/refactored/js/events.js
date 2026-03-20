import { state, els, selectedTraceGroup, logKey } from './state.js';
import { TABS, REFRESH_INTERVALS } from './config.js';
import { clamp, escapeHtml } from './utils.js';
import { renderChrome, renderPanels, renderTopologyPanel, renderNodesPanel, renderTracesPanel, renderLogsPanel } from './render.js';
import { refreshLogs, refreshMetrics } from './api.js';
import { connectStream, startStreamPoll } from './stream.js';
import { applyViewport } from './topology.js';

export function setActiveTab(tabId) {
    state.activeTab = TABS.some((tab) => tab.id === tabId) ? tabId : "topology";
    if (window.location.hash !== `#${state.activeTab}`) {
        window.location.hash = state.activeTab;
        return;
    }
    renderChrome();
    renderPanels();
}

export function hydrateHash() {
    const hash = window.location.hash.replace(/^#/, "");
    state.activeTab = TABS.some((tab) => tab.id === hash) ? hash : "topology";
}

export function downloadLogsJSON() {
    window.location.href = '/api/_system/logs/download';
}

export function downloadLogsText() {
    const items = state.logs.items || [];
    const text = items.map(e => {
        let line = (e.timestamp ? new Date(e.timestamp).toISOString() : '') + ' ' +
            (e.level || 'INFO') + ' [' + (e.source || '') + '] ' + (e.message || '');
        if (e.throwable) line += '\n' + e.throwable;
        return line;
    }).join('\n');
    const blob = new Blob([text], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'keel-logs.txt';
    a.click();
    URL.revokeObjectURL(a.href);
}

export function bindEvents() {
    window.addEventListener("hashchange", () => {
        hydrateHash();
        renderChrome();
        renderPanels();
    });

    document.addEventListener("click", (event) => {
        const nodeEl = event.target.closest("[data-node-id]");
        if (nodeEl) {
            state.selectedNodeId = nodeEl.dataset.nodeId;
            renderTopologyPanel();
            renderNodesPanel();
            return;
        }

        // 点击空白画布取消选择节点
        if (event.target.closest("#topology-stage") && !event.target.closest(".canvas-controls")) {
            state.selectedNodeId = null;
            renderTopologyPanel();
            renderNodesPanel();
        }

        const traceEl = event.target.closest("[data-trace-id]");
        if (traceEl) {
            state.selectedTraceId = traceEl.dataset.traceId;
            const group = selectedTraceGroup();
            state.selectedSpanId = group && group.spans[0] ? group.spans[0].spanId : null;
            renderTracesPanel();
            return;
        }
        const spanEl = event.target.closest("[data-span-id]");
        if (spanEl) {
            state.selectedSpanId = spanEl.dataset.spanId;
            renderTracesPanel();
            return;
        }
        const logEl = event.target.closest("[data-log-key]");
        if (logEl) {
            state.selectedLogKey = logEl.dataset.logKey;
            renderLogsPanel();
        }
    });

    document.getElementById("log-refresh-btn").addEventListener("click", async () => {
        state.logFilters.query = document.getElementById("log-query-input").value.trim();
        state.logFilters.level = document.getElementById("log-level-select").value;
        state.logFilters.source = document.getElementById("log-source-input").value.trim();
        state.logFilters.window = document.getElementById("log-window-select").value;
        await refreshLogs();
        await refreshMetrics();
    });

    function openIntervalPanel() {
        const cover = document.getElementById("refresh-interval-cover");
        const optionsEl = document.getElementById("refresh-interval-options");
        optionsEl.innerHTML = REFRESH_INTERVALS.map((opt) => `
                    <div class="refresh-interval-option ${state.refreshIntervalMs === opt.ms ? "is-active" : ""}"
                         data-ms="${opt.ms}">
                        ${opt.label}
                    </div>
                `).join("");
        cover.classList.add("is-open");
    }

    function closeIntervalPanel() {
        document.getElementById("refresh-interval-cover").classList.remove("is-open");
    }

    document.getElementById("connection-pill").addEventListener("click", () => {
        openIntervalPanel();
    });

    document.getElementById("refresh-interval-cover").addEventListener("click", (e) => {
        if (!e.target.closest(".refresh-interval-panel")) closeIntervalPanel();
    });

    document.getElementById("refresh-interval-options").addEventListener("click", (e) => {
        const option = e.target.closest(".refresh-interval-option");
        if (!option) return;
        const ms = parseInt(option.dataset.ms, 10);
        state.refreshIntervalMs = ms;
        startStreamPoll();
        closeIntervalPanel();
    });

    els.streamToggleBtn.addEventListener("click", () => {
        state.streamEnabled = !state.streamEnabled;
        if (state.streamEnabled) {
            connectStream();
        } else {
            if (state.eventSource) state.eventSource.close();
            state.eventSource = null;
            if (state.streamIntervalId !== null) {
                clearInterval(state.streamIntervalId);
                state.streamIntervalId = null;
            }
        }
        state.connectionState = state.streamEnabled ? "Connecting" : "Paused";
        renderChrome();
    });

    startStreamPoll();

    document.getElementById("zoom-in-btn").addEventListener("click", () => {
        state.zoom = clamp(state.zoom + 0.12, 0.65, 1.85);
        applyViewport();
    });
    document.getElementById("zoom-out-btn").addEventListener("click", () => {
        state.zoom = clamp(state.zoom - 0.12, 0.65, 1.85);
        applyViewport();
    });
    document.getElementById("layout-btn").addEventListener("click", () => {
        state.nodePositions = {};
        renderTopologyPanel();
    });
    document.getElementById("recenter-btn").addEventListener("click", () => {
        state.panX = 0;
        state.panY = 0;
        state.zoom = 1;
        applyViewport();
    });
    document.getElementById("recenter-kernel-btn").addEventListener("click", () => {
        state.selectedNodeId = "kernel";
        state.panX = 0;
        state.panY = 0;
        state.zoom = 1;
        setActiveTab("topology");
    });

    // ── 拖动阈值配置 ───────────────────────────────────────────────────
    const DRAG_THRESHOLD_PX = 5;

    els.topologyStage.addEventListener("pointerdown", (event) => {
        const nodeWrap = event.target.closest(".node-wrap");
        if (nodeWrap) {
            const nodeId = nodeWrap.dataset.nodeId;
            // 选中节点（立即生效）
            state.selectedNodeId = nodeId;
            renderTopologyPanel();
            renderNodesPanel();
            // 记录拖动起始状态
            state.dragState = {
                nodeId,
                startX: event.clientX,
                startY: event.clientY,
                dragging: false
            };
            els.topologyStage.setPointerCapture(event.pointerId);
            return;
        }

        // 空白画布 → 平移
        if (event.target.closest("#topology-viewport") || event.target.id === "topology-stage") {
            state.panState = {
                startX: event.clientX,
                startY: event.clientY,
                panX: state.panX,
                panY: state.panY
            };
        }
    });

    window.addEventListener("pointermove", (event) => {
        if (state.draggingNodeId || state.dragState || state.panState) {
            if (!state._dragRect) {
                state._dragRect = els.topologyStage.getBoundingClientRect();
            }
            state._pendingMove = event;
        }
    });

    window.addEventListener("pointercancel", () => {
        if (state.dragState) state.dragState = null;
        state.draggingNodeId = null;
        state.panState = null;
        state._pendingMove = null;
        state._dragRect = null;
    });

    window.addEventListener("pointerup", () => {
        if (state.dragState) state.dragState = null;
        state.draggingNodeId = null;
        state.panState = null;
        state._pendingMove = null;
        state._dragRect = null;
        // 拖动结束后同步连线位置
        if (state.activeTab === "topology") {
            renderTopologyPanel();
        }
    });

    // ── rAF tick ───────────────────────────────────────────────────────
    function updateNodeStyle(nodeId, x, y) {
        const wrap = document.querySelector(`.node-wrap[data-node-id="${CSS.escape(nodeId)}"]`);
        if (wrap) {
            wrap.style.left = `${x}%`;
            wrap.style.top = `${y}%`;
        }
    }

    function tick() {
        if (state._pendingMove) {
            const event = state._pendingMove;
            const rect = state._dragRect || els.topologyStage.getBoundingClientRect();
            state._dragRect = null;
            state._pendingMove = null;

            if (state.draggingNodeId) {
                // 已进入拖动模式 → 直接更新位置，不做全量渲染
                const x = ((event.clientX - rect.left) / rect.width) * 100;
                const y = ((event.clientY - rect.top) / rect.height) * 100;
                const clampedX = clamp(x, 8, 92);
                const clampedY = clamp(y, 10, 90);
                state.nodePositions[state.draggingNodeId] = { x: clampedX, y: clampedY };
                updateNodeStyle(state.draggingNodeId, clampedX, clampedY);
            } else if (state.dragState) {
                // 尚未确认拖动 → 检查移动阈值
                if (!state.dragState.dragging) {
                    const dx = event.clientX - state.dragState.startX;
                    const dy = event.clientY - state.dragState.startY;
                    if (Math.sqrt(dx * dx + dy * dy) >= DRAG_THRESHOLD_PX) {
                        state.dragState.dragging = true;
                        state.draggingNodeId = state.dragState.nodeId;
                    }
                } else {
                    // threshold 确认后首次进入此分支，立即同步位置
                    const x = ((event.clientX - rect.left) / rect.width) * 100;
                    const y = ((event.clientY - rect.top) / rect.height) * 100;
                    const clampedX = clamp(x, 8, 92);
                    const clampedY = clamp(y, 10, 90);
                    state.nodePositions[state.draggingNodeId] = { x: clampedX, y: clampedY };
                    updateNodeStyle(state.draggingNodeId, clampedX, clampedY);
                }
            } else if (state.panState) {
                state.panX = state.panState.panX + (event.clientX - state.panState.startX);
                state.panY = state.panState.panY + (event.clientY - state.panState.startY);
                applyViewport();
            }
        }
        requestAnimationFrame(tick);
    }

    requestAnimationFrame(tick);
}
