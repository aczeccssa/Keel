import { KeelElement } from '../base/KeelElement.js';
import { tableHtml } from '../../render.js';

export class KeelDataTable extends KeelElement {
    template() {
        return `<div data-ref="wrap"></div>`;
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
        this.refs.wrap.className = wrapClass;
        this.refs.wrap.innerHTML = rows.length ? tableHtml(headers, rows) : emptyHtml;
    }
}

if (!customElements.get('keel-data-table')) {
    customElements.define('keel-data-table', KeelDataTable);
}
