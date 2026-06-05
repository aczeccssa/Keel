import { KeelElement } from '../base/KeelElement.js';
import { statCards } from '../../render.js';

export class KeelStatGrid extends KeelElement {
    template() {
        return `
            <style>
                .stats-row {
                    display: grid;
                    gap: 16px;
                    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                }
                .stat-card {
                    background: var(--panel-strong);
                    padding: 22px 24px;
                    border-radius: var(--radius-lg);
                    border: 1px solid var(--line);
                    box-shadow: var(--shadow-sm);
                }
                .stat-card .label {
                    display: block;
                    margin-bottom: 10px;
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                }
                .stat-card .value {
                    margin-top: 8px;
                    font-family: var(--font-headline);
                    font-size: 28px;
                    line-height: 0.95;
                    letter-spacing: -0.04em;
                    color: var(--ink);
                }
                .stat-card .hint {
                    margin-top: 6px;
                    color: var(--muted);
                    font-size: 11px;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
            </style>
            <div data-ref="grid"></div>
        `;
    }

    render({ entries = [], className = 'stats-row' } = {}) {
        this.ensureInitialized();
        this.refs.grid.className = className;
        this.refs.grid.innerHTML = statCards(entries);
    }
}

if (!customElements.get('keel-stat-grid')) {
    customElements.define('keel-stat-grid', KeelStatGrid);
}
