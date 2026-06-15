import { KeelElement } from '../base/KeelElement.js';

export class KeelHero extends KeelElement {
    hostStyles() { return 'display:block;'; }

    template() {
        return `
            <style>
                .hero {
                    position: relative;
                    display: grid;
                    gap: 0;
                    border: 2px solid var(--ink);
                    margin-bottom: 24px;
                    min-height: 156px;
                    background:
                        linear-gradient(180deg, rgba(20, 184, 166, 0.05), transparent 54%),
                        repeating-linear-gradient(90deg, transparent 0, transparent 12px, rgba(255,255,255,0.025) 12px, rgba(255,255,255,0.025) 13px),
                        repeating-linear-gradient(0deg, transparent 0, transparent 36px, rgba(255,255,255,0.025) 36px, rgba(255,255,255,0.025) 37px),
                        var(--surface-muted);
                    color: var(--ink);
                    overflow: hidden;
                }
                .hero::before {
                    content: "";
                    position: absolute;
                    inset: 0 0 auto;
                    height: 42px;
                    pointer-events: none;
                    background: linear-gradient(90deg, var(--surface-accent), rgba(0, 0, 0, 0.18));
                    border-bottom: 1px solid rgba(235, 231, 223, 0.16);
                }
                .hero-shell {
                    position: relative;
                    z-index: 1;
                    display: grid;
                    grid-template-rows: auto 1fr;
                    min-height: inherit;
                }
                .hero-strip {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    min-width: 0;
                    min-height: 42px;
                    padding: 11px 18px;
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.18em;
                    text-transform: uppercase;
                    color: var(--on-accent);
                }
                .hero-label {
                    min-width: 0;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                }
                .hero-coord { color: var(--teal); white-space: nowrap; opacity: 0.9; }
                .hero-main {
                    display: grid;
                    grid-template-columns: minmax(0, 1fr) auto;
                    gap: 24px;
                    align-items: end;
                    padding: 22px 18px 18px;
                }
                .hero-copy {
                    display: grid;
                    gap: 10px;
                    align-content: end;
                    min-width: 0;
                }
                .hero-caption {
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    color: var(--muted);
                }
                .hero-title {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: clamp(34px, 4.8vw, 64px);
                    line-height: 0.88;
                    letter-spacing: -0.05em;
                    text-transform: uppercase;
                    color: var(--ink);
                    max-width: 12ch;
                    overflow-wrap: anywhere;
                }
                .hero-side {
                    display: flex;
                    align-items: flex-end;
                    justify-content: flex-end;
                    min-width: 0;
                    max-width: 320px;
                }
                .hero-side:empty {
                    display: none;
                }
                .hero-side :is(div, span, strong, code) {
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.1em;
                    text-transform: uppercase;
                }
                @media (max-width: 900px) {
                    .hero {
                        min-height: 0;
                    }
                    .hero-main {
                        grid-template-columns: 1fr;
                        align-items: start;
                    }
                    .hero-side {
                        justify-content: flex-start;
                        max-width: none;
                    }
                    .hero-title { font-size: clamp(34px, 11vw, 56px); max-width: none; }
                }
            </style>
            <section class="hero" data-ref="root">
                <div class="hero-shell">
                    <div class="hero-strip">
                        <span class="hero-label" data-ref="label"></span>
                        <samp class="hero-coord">REV/03 + GRID/24</samp>
                    </div>
                    <div class="hero-main">
                        <div class="hero-copy">
                            <span class="hero-caption">System control surface</span>
                            <h1 class="hero-title" data-ref="title"></h1>
                        </div>
                        <aside class="hero-side" data-ref="side"></aside>
                    </div>
                </div>
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
