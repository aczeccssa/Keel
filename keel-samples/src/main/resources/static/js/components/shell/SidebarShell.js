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
            <style>
                .sidebar {
                    height: 100vh;
                    padding: 28px 18px;
                    background: rgba(247, 244, 237, 0.86);
                    backdrop-filter: blur(18px);
                    border-right: 1px solid var(--line);
                    display: flex;
                    flex-direction: column;
                    gap: 24px;
                }
                .brand {
                    padding: 6px 10px;
                }
                .brand h1 {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 28px;
                    font-style: italic;
                    font-weight: 700;
                    letter-spacing: -0.03em;
                }
                .brand p {
                    margin: 8px 0 0;
                    color: var(--muted);
                    font-size: 11px;
                    font-weight: 700;
                    letter-spacing: 0.16em;
                    text-transform: uppercase;
                }
                .nav-list,
                .meta-list {
                    display: grid;
                    gap: 8px;
                }
                .nav-link,
                .meta-link {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 10px;
                    padding: 14px 16px;
                    border-radius: 999px;
                    color: #475467;
                    transition: all 250ms var(--ease-smooth);
                }
                .nav-link:hover,
                .meta-link:hover {
                    transform: translateX(4px);
                    background: rgba(255, 255, 255, 0.7);
                }
                .nav-link.is-active {
                    background: var(--panel-strong);
                    color: var(--navy);
                    box-shadow: 0 10px 28px rgba(15, 23, 42, 0.08);
                }
                .nav-link-label {
                    display: flex;
                    align-items: center;
                    gap: 10px;
                    min-width: 0;
                    flex: 1;
                }
                .nav-icon {
                    width: 30px;
                    height: 30px;
                    border-radius: 10px;
                    background: rgba(15, 23, 42, 0.06);
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 14px;
                    font-weight: 800;
                    flex-shrink: 0;
                }
                .nav-icon svg {
                    width: 16px;
                    height: 16px;
                }
                .nav-link.is-active .nav-icon {
                    background: rgba(15, 118, 110, 0.12);
                    color: var(--teal);
                }
                .nav-copy,
                .meta-copy {
                    min-width: 0;
                    flex: 1;
                }
                .nav-copy strong,
                .meta-copy strong {
                    display: block;
                    font-size: 12px;
                    font-weight: 800;
                    letter-spacing: 0.08em;
                    text-transform: uppercase;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .nav-copy span,
                .meta-copy span {
                    display: block;
                    margin-top: 4px;
                    color: var(--muted);
                    font-size: 11px;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .sidebar-footer {
                    margin-top: auto;
                    display: grid;
                    gap: 12px;
                }
                .meta-link {
                    padding: 10px 12px;
                }
                .sidebar-cta {
                    width: 100%;
                    border: 0;
                    padding: 14px 18px;
                    border-radius: 999px;
                    background: var(--navy);
                    color: #f8fafc;
                    cursor: pointer;
                    font-size: 11px;
                    font-weight: 800;
                    letter-spacing: 0.18em;
                    text-transform: uppercase;
                    transition: background 200ms ease;
                }
                .sidebar-cta:hover {
                    background: var(--navy-2);
                }
                @media (max-width: 980px) {
                    .sidebar {
                        height: auto;
                        padding: 18px;
                        border-right: 0;
                        border-bottom: 1px solid var(--line);
                    }
                }
            </style>
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
                <span class="meta-copy"><strong>${appState.logs.summary?.totalMatched || appState.logs.page?.total || 0} Logs</strong><span>Structured explorer</span></span>
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
