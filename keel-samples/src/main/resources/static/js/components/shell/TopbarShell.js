import { KeelElement } from '../base/KeelElement.js';
import { REFRESH_INTERVALS } from '../../config.js';
import { apiUrl } from '../../api.js';

export class TopbarShell extends KeelElement {
    template() {
        return `
            <style>
                .topbar {
                    flex-shrink: 0;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    padding: 22px 28px 16px;
                    background: rgba(246, 243, 236, 0.82);
                    backdrop-filter: blur(18px);
                    border-bottom: 1px solid rgba(17, 24, 39, 0.04);
                    z-index: 10;
                }
                .topbar-showcase {
                    font-family: var(--font-headline);
                    font-size: 20px;
                    font-weight: 700;
                    font-style: italic;
                    letter-spacing: -0.02em;
                    color: var(--navy);
                    transition: text-decoration 200ms ease;
                }
                .topbar-showcase:hover {
                    text-decoration: underline;
                    text-underline-offset: 3px;
                }
                .topbar-actions {
                    display: flex;
                    align-items: center;
                    gap: 10px;
                }
                .status-pill,
                .ghost-pill {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    padding: 10px 14px;
                    border-radius: 999px;
                    background: var(--panel-strong);
                    border: 1px solid rgba(17, 24, 39, 0.06);
                    font-size: 11px;
                    font-weight: 800;
                    letter-spacing: 0.12em;
                    text-transform: uppercase;
                }
                .status-pill {
                    cursor: pointer;
                }
                .status-dot {
                    width: 8px;
                    height: 8px;
                    border-radius: 999px;
                    background: var(--green);
                }
                .status-pill.is-offline .status-dot {
                    background: var(--red);
                }
                .ghost-pill {
                    border: 0;
                    cursor: pointer;
                    transition: background 200ms ease;
                }
                .ghost-pill:hover {
                    background: #ffffff;
                }
                @media (max-width: 720px) {
                    .topbar {
                        padding: 18px 20px 14px;
                        flex-direction: column;
                        align-items: stretch;
                    }
                    .topbar-actions {
                        justify-content: space-between;
                    }
                }
            </style>
            <header class="topbar">
                <a class="topbar-showcase" data-ref="showcaseLinke" href="#" target="_blank" rel="noopener">Showcase</a>
                <div class="topbar-actions">
                    <div class="status-pill" data-ref="connectionPill">
                        <span class="status-dot"></span>
                        <span data-ref="connectionText">Booting</span>
                    </div>
                    <button class="ghost-pill" data-ref="streamToggleBtn" type="button">Live On</button>
                </div>
            </header>
        `;
    }

    afterMount() {
        this.refs.connectionPill.addEventListener('click', () => {
            this.emit('keel:open-refresh-overlay');
        });

        this.refs.streamToggleBtn.addEventListener('click', () => {
            this.emit('keel:stream-toggle');
        });
    }



    render(appState) {
        this.ensureInitialized();
        this.refs.connectionText.textContent = appState.connectionState === 'Live'
            ? `Live ${REFRESH_INTERVALS.find((item) => item.ms === appState.refreshIntervalMs)?.label || '5s'}`
            : appState.connectionState;
        this.refs.connectionPill.classList.toggle('is-offline', appState.connectionState !== 'Live');
        this.refs.streamToggleBtn.textContent = appState.streamEnabled ? 'Live On' : 'Live Off';
        this.refs.showcaseLinke.href = apiUrl('/plugins/authsample/showcase');
    }
}

if (!customElements.get('keel-topbar')) {
    customElements.define('keel-topbar', TopbarShell);
}
