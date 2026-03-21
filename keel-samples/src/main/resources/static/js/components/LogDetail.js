import { KeelElement } from './base/KeelElement.js';
import { renderLogDetail } from '../render.js';

export class LogDetail extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `<div class="detail-list scroll-panel" data-ref="body"></div>`;
    }

    render({ selected = null } = {}) {
        this.ensureInitialized();
        this.refs.body.innerHTML = renderLogDetail(selected);
    }
}

if (!customElements.get('keel-log-detail')) {
    customElements.define('keel-log-detail', LogDetail);
}
