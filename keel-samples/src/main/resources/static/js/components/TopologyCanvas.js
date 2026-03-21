import { KeelElement } from './base/KeelElement.js';
import { state } from '../state.js';
import { clamp } from '../utils.js';
import { applyViewport, renderTopologyGraph } from '../topology.js';

const DRAG_THRESHOLD_PX = 5;

export class TopologyCanvas extends KeelElement {
    template() {
        return `
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
