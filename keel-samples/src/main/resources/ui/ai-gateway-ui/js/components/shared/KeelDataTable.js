import { KeelElement } from '../base/KeelElement.js';

export class KeelDataTable extends KeelElement {
    hostStyles() { return 'display:block;'; }

    template() {
        return `
            <style>
                :host { display: block; min-width: 0; }
                .wrap {
                    overflow-x: auto;
                    background: var(--paper);
                    border-top: 2px solid var(--ink);
                    border-bottom: 2px solid var(--ink);
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    table-layout: auto;
                    font-family: var(--font-mono);
                    font-size: 11px;
                    letter-spacing: 0.02em;
                }
                thead th {
                    position: sticky;
                    top: 0;
                    z-index: 1;
                    text-align: left;
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    color: var(--paper);
                    background: var(--ink);
                    padding: 11px 12px;
                    white-space: nowrap;
                    border-right: 1px solid rgba(244,244,240,0.28);
                }
                thead th::before { content: "// "; color: var(--teal); opacity: 0.6; }
                thead th:last-child { border-right: 0; }
                tbody td {
                    padding: 12px;
                    border-right: 1px solid var(--ink);
                    border-bottom: 1px solid var(--ink);
                    color: var(--ink);
                    vertical-align: middle;
                    font-family: var(--font-mono);
                    line-height: 1.35;
                }
                tbody td:last-child { border-right: 0; }
                tbody tr:last-child td { border-bottom: 0; }
                tbody tr:nth-child(even) td { background: var(--color-surface-container-low, #ebe9e3); }
                tbody tr:hover td { background: var(--ink); color: var(--paper); }
                tbody tr:hover code,
                tbody tr:hover samp,
                tbody tr:hover data { background: var(--paper); color: var(--ink); border-color: var(--paper); }
                code,
                samp,
                data {
                    display: inline-block;
                    max-width: 360px;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    vertical-align: middle;
                    font-family: var(--font-mono);
                    font-size: 10.5px;
                    font-weight: 800;
                    letter-spacing: 0.04em;
                    text-transform: uppercase;
                    background: var(--ink);
                    color: var(--paper);
                    border: 1px solid var(--ink);
                    padding: 2px 6px;
                }
                .chip {
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                    padding: 5px 8px;
                    border: 1px solid var(--ink);
                    background: var(--paper);
                    color: var(--ink);
                    font-family: var(--font-mono);
                    font-size: 9.5px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                    white-space: nowrap;
                }
                .chip.is-healthy { background: var(--green-soft); }
                .chip.is-warn { background: var(--amber-soft); }
                .chip.is-alert { background: var(--red); color: var(--paper); }
                .btn-reset {
                    padding: 7px 11px;
                    border: 2px solid var(--ink);
                    background: var(--paper);
                    color: var(--ink);
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.1em;
                    text-transform: uppercase;
                    cursor: pointer;
                    transition: background 120ms var(--ease-smooth), color 120ms var(--ease-smooth), transform 120ms var(--ease-smooth);
                }
                .btn-reset:hover { background: var(--red); color: var(--paper); transform: translate(-1px, -1px); }
                tbody tr:hover .chip { border-color: var(--paper); }
                tbody tr:hover .chip.is-alert,
                tbody tr:hover .btn-reset { border-color: var(--paper); }
                .km-empty {
                    text-align: center;
                    color: var(--muted);
                    padding: 36px 16px;
                    font-family: var(--font-mono);
                    font-size: 12px;
                    font-weight: 800;
                    letter-spacing: 0.1em;
                    text-transform: uppercase;
                }
            </style>
            <div class="wrap" data-ref="wrap"></div>
        `;
    }

    render({ headers = [], rows = [], emptyHtml = '', silent = false } = {}) {
        if (rows.length === 0) {
            this.refs.wrap.innerHTML = emptyHtml || '<div class="km-empty">// NO DATA</div>';
            this._lastRows = [];
            return;
        }
        // Silent path: if we already have a rendered table with the same header count,
        // diff rows by their first cell (assumed primary key) and update in place.
        if (silent && this._lastRows && this._lastHeaders && this._lastHeaders.length === headers.length) {
            this._diffRows(rows);
            this._lastRows = rows;
            this._lastHeaders = headers;
            return;
        }
        const thead = headers.length
            ? `<thead><tr>${headers.map(h => `<th>${h}</th>`).join('')}</tr></thead>`
            : '';
        const tbody = `<tbody>${rows.map(r => `<tr>${r.map(c => `<td>${c}</td>`).join('')}</tr>`).join('')}</tbody>`;
        this.refs.wrap.innerHTML = `<table>${thead}${tbody}</table>`;
        this._lastRows = rows;
        this._lastHeaders = headers;
        this._animate();
    }

    _diffRows(newRows) {
        const tbody = this.refs.wrap.querySelector('tbody');
        if (!tbody) return;
        const oldKeyToTr = new Map();
        Array.from(tbody.querySelectorAll('tr')).forEach(tr => {
            const key = tr.dataset.k || (tr.dataset.k = tr.cells[0]?.textContent?.trim() || '');
            oldKeyToTr.set(key, tr);
        });
        const newKeys = new Set();
        newRows.forEach(row => {
            const key = (row[0] || '').replace(/<[^>]+>/g, '').trim();
            newKeys.add(key);
            const cellsHtml = row.map(c => `<td>${c}</td>`).join('');
            const existing = oldKeyToTr.get(key);
            if (existing) {
                // Compare cell content; flash changed cells.
                const newCellTexts = row.map(c => c.replace(/<[^>]+>/g, '').trim());
                Array.from(existing.cells).forEach((td, i) => {
                    const oldText = td.textContent.trim();
                    const newText = newCellTexts[i] ?? '';
                    if (oldText !== newText) {
                        td.innerHTML = row[i] ?? '';
                        this._flashCell(td);
                    }
                });
            } else {
                const tr = document.createElement('tr');
                tr.innerHTML = cellsHtml;
                tr.style.opacity = '0';
                tbody.appendChild(tr);
                requestAnimationFrame(() => { tr.style.transition = 'opacity 300ms'; tr.style.opacity = '1'; });
            }
        });
        // Remove rows that no longer exist.
        oldKeyToTr.forEach((tr, key) => { if (!newKeys.has(key)) tr.remove(); });
    }

    _flashCell(td) {
        const prev = td.style.backgroundColor;
        td.style.transition = 'background-color 300ms ease';
        td.style.backgroundColor = 'var(--teal-soft)';
        setTimeout(() => { td.style.backgroundColor = prev; }, 300);
    }

    _animate() {
        const gsap = window.gsap;
        if (!gsap) return;
        const trs = this.refs.wrap.querySelectorAll('tbody tr');
        if (trs.length === 0) return;
        gsap.fromTo(trs, { autoAlpha: 0, x: -8 }, { autoAlpha: 1, x: 0, duration: 0.24, ease: 'power2.out', stagger: 0.018 });
    }
}

customElements.define('keel-data-table', KeelDataTable);
