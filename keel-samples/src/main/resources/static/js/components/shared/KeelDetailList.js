import { KeelElement } from '../base/KeelElement.js';

export class KeelDetailList extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `<div data-ref="list"></div>`;
    }

    render({ html = '', className = 'detail-list scroll-panel' } = {}) {
        this.ensureInitialized();
        this.refs.list.className = className;
        this.refs.list.innerHTML = html;
    }
}

if (!customElements.get('keel-detail-list')) {
    customElements.define('keel-detail-list', KeelDetailList);
}
