import { KeelElement } from './base/KeelElement.js';
import { renderWaterfall } from '../render.js';

export class TraceWaterfall extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <style>
                .waterfall-shell {
                    display: flex;
                    flex-direction: column;
                    overflow-y: auto;
                    max-height: 280px;
                    flex-shrink: 0;
                }
                .waterfall-row {
                    display: grid;
                    grid-template-columns: 190px minmax(0, 1fr) 80px;
                    align-items: center;
                }
                .waterfall-row > div:first-child {
                    min-width: 0;
                }
                .waterfall-row strong {
                    display: block;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    color: var(--ink);
                }
                .waterfall-bar {
                    position: relative;
                    height: 16px;
                    border-radius: 999px;
                    background: rgba(148, 163, 184, 0.14);
                    overflow: hidden;
                    cursor: pointer;
                    margin: 0 12px;
                }
                .waterfall-bar span {
                    position: absolute;
                    top: 0;
                    height: 100%;
                    border-radius: 999px;
                    transition: width 200ms ease, left 200ms ease;
                }
                .mono {
                    font-family: var(--font-mono);
                }
                .muted {
                    color: var(--muted);
                    font-size: 12px;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    margin-top: 4px;
                }
            </style>
            <div class="waterfall-shell" data-ref="shell"></div>
        `;
    }

    afterMount() {
        this.shadowRoot.addEventListener('click', (event) => {
            const bar = event.target.closest('[data-span-id]');
            if (!bar) return;
            this.emit('keel:span-select', { spanId: bar.dataset.spanId });
        });
    }

    render({ group = null } = {}) {
        this.ensureInitialized();
        this.refs.shell.innerHTML = group ? renderWaterfall(group) : '';
    }
}

if (!customElements.get('keel-trace-waterfall')) {
    customElements.define('keel-trace-waterfall', TraceWaterfall);
}
