import { KeelElement } from '../base/KeelElement.js';
import { statCards } from '../../render.js';

export class KeelStatGrid extends KeelElement {
    template() {
        return `<div data-ref="grid"></div>`;
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
