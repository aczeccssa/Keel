import { KeelElement } from '../base/KeelElement.js';

/**
 * Key/value detail list used in management surfaces. Each row is a label + a value
 * (the value is rendered as HTML, the parent is responsible for escaping).
 */
export class KeelDetailList extends KeelElement {
    hostStyles() { return 'display:block;'; }

    template() {
        return `
            <style>
                :host { display: block; }
                dl {
                    margin: 0;
                    display: grid;
                    grid-template-columns: minmax(140px, 0.4fr) minmax(0, 1fr);
                    gap: 0;
                }
                dt {
                    padding: 14px 18px;
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                    color: var(--muted);
                    border-bottom: 1px solid rgba(17, 24, 39, 0.04);
                }
                dd {
                    margin: 0;
                    padding: 14px 18px;
                    font-size: 13px;
                    color: var(--ink);
                    border-bottom: 1px solid rgba(17, 24, 39, 0.04);
                    word-break: break-word;
                }
            </style>
            <dl data-ref="dl"></dl>
        `;
    }

    render({ items = [] } = {}) {
        this.refs.dl.innerHTML = items.map(([label, value]) => `
            <dt>${this._escape(label)}</dt>
            <dd>${value}</dd>
        `).join('');
    }

    _escape(s) {
        return String(s).replace(/[&<>"']/g, ch => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[ch]));
    }
}

customElements.define('keel-detail-list', KeelDetailList);
