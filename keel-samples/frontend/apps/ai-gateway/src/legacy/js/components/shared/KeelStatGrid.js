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
                .grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                    gap: 16px;
                    background: transparent;
                }
                .tile {
                    background: var(--paper);
                    padding: 22px 22px 20px;
                    border: 2px solid var(--ink);
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

    render({ entries = [], silent = false } = {}) {
        // Silent path: same number of tiles, just swap values in place with a tween + flash.
        if (silent && this.refs.grid.children.length === entries.length) {
            entries.forEach(([label, value, hint], i) => {
                const tile = this.refs.grid.children[i];
                const valueEl = tile.querySelector('.value');
                const hintEl = tile.querySelector('.hint');
                const newVal = String(value ?? '');
                if (valueEl && valueEl.textContent !== newVal) {
                    this._tweenValue(valueEl, valueEl.textContent, newVal);
                }
                if (hintEl) hintEl.textContent = hint ? String(hint) : '';
            });
            return;
        }
        this.refs.grid.innerHTML = entries.map(([label, value, hint]) => `
            <div class="tile">
                <div class="label">${this._escape(label)}</div>
                <div class="value">${this._escape(String(value ?? ''))}</div>
                ${hint ? `<div class="hint">${this._escape(String(hint))}</div>` : ''}
            </div>
        `).join('');
        this._animate();
    }

    _tweenValue(el, from, to) {
        // Parse leading non-digit prefix and trailing non-digit suffix (e.g. "$" prefix, "%" suffix, "K" suffix).
        const parse = (s) => {
            const m = String(s).match(/^(\D*?)([\d.,]+)(.*)$/);
            if (!m) return null;
            return { prefix: m[1], num: parseFloat(m[2].replace(/,/g, '')) || 0, suffix: m[3], decimals: (m[2].split('.')[1] || '').length };
        };
        const a = parse(from);
        const b = parse(to);
        if (!a || !b) { el.textContent = to; return; }
        const start = performance.now();
        const duration = 600;
        const step = (now) => {
            const t = Math.min((now - start) / duration, 1);
            const eased = 1 - Math.pow(1 - t, 3);
            const current = a.num + (b.num - a.num) * eased;
            el.textContent = a.prefix + current.toLocaleString('en-US', {
                minimumFractionDigits: b.decimals,
                maximumFractionDigits: b.decimals,
            }) + b.suffix;
            if (t < 1) requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
        // Brief teal flash
        el.style.transition = 'color 200ms ease';
        const prev = el.style.color;
        el.style.color = 'var(--teal)';
        setTimeout(() => { el.style.color = prev; }, 400);
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
