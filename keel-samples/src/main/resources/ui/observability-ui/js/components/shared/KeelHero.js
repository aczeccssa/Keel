import { KeelElement } from '../base/KeelElement.js';

export class KeelHero extends KeelElement {
    hostStyles() {
        return 'flex: 0 0 auto;';
    }

    template() {
        return `
            <style>
                .hero {
                    display: flex;
                    flex-wrap: nowrap;
                    align-items: flex-end;
                    justify-content: space-between;
                    gap: 18px;
                    margin-bottom: 24px;
                    flex-shrink: 0;
                }
                .section-label {
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    margin-bottom: 10px;
                }
                .hero h2 {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: clamp(32px, 3.5vw, 40px);
                    line-height: 0.95;
                    letter-spacing: -0.04em;
                }
                .hero-meta {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 10px;
                }
            </style>
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
