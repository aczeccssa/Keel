import { KeelElement } from '../base/KeelElement.js';

/**
 * Brutalist data table: solid ink header bar with monospace uppercase headers, hairline ink row
 * dividers, hover-invert rows, mono body cells. Cells are pre-formatted HTML (parent escapes).
 * Wrapped in a hard 2px ink border. The whole body staggers in via GSAP when present.
 */
export class KeelDataTable extends KeelElement {
    hostStyles() { return 'display:block;'; }

    template() {
        return `
            <style>
                :host { display: block; }
                .wrap { overflow-x: auto; border: 2px solid var(--ink); background: var(--paper); }
                table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
                thead th {
                    text-align: left;
                    font-family: var(--font-mono);
                    font-size: 10px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase;
                    color: var(--paper); background: var(--ink);
                    padding: 12px 16px;
                    white-space: nowrap;
                    border-right: 1px solid rgba(244,244,240,0.2);
                }
                thead th:last-child { border-right: 0; }
                tbody td {
                    padding: 12px 16px;
                    border-bottom: 1px solid var(--ink);
                    color: var(--ink);
                    vertical-align: middle;
                    font-family: var(--font-body);
                }
                tbody tr:last-child td { border-bottom: 0; }
                tbody tr { transition: background 100ms var(--ease-smooth), color 100ms; }
                tbody tr:hover { background: var(--ink); }
                tbody tr:hover td { color: var(--paper); }
                tbody tr:hover code { background: var(--paper); color: var(--ink); }
                code {
                    font-family: var(--font-mono);
                    font-size: 11px;
                    background: var(--ink);
                    color: var(--paper);
                    padding: 1px 6px;
                }
                .km-empty {
                    text-align: center; color: var(--muted); padding: 36px 16px;
                    font-family: var(--font-mono); font-size: 12px; letter-spacing: 0.06em; text-transform: uppercase;
                }
            </style>
            <div class="wrap" data-ref="wrap"></div>
        `;
    }

    render({ headers = [], rows = [], emptyHtml = '' } = {}) {
        if (rows.length === 0) {
            this.refs.wrap.innerHTML = emptyHtml || '<div class="km-empty">// NO DATA</div>';
            return;
        }
        const thead = headers.length
            ? `<thead><tr>${headers.map(h => `<th>${h}</th>`).join('')}</tr></thead>`
            : '';
        const tbody = `<tbody>${rows.map(r => `<tr>${r.map(c => `<td>${c}</td>`).join('')}</tr>`).join('')}</tbody>`;
        this.refs.wrap.innerHTML = `<table>${thead}${tbody}</table>`;
        this._animate();
    }

    _animate() {
        const gsap = window.gsap;
        if (!gsap) return;
        const trs = this.refs.wrap.querySelectorAll('tbody tr');
        if (trs.length === 0) return;
        gsap.fromTo(trs, { autoAlpha: 0, x: -8 }, { autoAlpha: 1, x: 0, duration: 0.28, ease: 'power2.out', stagger: 0.02 });
    }
}

customElements.define('keel-data-table', KeelDataTable);
