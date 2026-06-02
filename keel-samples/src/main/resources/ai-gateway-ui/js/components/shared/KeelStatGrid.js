import { KeelElement } from '../base/KeelElement.js';

/**
 * Brutalist stat tiles: razor 1px grid lines between hard-bordered cells, big Archivo Black
 * numerals, monospace labels. Reveal staggered with GSAP if present.
 */
export class KeelStatGrid extends KeelElement {
    hostStyles() { return 'display:block;'; }

    template() {
        return `
            <style>
                :host { display: block; }
                /* gap:2px over an ink background draws solid dividing lines between tiles */
                .grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
                    gap: 2px;
                    background: var(--ink);
                    border: 2px solid var(--ink);
                }
                .tile {
                    background: var(--paper);
                    padding: 22px 22px 20px;
                    position: relative;
                }
                .label {
                    font-family: var(--font-mono);
                    font-size: 10px; font-weight: 800; letter-spacing: 0.16em; text-transform: uppercase;
                    color: var(--ink);
                    display: flex; align-items: center; gap: 7px;
                }
                .label::before { content: ">"; color: var(--teal); font-weight: 800; }
                .value {
                    margin-top: 12px;
                    font-family: var(--font-headline);
                    font-size: clamp(28px, 3vw, 38px);
                    line-height: 0.9;
                    letter-spacing: -0.04em;
                    color: var(--ink);
                    font-feature-settings: 'tnum';
                    word-break: break-word;
                }
                .hint {
                    margin-top: 9px;
                    font-family: var(--font-mono);
                    font-size: 10px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase;
                    color: var(--muted);
                }
            </style>
            <div class="grid" data-ref="grid"></div>
        `;
    }

    render({ entries = [] } = {}) {
        this.refs.grid.innerHTML = entries.map(([label, value, hint]) => `
            <div class="tile">
                <div class="label">${this._escape(label)}</div>
                <div class="value">${this._escape(String(value ?? ''))}</div>
                ${hint ? `<div class="hint">${this._escape(String(hint))}</div>` : ''}
            </div>
        `).join('');
        this._animate();
    }

    _escape(s) {
        return s.replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
    }

    _animate() {
        const gsap = window.gsap;
        const tiles = this.refs.grid.querySelectorAll('.tile');
        if (!gsap || tiles.length === 0) return;
        gsap.fromTo(tiles, { autoAlpha: 0, y: 12 }, { autoAlpha: 1, y: 0, duration: 0.4, ease: 'power2.out', stagger: 0.05 });
    }
}

customElements.define('keel-stat-grid', KeelStatGrid);
