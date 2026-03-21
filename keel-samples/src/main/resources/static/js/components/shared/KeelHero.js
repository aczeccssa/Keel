import { KeelElement } from '../base/KeelElement.js';

export class KeelHero extends KeelElement {
    hostStyles() {
        return 'flex: 0 0 auto;';
    }

    template() {
        return `
            <div class="hero">
                <div>
                    <div class="section-label" data-ref="label"></div>
                    <h2 data-ref="title"></h2>
                </div>
                <div class="hero-meta" data-ref="meta"></div>
            </div>
        `;
    }

    render({ label = '', title = '', metaHtml = '' } = {}) {
        this.ensureInitialized();
        this.refs.label.textContent = label;
        this.refs.title.textContent = title;
        this.refs.meta.innerHTML = metaHtml;
    }
}

if (!customElements.get('keel-hero')) {
    customElements.define('keel-hero', KeelHero);
}
