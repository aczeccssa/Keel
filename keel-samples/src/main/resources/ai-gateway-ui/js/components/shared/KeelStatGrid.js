import { KeelElement } from '../base/KeelElement.js';

/**
 * Grid of 2-4 stat tiles (label, value, optional hint) with animated number tween
 * powered by GSAP. Re-renders any time the parent calls render({entries}).
 */
export class KeelStatGrid extends KeelElement {
    hostStyles() { return 'display:block;'; }

    template() {
        return `
            <style>
                :host { display: block; }
                .grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
                    gap: 18px;
                }
                .tile {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 22px 24px;
                    border: 1px solid rgba(17, 24, 39, 0.04);
                    box-shadow: var(--shadow-sm);
                    position: relative;
                    overflow: hidden;
                }
                .tile::after {
                    content: '';
                    position: absolute;
                    inset: 0;
                    background: linear-gradient(180deg, transparent 0%, rgba(15, 118, 110, 0.04) 100%);
                    pointer-events: none;
                }
                .label {
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.16em;
                    color: var(--muted);
                }
                .value {
                    margin-top: 10px;
                    font-family: var(--font-headline);
                    font-size: clamp(28px, 3vw, 36px);
                    line-height: 0.95;
                    letter-spacing: -0.04em;
                    color: var(--ink);
                    font-feature-settings: 'tnum';
                }
                .hint {
                    margin-top: 8px;
                    font-size: 11px;
                    font-weight: 700;
                    color: var(--muted);
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                }
            </style>
            <div class="grid" data-ref="grid"></div>
        `;
    }

    render({ entries = [] } = {}) {
        this.refs.grid.innerHTML = entries.map(([label, value, hint]) => `
            <div class="tile">
                <div class="label">${this._escape(label)}</div>
                <div class="value" data-value>${this._escape(String(value ?? ''))}</div>
                ${hint ? `<div class="hint">${this._escape(String(hint))}</div>` : ''}
            </div>
        `).join('');
        // Animate tiles in with GSAP if available.
        this._animate();
    }

    _escape(s) {
        return s.replace(/[&<>"']/g, ch => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[ch]));
    }

    _animate() {
        const gsap = window.gsap;
        if (!gsap) return;
        const tiles = this.refs.grid.querySelectorAll('.tile');
        if (tiles.length === 0) return;
        gsap.fromTo(
            tiles,
            { opacity: 0, y: 14 },
            { opacity: 1, y: 0, duration: 0.45, ease: 'power2.out', stagger: 0.06 }
        );
    }
}

customElements.define('keel-stat-grid', KeelStatGrid);
