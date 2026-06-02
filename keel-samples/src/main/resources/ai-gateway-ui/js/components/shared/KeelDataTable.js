import { KeelElement } from '/js/components/base/KeelElement.js';

/**
 * Styled data table. Cells are pre-formatted HTML (the parent is responsible for
 * escaping), so this is intentionally not a security boundary. The whole table
 * gets a subtle stagger-in via GSAP if it is present on the page.
 */
export class KeelDataTable extends KeelElement {
    hostStyles() { return 'display:block;'; }

    template() {
        return `
            <style>
                :host { display: block; }
                .wrap {
                    overflow-x: auto;
                    border-radius: var(--radius-md);
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    font-size: 13px;
                }
                thead th {
                    text-align: left;
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                    color: var(--muted);
                    padding: 14px 18px;
                    border-bottom: 1px solid var(--line);
                    background: var(--color-surface-container-low, #f3f1ed);
                }
                tbody td {
                    padding: 14px 18px;
                    border-bottom: 1px solid rgba(17, 24, 39, 0.04);
                    color: var(--ink);
                    vertical-align: middle;
                }
                tbody tr:last-child td { border-bottom: 0; }
                tbody tr { transition: background 150ms ease; }
                tbody tr:hover { background: rgba(15, 118, 110, 0.04); }
                code {
                    font-family: var(--font-mono);
                    font-size: 11px;
                    background: rgba(15, 23, 42, 0.05);
                    padding: 1px 6px;
                    border-radius: 4px;
                }
            </style>
            <div class="wrap" data-ref="wrap"></div>
        `;
    }

    render({ headers = [], rows = [], emptyHtml = '' } = {}) {
        if (rows.length === 0) {
            this.refs.wrap.innerHTML = emptyHtml || '<div style="text-align:center;color:var(--muted);padding:32px;font-size:13px;">No data</div>';
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
        gsap.fromTo(
            trs,
            { opacity: 0, y: 8 },
            { opacity: 1, y: 0, duration: 0.3, ease: 'power2.out', stagger: 0.025 }
        );
    }
}

customElements.define('keel-data-table', KeelDataTable);
