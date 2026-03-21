import { KeelElement } from '../base/KeelElement.js';
import { TABS } from '../../config.js';
import { escapeHtml, formatPercent } from '../../utils.js';

const ICONS = {
    topology: '<circle cx="12" cy="12" r="3"/><path d="M12 2v4"/><path d="M12 18v4"/><path d="m4.93 4.93 2.83 2.83"/><path d="m16.24 16.24 2.83 2.83"/><path d="M2 12h4"/><path d="M18 12h4"/><path d="m4.93 19.07 2.83-2.83"/><path d="m16.24 7.76 2.83-2.83"/>',
    traces: '<path d="M3 5h6"/><path d="M3 12h10"/><path d="M3 19h14"/><path d="m13 17 4 4 4-4"/><path d="M17 21V9"/>',
    logs: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M8 13h8"/><path d="M8 17h8"/><path d="M10 9H8"/>',
    nodes: '<path d="M16 18a2 2 0 0 0-2 2"/><path d="M10 18a2 2 0 0 1 2 2"/><path d="M12 14v6"/><path d="M6 10a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z"/><path d="M18 10a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z"/><path d="M12 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z"/><path d="M12 8v3"/><path d="M7.5 9.5 10 11"/><path d="M16.5 9.5 14 11"/>',
    metrics: '<path d="M3 3v18h18"/><path d="m7 14 3-3 3 2 4-6"/>',
    openapi: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6"/><path d="M10 13h4"/><path d="M8 17h8"/>'
};

function svgIcon(name, className = 'nav-icon-svg') {
    return `
        <svg class="${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            ${ICONS[name] || ''}
        </svg>
    `;
}

export class SidebarShell extends KeelElement {
    template() {
        return `
            <aside class="sidebar">
                <div class="brand">
                    <h1>Keel Precision</h1>
                    <p>Observability Core</p>
                </div>

                <nav class="nav-list" data-ref="nav"></nav>

                <div class="sidebar-footer">
                    <div class="meta-list" data-ref="meta"></div>
                    <button class="sidebar-cta" data-ref="recenterBtn" type="button">Center Kernel</button>
                </div>
            </aside>
        `;
    }

    afterMount() {
        this.refs.nav.addEventListener('click', (event) => {
            const link = event.target.closest('[data-tab-id]');
            if (!link) return;
            this.emit('keel:navigate', { tabId: link.dataset.tabId });
        });

        this.refs.meta.addEventListener('click', (event) => {
            const link = event.target.closest('[data-tab-id]');
            if (!link) return;
            this.emit('keel:navigate', { tabId: link.dataset.tabId });
        });

        this.refs.recenterBtn.addEventListener('click', () => {
            this.emit('keel:node-select', {
                nodeId: 'kernel',
                resetViewport: true,
                navigate: 'topology'
            });
        });
    }

    render(appState) {
        this.ensureInitialized();
        const nodes = appState.nodeSummaries.length || appState.topology.length;
        this.refs.nav.innerHTML = TABS.map((tab) => `
            <a class="nav-link ${tab.id === appState.activeTab ? 'is-active' : ''}" href="#${tab.id}" data-tab-id="${tab.id}">
                <span class="nav-link-label">
                    <span class="nav-icon">${svgIcon(tab.id)}</span>
                    <span class="nav-copy"><strong>${escapeHtml(tab.label)}</strong><span>${escapeHtml(tab.note)}</span></span>
                </span>
            </a>
        `).join('');

        this.refs.meta.innerHTML = `
            <a class="meta-link" href="#nodes" data-tab-id="nodes">
                <span class="meta-copy"><strong>${nodes} Nodes</strong><span>Shared node selection</span></span>
            </a>
            <a class="meta-link" href="#logs" data-tab-id="logs">
                <span class="meta-copy"><strong>${appState.logs.total || 0} Logs</strong><span>Structured explorer</span></span>
            </a>
            <a class="meta-link" href="#metrics" data-tab-id="metrics">
                <span class="meta-copy"><strong>${appState.metrics ? formatPercent(appState.metrics.kernel.processCpuLoad) : 'n/a'}</strong><span>Kernel CPU</span></span>
            </a>
        `;
    }
}

if (!customElements.get('keel-sidebar')) {
    customElements.define('keel-sidebar', SidebarShell);
}
