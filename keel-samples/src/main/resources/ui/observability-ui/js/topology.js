import { state, selectedNode } from './state.js';
import { VIEWBOX } from './config.js';
import { clamp, escapeHtml } from './utils.js';
import { nodeLabel, healthTone, healthColor } from './formatters.js';

export function inferLayout(nodes) {
    const positions = new Map();
    if (!nodes.length) return positions;
    const kernel = nodes.find((node) => node.id === 'kernel' || node.kind === 'kernel') || nodes[0];
    positions.set(kernel.id, { x: 50, y: 52, size: 'kernel' });
    const others = nodes.filter((node) => node.id !== kernel.id);
    const namedSlots = [
        { token: 'auth', x: 18, y: 24 },
        { token: 'order', x: 18, y: 80 },
        { token: 'product', x: 82, y: 22 },
        { token: 'inventory', x: 82, y: 80 },
        { token: 'observe', x: 80, y: 52 },
        { token: 'db', x: 20, y: 52 }
    ];
    const remaining = [];
    others.forEach((node) => {
        const key = `${node.id} ${node.pluginId || ''} ${node.label || ''}`.toLowerCase();
        const slot = namedSlots.find((candidate) => key.includes(candidate.token));
        if (slot) {
            positions.set(node.id, { x: slot.x, y: slot.y, size: 'leaf' });
        } else {
            remaining.push(node);
        }
    });

    remaining.forEach((node, index) => {
        const angle = ((index / Math.max(remaining.length, 1)) * Math.PI * 2) - (Math.PI / 2);
        positions.set(node.id, {
            x: 50 + Math.cos(angle) * 38,
            y: 52 + Math.sin(angle) * 36,
            size: 'leaf'
        });
    });
    nodes.forEach((node) => {
        const saved = state.nodePositions[node.id];
        if (saved) positions.set(node.id, { ...saved, size: node.id === kernel.id ? 'kernel' : 'leaf' });
    });
    return positions;
}

export function curvePath(from, to) {
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const distance = Math.max(Math.hypot(dx, dy), 1);
    const ux = dx / distance;
    const uy = dy / distance;
    const startOffset = from.size === 'kernel' ? 90 : 50;
    const endOffset = to.size === 'kernel' ? 90 : 50;
    const sx = from.x + ux * startOffset;
    const sy = from.y + uy * startOffset;
    const ex = to.x - ux * endOffset;
    const ey = to.y - uy * endOffset;
    const bend = clamp(Math.abs(ex - sx) * 0.18, 80, 180);
    const dir = ex >= sx ? 1 : -1;
    const c1x = sx + bend * dir;
    const c2x = ex - bend * dir;
    return `M ${sx} ${sy} C ${c1x} ${sy}, ${c2x} ${ey}, ${ex} ${ey}`;
}

export function inferEdges(nodes) {
    const edges = new Map();
    const kernel = nodes.find((node) => node.id === 'kernel') || nodes[0];
    if (kernel) {
        nodes.filter((node) => node.id !== kernel.id).forEach((node) => {
            edges.set(`${kernel.id}->${node.id}`, {
                edgeFrom: kernel.id,
                edgeTo: node.id,
                status: node.healthState || 'HEALTHY'
            });
        });
    }
    state.flows.forEach((flow) => {
        if (flow.edgeFrom && flow.edgeTo) {
            edges.set(`${flow.edgeFrom}->${flow.edgeTo}`, flow);
        }
    });
    return [...edges.values()];
}

export function renderNode(node, position) {
    if (!position) return '';
    const selected = state.selectedNodeId === node.id;
    const kernel = node.id === 'kernel' || node.kind === 'kernel';
    return `
        <div class="node-wrap" data-node-id="${escapeHtml(node.id)}" style="left:${position.x}%; top:${position.y}%;">
            <div class="node-card ${kernel ? 'kernel' : ''} ${selected ? 'is-selected' : ''}">
                <div class="node-topline">
                    <span class="node-chip">${escapeHtml(kernel ? 'kernel' : (node.runtimeMode || 'plugin'))}</span>
                    <span class="badge ${healthTone(node.healthState || node.lifecycleState)}"> </span>
                </div>
                <div class="node-title">${escapeHtml(nodeLabel(node))}</div>
            </div>
        </div>
    `;
}

export function renderActiveFlows(positions, refs) {
    const now = Date.now();
    state.activeFlows = state.activeFlows.filter((flow) => now - flow.createdAt < flow.ttlMs);
    refs.flowSvg.innerHTML = state.activeFlows.map((flow) => {
        const from = positions.get(flow.edgeFrom);
        const to = positions.get(flow.edgeTo);
        if (!from || !to) return '';
        const start = { x: (from.x / 100) * VIEWBOX, y: (from.y / 100) * VIEWBOX, size: from.size };
        const end = { x: (to.x / 100) * VIEWBOX, y: (to.y / 100) * VIEWBOX, size: to.size };
        const pathId = `flow-${flow.id}`;
        const stroke = healthColor(flow.status);
        const opacity = 1 - ((now - flow.createdAt) / flow.ttlMs) * 0.8;
        return `
            <g opacity="${opacity}">
                <path id="${pathId}" class="flow-path" stroke="${stroke}" d="${curvePath(start, end)}"></path>
                <circle r="5" fill="${stroke}">
                    <animateMotion dur="${flow.durationMs}ms" repeatCount="1" fill="freeze">
                        <mpath href="#${pathId}"></mpath>
                    </animateMotion>
                </circle>
            </g>
        `;
    }).join('');
    if (state.activeFlows.length) {
        requestAnimationFrame(() => renderActiveFlows(positions, refs));
    }
}

export function applyViewport(refs) {
    refs.viewport.style.transform = `translate(${state.panX}px, ${state.panY}px) scale(${state.zoom})`;
    refs.zoomLabel.textContent = `${Math.round(state.zoom * 100)}%`;
}

export function renderTopologyGraph(refs) {
    const nodes = [...state.topology];
    if (!nodes.length) {
        refs.baseSvg.innerHTML = '';
        refs.flowSvg.innerHTML = '';
        refs.nodeLayer.innerHTML = '<div class="empty" style="position:absolute; inset:24px; display:grid; place-items:center;">No topology data available.</div>';
        applyViewport(refs);
        return;
    }
    const positions = inferLayout(nodes);
    const edges = inferEdges(nodes);
    const activeNode = selectedNode();
    const selectedId = activeNode ? activeNode.id : null;
    refs.baseSvg.innerHTML = edges.map((edge) => {
        const from = positions.get(edge.edgeFrom);
        const to = positions.get(edge.edgeTo);
        if (!from || !to) return '';
        const start = {
            x: (from.x / 100) * VIEWBOX,
            y: (from.y / 100) * VIEWBOX,
            size: from.size
        };
        const end = {
            x: (to.x / 100) * VIEWBOX,
            y: (to.y / 100) * VIEWBOX,
            size: to.size
        };
        return `<path class="base-edge ${selectedId && (edge.edgeFrom === selectedId || edge.edgeTo === selectedId) ? 'is-selected' : ''}" d="${curvePath(start, end)}"></path>`;
    }).join('');
    refs.nodeLayer.innerHTML = nodes.map((node) => renderNode(node, positions.get(node.id))).join('');
    renderActiveFlows(positions, refs);
    applyViewport(refs);
}
