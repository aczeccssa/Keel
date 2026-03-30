import { KeelElement } from './base/KeelElement.js';
import { state } from '../state.js';
import { clamp } from '../utils.js';
import { applyViewport, renderTopologyGraph } from '../topology.js';

const DRAG_THRESHOLD_PX = 5;

export class TopologyCanvas extends KeelElement {
    template() {
        return `
            <style>
                .canvas-card {
                    height: 100%;
                    min-height: 0;
                    border-radius: var(--radius-xl);
                    overflow: hidden;
                }
                .topology-stage {
                    position: relative;
                    height: 100%;
                    background: radial-gradient(circle at top left, rgba(15, 118, 110, 0.08), transparent 30%),
                        linear-gradient(180deg, rgba(255, 255, 255, 0.7), rgba(255, 255, 255, 0.82));
                }
                .topo-canvas-drag {
                    touch-action: none;
                    cursor: grab;
                }
                .topo-canvas-drag:active {
                    cursor: grabbing;
                }
                .topology-grid {
                    position: absolute;
                    inset: 0;
                    background-image: linear-gradient(rgba(15, 23, 42, 0.035) 1px, transparent 1px),
                        linear-gradient(90deg, rgba(15, 23, 42, 0.035) 1px, transparent 1px);
                    background-size: 42px 42px;
                    pointer-events: none;
                }
                .topology-viewport {
                    position: absolute;
                    inset: 0;
                    transform-origin: center center;
                    will-change: transform;
                }
                .topology-svg {
                    position: absolute;
                    inset: 0;
                    width: 100%;
                    height: 100%;
                    pointer-events: none;
                }
                .topology-node-layer {
                    position: absolute;
                    inset: 0;
                }
                .base-edge {
                    fill: none;
                    stroke: rgba(148, 163, 184, 0.5);
                    stroke-width: 1.5;
                    stroke-dasharray: 8 7;
                    transition: stroke 300ms ease, stroke-width 300ms ease;
                }
                .base-edge.is-selected {
                    stroke: rgba(15, 118, 110, 0.62);
                    stroke-width: 2.2;
                }
                .flow-path {
                    fill: none;
                    stroke-width: 2.5;
                    stroke-dasharray: 9 7;
                    animation: edge-flow 0.82s linear infinite;
                }
                @keyframes edge-flow {
                    from {
                        stroke-dashoffset: 34;
                    }
                    to {
                        stroke-dashoffset: 0;
                    }
                }
                .node-wrap {
                    position: absolute;
                    transform: translate(-50%, -50%);
                    z-index: 1;
                }
                .node-card {
                    min-width: 120px;
                    max-width: 180px;
                    padding: 12px 14px;
                    border-radius: 20px;
                    background: rgba(255, 255, 255, 0.85);
                    backdrop-filter: blur(8px);
                    border: 1px solid rgba(17, 24, 39, 0.04);
                    cursor: pointer;
                    transition: transform 300ms var(--ease-spring), box-shadow 300ms var(--ease-smooth), border-color 300ms var(--ease-smooth);
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    text-align: center;
                }
                .node-card:hover {
                    transform: translateY(-4px) scale(1.05);
                    z-index: 2;
                }
                .node-card.is-selected {
                    border-color: rgba(15, 118, 110, 0.4);
                }
                .node-card.kernel {
                    min-width: 150px;
                    padding: 18px;
                    border-radius: 28px;
                    background: radial-gradient(circle at top, rgba(30, 41, 59, 0.9), rgba(15, 23, 42, 0.96));
                    color: #f8fafc;
                    border-color: rgba(255, 255, 255, 0.08);
                }
                .node-topline {
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    gap: 8px;
                    margin-bottom: 8px;
                    width: 100%;
                }
                .node-chip {
                    padding: 5px 9px;
                    border-radius: 999px;
                    background: rgba(15, 23, 42, 0.05);
                    font-size: 9px;
                    font-weight: 800;
                    letter-spacing: 0.12em;
                    text-transform: uppercase;
                }
                .kernel .node-chip {
                    background: rgba(255, 255, 255, 0.12);
                    color: #dbeafe;
                }
                .node-title {
                    font-family: var(--font-headline);
                    font-size: 18px;
                    line-height: 1.1;
                    letter-spacing: -0.02em;
                    word-break: break-word;
                    color: inherit;
                }
                .canvas-controls {
                    position: absolute;
                    left: 24px;
                    bottom: 24px;
                    display: flex;
                    flex-wrap: wrap;
                    align-items: center;
                    gap: 10px;
                    z-index: 3;
                }
                .canvas-btn {
                    border: 1px solid rgba(15, 23, 42, 0.04);
                    width: 42px;
                    height: 42px;
                    border-radius: 14px;
                    background: rgba(255, 255, 255, 0.84);
                    cursor: pointer;
                    transition: all 200ms ease;
                    color: var(--navy);
                    font-weight: 600;
                }
                .canvas-btn:hover {
                    background: #ffffff;
                    transform: translateY(-2px);
                }
                .canvas-label {
                    padding: 10px 12px;
                    border-radius: 14px;
                    background: rgba(255, 255, 255, 0.84);
                    font-size: 11px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    border: 1px solid rgba(15, 23, 42, 0.04);
                    font-family: var(--font-mono);
                }
            </style>
            <div class="canvas-card topology-stage topo-canvas-drag" data-ref="stage">
                <div class="topology-grid"></div>
                <div class="topology-viewport" data-ref="viewport">
                    <svg class="topology-svg" data-ref="baseSvg" viewBox="0 0 1000 1000" preserveAspectRatio="none"></svg>
                    <svg class="topology-svg" data-ref="flowSvg" viewBox="0 0 1000 1000" preserveAspectRatio="none"></svg>
                    <div class="topology-node-layer" data-ref="nodeLayer"></div>
                </div>
                <div class="canvas-controls">
                    <button class="canvas-btn" data-action="zoom-in" type="button">+</button>
                    <button class="canvas-btn" data-action="zoom-out" type="button">-</button>
                    <button class="canvas-btn" data-action="layout" type="button">L</button>
                    <button class="canvas-btn" data-action="recenter" type="button">C</button>
                    <div class="canvas-label mono" data-ref="zoomLabel">100%</div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.shadowRoot.addEventListener('click', (event) => {
            const action = event.target.closest('[data-action]')?.dataset.action;
            if (action === 'zoom-in') this.emit('keel:topology-zoom-in');
            if (action === 'zoom-out') this.emit('keel:topology-zoom-out');
            if (action === 'layout') this.emit('keel:topology-layout-reset');
            if (action === 'recenter') this.emit('keel:topology-recenter');

            const nodeEl = event.target.closest('[data-node-id]');
            if (nodeEl) return;
            if (event.target.closest('.canvas-controls')) return;
            if (event.target.closest('.topology-stage')) {
                this.emit('keel:node-clear');
            }
        });

        this.refs.stage.addEventListener('pointerdown', (event) => {
            const nodeWrap = event.target.closest('.node-wrap');
            if (nodeWrap) {
                const nodeId = nodeWrap.dataset.nodeId;
                this.emit('keel:node-select', { nodeId });
                state.dragState = {
                    nodeId,
                    startX: event.clientX,
                    startY: event.clientY,
                    dragging: false
                };
                this.refs.stage.setPointerCapture(event.pointerId);
                return;
            }

            if (event.target.closest('.topology-viewport') || event.target === this.refs.stage) {
                state.panState = {
                    startX: event.clientX,
                    startY: event.clientY,
                    panX: state.panX,
                    panY: state.panY
                };
            }
        });

        window.addEventListener('pointermove', (event) => {
            if (state.draggingNodeId || state.dragState || state.panState) {
                if (!state._dragRect) {
                    state._dragRect = this.refs.stage.getBoundingClientRect();
                }
                state._pendingMove = event;
            }
        });

        window.addEventListener('pointercancel', () => {
            this.resetPointerState();
        });

        window.addEventListener('pointerup', () => {
            this.resetPointerState();
            if (state.activeTab === 'topology') {
                renderTopologyGraph(this.refs);
            }
        });

        const tick = () => {
            if (state._pendingMove) {
                const event = state._pendingMove;
                const rect = state._dragRect || this.refs.stage.getBoundingClientRect();
                state._dragRect = null;
                state._pendingMove = null;

                if (state.draggingNodeId) {
                    const x = ((event.clientX - rect.left) / rect.width) * 100;
                    const y = ((event.clientY - rect.top) / rect.height) * 100;
                    const clampedX = clamp(x, 8, 92);
                    const clampedY = clamp(y, 10, 90);
                    state.nodePositions[state.draggingNodeId] = { x: clampedX, y: clampedY };
                    this.updateNodeStyle(state.draggingNodeId, clampedX, clampedY);
                } else if (state.dragState) {
                    if (!state.dragState.dragging) {
                        const dx = event.clientX - state.dragState.startX;
                        const dy = event.clientY - state.dragState.startY;
                        if (Math.sqrt(dx * dx + dy * dy) >= DRAG_THRESHOLD_PX) {
                            state.dragState.dragging = true;
                            state.draggingNodeId = state.dragState.nodeId;
                        }
                    } else {
                        const x = ((event.clientX - rect.left) / rect.width) * 100;
                        const y = ((event.clientY - rect.top) / rect.height) * 100;
                        const clampedX = clamp(x, 8, 92);
                        const clampedY = clamp(y, 10, 90);
                        state.nodePositions[state.draggingNodeId] = { x: clampedX, y: clampedY };
                        this.updateNodeStyle(state.draggingNodeId, clampedX, clampedY);
                    }
                } else if (state.panState) {
                    state.panX = state.panState.panX + (event.clientX - state.panState.startX);
                    state.panY = state.panState.panY + (event.clientY - state.panState.startY);
                    applyViewport(this.refs);
                }
            }
            window.requestAnimationFrame(tick);
        };

        window.requestAnimationFrame(tick);
    }

    resetPointerState() {
        state.dragState = null;
        state.draggingNodeId = null;
        state.panState = null;
        state._pendingMove = null;
        state._dragRect = null;
    }

    updateNodeStyle(nodeId, x, y) {
        const wrap = this.refs.nodeLayer.querySelector(`.node-wrap[data-node-id="${CSS.escape(nodeId)}"]`);
        if (!wrap) return;
        wrap.style.left = `${x}%`;
        wrap.style.top = `${y}%`;
    }

    render() {
        this.ensureInitialized();
        renderTopologyGraph(this.refs);
    }
}

if (!customElements.get('keel-topology-canvas')) {
    customElements.define('keel-topology-canvas', TopologyCanvas);
}
