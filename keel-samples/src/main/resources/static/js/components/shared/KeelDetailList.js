import { KeelElement } from '../base/KeelElement.js';

export class KeelDetailList extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <style>
                .detail-list {
                    display: flex;
                    flex-direction: column;
                    overflow-y: auto;
                }
                .detail-list > .list-item {
                    border-radius: 0;
                    background: transparent;
                    border: 0;
                    border-bottom: 1px solid rgba(17, 24, 39, 0.05);
                    padding: 9px 0;
                }
                .detail-list > .list-item:first-child {
                    padding-top: 0;
                }
                .detail-list > .list-item:last-child {
                    border-bottom: none;
                    padding-bottom: 0;
                }
                .detail-list > .list-item > .section-label {
                    margin-bottom: 4px;
                }
                .section-label {
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                }
                .scroll-panel::-webkit-scrollbar {
                    width: 6px;
                    height: 6px;
                }
                .scroll-panel::-webkit-scrollbar-thumb {
                    background: rgba(15, 23, 42, 0.15);
                    border-radius: 999px;
                }
                .scroll-panel::-webkit-scrollbar-track {
                    background: transparent;
                }
            </style>
            <div data-ref="list"></div>
        `;
    }

    render({ html = '', className = 'detail-list scroll-panel' } = {}) {
        this.ensureInitialized();
        this.refs.list.className = className;
        this.refs.list.innerHTML = html;
    }
}

if (!customElements.get('keel-detail-list')) {
    customElements.define('keel-detail-list', KeelDetailList);
}
