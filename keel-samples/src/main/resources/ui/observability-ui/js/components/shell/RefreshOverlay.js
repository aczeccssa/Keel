import { KeelElement } from '../base/KeelElement.js';
import { REFRESH_INTERVALS } from '../../config.js';

export class RefreshOverlay extends KeelElement {
    template() {
        return `
            <style>
                .refresh-interval-cover {
                    display: none;
                    position: fixed;
                    inset: 0;
                    z-index: 1000;
                    background: rgba(17, 24, 39, 0.25);
                    backdrop-filter: blur(4px);
                    -webkit-backdrop-filter: blur(4px);
                    align-items: center;
                    justify-content: center;
                }
                .refresh-interval-cover.is-open {
                    display: flex;
                }
                .refresh-interval-panel {
                    background: var(--panel-strong);
                    border: 1px solid rgba(17, 24, 39, 0.06);
                    border-radius: var(--radius-lg);
                    padding: 20px 24px;
                    min-width: 200px;
                    animation: panel-enter 200ms var(--ease-spring);
                }
                .refresh-interval-label {
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    color: var(--muted);
                    margin-bottom: 12px;
                }
                .refresh-interval-options {
                    display: flex;
                    flex-direction: column;
                    gap: 4px;
                }
                .refresh-interval-option {
                    display: flex;
                    align-items: center;
                    gap: 10px;
                    padding: 10px 12px;
                    border-radius: var(--radius-md);
                    cursor: pointer;
                    font-size: 13px;
                    font-weight: 600;
                    transition: background 150ms ease;
                    color: var(--ink);
                }
                .refresh-interval-option:hover {
                    background: rgba(15, 23, 42, 0.05);
                }
                .refresh-interval-option.is-active {
                    color: var(--teal);
                    font-weight: 700;
                }
                .refresh-interval-option.is-active::after {
                    content: "";
                    margin-left: auto;
                    width: 6px;
                    height: 6px;
                    border-radius: 999px;
                    background: var(--teal);
                }
                @keyframes panel-enter {
                    from {
                        opacity: 0;
                        transform: translateY(6px) scale(0.98);
                    }
                    to {
                        opacity: 1;
                        transform: translateY(0) scale(1);
                    }
                }
            </style>
            <div class="refresh-interval-cover" data-ref="cover">
                <div class="refresh-interval-panel">
                    <div class="refresh-interval-label">Refresh interval</div>
                    <div class="refresh-interval-options" data-ref="options"></div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.cover.addEventListener('click', (event) => {
            const option = event.target.closest('[data-ms]');
            if (option) {
                this.emit('keel:refresh-interval-change', { ms: Number(option.dataset.ms) });
                return;
            }
            if (!event.target.closest('.refresh-interval-panel')) {
                this.emit('keel:close-refresh-overlay');
            }
        });
    }

    render({ open = false, refreshIntervalMs = 5000 } = {}) {
        this.ensureInitialized();
        this.refs.cover.classList.toggle('is-open', open);
        this.refs.options.innerHTML = REFRESH_INTERVALS.map((opt) => `
            <div class="refresh-interval-option ${refreshIntervalMs === opt.ms ? 'is-active' : ''}" data-ms="${opt.ms}">
                ${opt.label}
            </div>
        `).join('');
    }
}

if (!customElements.get('keel-refresh-overlay')) {
    customElements.define('keel-refresh-overlay', RefreshOverlay);
}
