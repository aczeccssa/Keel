import { KeelElement } from '../base/KeelElement.js';

/**
 * Brutalist section header: a hazard-red marker, a monospace label, and a massive Archivo Black
 * title. This is the SINGLE page title (the topbar shows only a system breadcrumb), so it never
 * duplicates.
 */
export class KeelHero extends KeelElement {
    hostStyles() { return 'display:block;margin-bottom:8px;'; }

    template() {
        return `
            <style>
                .hero {
                    display: flex;
                    align-items: flex-end;
                    justify-content: space-between;
                    gap: 24px;
                    padding: 0 0 18px;
                    border-bottom: 2px solid var(--ink);
                    margin-bottom: 26px;
                }
                .hero-meta { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
                .hero-label {
                    display: inline-flex; align-items: center; gap: 9px;
                    font-family: var(--font-mono);
                    font-size: 11px; font-weight: 800; letter-spacing: 0.18em; text-transform: uppercase;
                    color: var(--ink);
                }
                .hero-label::before {
                    content: ""; width: 13px; height: 13px; background: var(--teal); flex-shrink: 0;
                }
                .hero-title {
                    font-family: var(--font-headline);
                    font-size: clamp(34px, 5vw, 56px);
                    line-height: 0.86;
                    letter-spacing: -0.045em;
                    text-transform: uppercase;
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
