import { KeelElement } from '../base/KeelElement.js';
import { tableHtml } from '../../render.js';

export class KeelDataTable extends KeelElement {
    template() {
        return `
            <style>
                :host {
                    min-width: 0;
                }
                .table-wrap {
                    width: 100%;
                    overflow-x: auto;
                    -webkit-overflow-scrolling: touch;
                }
                .table-wrap::-webkit-scrollbar {
                    height: 4px;
                }
                .table-wrap::-webkit-scrollbar-thumb {
                    background: rgba(15, 23, 42, 0.15);
                    border-radius: 999px;
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    text-align: left;
                }
                th,
                td {
                    padding: 14px 16px;
                    border-bottom: 1px solid rgba(17, 24, 39, 0.06);
                    vertical-align: top;
                    color: var(--ink);
                }
                th {
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.12em;
                    text-transform: uppercase;
                }
                tbody tr:last-child td {
                    border-bottom: 0;
                }
            </style>
            <div class="table-wrap" data-ref="wrap"></div>
        `;
    }

    afterMount() {
        this.shadowRoot.addEventListener('click', (event) => {
            const button = event.target.closest('[data-plugin-id][data-plugin-action]');
            if (!button) return;
            this.emit('keel:plugin-toggle', {
                pluginId: button.dataset.pluginId,
                action: button.dataset.pluginAction
            });
        });
    }

    render({ headers = [], rows = [], emptyHtml = '', wrapClass = '' } = {}) {
        this.ensureInitialized();
        this.refs.wrap.className = ['table-wrap', wrapClass].filter(Boolean).join(' ');
        this.refs.wrap.innerHTML = rows.length ? tableHtml(headers, rows) : emptyHtml;
    }
}

if (!customElements.get('keel-data-table')) {
    customElements.define('keel-data-table', KeelDataTable);
}
