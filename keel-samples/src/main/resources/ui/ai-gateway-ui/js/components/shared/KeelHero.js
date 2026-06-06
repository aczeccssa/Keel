import { KeelElement } from '../base/KeelElement.js';

export class KeelHero extends KeelElement {
    hostStyles() { return 'display:block;'; }

    template() {
        return `
            <style>
                .hero {
                    position: relative;
                    display: grid;
                    grid-template-columns: minmax(0, 1fr) auto;
                    gap: 1px;
                    background: var(--ink);
                    border: 2px solid var(--ink);
                    margin-bottom: 24px;
                    isolation: isolate;
                }
                .hero::before {
                    content: "";
                    position: absolute;
                    inset: 0;
                    pointer-events: none;
                    background-image:
                        repeating-linear-gradient(90deg, transparent 0, transparent 8px, rgba(11,11,11,0.08) 8px, rgba(11,11,11,0.08) 10px),
                        repeating-linear-gradient(0deg, transparent 0, transparent 34px, rgba(11,11,11,0.06) 34px, rgba(11,11,11,0.06) 35px);
                    mix-blend-mode: multiply;
                    z-index: 2;
                }
                .hero-main,
                .hero-side {
                    background: var(--paper);
                    min-width: 0;
                }
                .hero-main {
                    display: grid;
                    grid-template-rows: auto auto;
                }
                .hero-strip {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    min-width: 0;
                    padding: 13px 18px;
                    border-bottom: 1px solid var(--ink);
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.18em;
                    text-transform: uppercase;
                    color: var(--ink);
                }
                .hero-label {
                    min-width: 0;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                }
                .hero-coord { color: var(--red); white-space: nowrap; }
                .hero-title {
                    margin: 0;
                    padding: 24px 18px 28px;
                    font-family: var(--font-headline);
                    font-size: clamp(46px, 8vw, 118px);
                    line-height: 0.82;
                    letter-spacing: -0.06em;
                    text-transform: uppercase;
                    color: var(--ink);
                    max-width: 12ch;
                    overflow-wrap: anywhere;
                }
                .hero-side {
                    display: grid;
                    align-content: stretch;
                    min-width: 190px;
                }
                .hero-side:empty::before {
                    content: "/// KEEL / RELAY";
                    display: grid;
                    place-items: center;
                    writing-mode: vertical-rl;
                    min-height: 100%;
                    padding: 18px 14px;
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.18em;
                    text-transform: uppercase;
                    color: var(--ink);
                    background: repeating-linear-gradient(180deg, var(--paper) 0, var(--paper) 7px, #dedbd2 7px, #dedbd2 9px);
                }
                @media (max-width: 900px) {
                    .hero { grid-template-columns: 1fr; }
                    .hero-side { min-width: 0; }
                    .hero-side:empty { display: none; }
                    .hero-title { font-size: clamp(42px, 14vw, 84px); max-width: none; }
                }
            </style>
            <section class="hero" data-ref="root">
                <div class="hero-main">
                    <div class="hero-strip">
                        <span class="hero-label" data-ref="label"></span>
                        <samp class="hero-coord">REV/02 + GRID/19</samp>
                    </div>
                    <h1 class="hero-title" data-ref="title"></h1>
                </div>
                <aside class="hero-side" data-ref="side"></aside>
            </section>
        `;
    }

    render({ label = '', title = '', metaHtml = '' } = {}) {
        this.refs.label.textContent = `[ ${label} ]`;
        this.refs.title.textContent = title;
        this.refs.side.innerHTML = metaHtml;
    }
}

customElements.define('keel-hero', KeelHero);
