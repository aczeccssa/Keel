import { KeelElement } from './base/KeelElement.js';
import { renderWaterfall } from '../render.js';

export class TraceWaterfall extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `<div class="waterfall-shell" data-ref="shell"></div>`;
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
