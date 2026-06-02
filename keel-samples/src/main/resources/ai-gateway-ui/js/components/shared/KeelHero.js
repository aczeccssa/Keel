import { KeelElement } from '/js/components/base/KeelElement.js';

/**
 * Headline strip used at the top of every panel. Renders a small uppercase label,
 * a large editorial title, and an optional meta-slot (count, last-updated, etc).
 */
export class KeelHero extends KeelElement {
    hostStyles() { return 'display:block;margin-bottom:6px;'; }

    template() {
        return `
            <style>
                .hero {
                    display: flex;
                    align-items: flex-end;
                    justify-content: space-between;
                    gap: 24px;
                    padding: 8px 0 24px;
                    border-bottom: 1px solid var(--line);
                    margin-bottom: 28px;
                }
                .hero-meta { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
                .hero-label {
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.18em;
                    color: var(--muted);
                }
                .hero-title {
                    font-family: var(--font-headline);
                    font-size: clamp(28px, 4vw, 42px);
                    line-height: 0.95;
                    letter-spacing: -0.04em;
                    color: var(--ink);
                    margin: 0;
                }
                .hero-side { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
            </style>
            <div class="hero" data-ref="root">
                <div class="hero-meta">
                    <span class="hero-label" data-ref="label"></span>
                    <h1 class="hero-title" data-ref="title"></h1>
                </div>
                <div class="hero-side" data-ref="side"></div>
            </div>
        `;
    }

    render({ label = '', title = '', metaHtml = '' } = {}) {
        this.refs.label.textContent = label;
        this.refs.title.textContent = title;
        this.refs.side.innerHTML = metaHtml;
    }
}

customElements.define('keel-hero', KeelHero);
