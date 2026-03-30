import { KeelElement } from './base/KeelElement.js';
import { renderLogDetail } from '../render.js';

export class LogDetail extends KeelElement {
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
                    gap: 16px;
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
                .log-detail-template {
                    display: grid;
                    gap: 18px;
                }
                .log-meta-row {
                    display: flex;
                    gap: 8px;
                    flex-wrap: wrap;
                }
                .badge {
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    padding: 7px 10px;
                    border-radius: 999px;
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.1em;
                    text-transform: uppercase;
                    border: 1px solid transparent;
                }
                .badge.ok {
                    background: var(--green-soft);
                    color: var(--green);
                    border-color: rgba(19, 130, 79, 0.18);
                }
                .badge.warn {
                    background: var(--amber-soft);
                    color: var(--amber);
                    border-color: rgba(197, 106, 27, 0.18);
                }
                .badge.err {
                    background: var(--red-soft);
                    color: var(--red);
                    border-color: rgba(198, 40, 40, 0.18);
                }
                .badge.neutral {
                    background: rgba(102, 112, 133, 0.1);
                    color: var(--muted);
                    border-color: rgba(102, 112, 133, 0.14);
                }
                .topo-node-kv {
                    display: flex;
                    flex-direction: column;
                    border-top: 1px solid rgba(17, 24, 39, 0.08);
                }
                .topo-kv-row {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    padding: 11px 0;
                    border-bottom: 1px solid rgba(17, 24, 39, 0.06);
                }
                .topo-kv-label {
                    color: var(--muted);
                    font-size: 11px;
                    font-weight: 700;
                    letter-spacing: 0.04em;
                }
                .topo-kv-val {
                    color: var(--ink);
                    font-family: var(--font-headline);
                    font-size: 14px;
                    font-weight: 700;
                    text-align: right;
                    word-break: break-word;
                }
                .topo-kv-val.muted {
                    color: var(--muted);
                    font-family: var(--font-mono);
                    font-size: 11px;
                    font-weight: 500;
                }
                .log-attr-block,
                .throwable-block {
                    padding: 16px 18px;
                    border-radius: 18px;
                    background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(246, 243, 236, 0.68));
                    border: 1px solid rgba(17, 24, 39, 0.06);
                }
                .throwable-block {
                    background: #0f172a;
                    color: #dbeafe;
                    font-family: var(--font-mono);
                    font-size: 11px;
                    line-height: 1.7;
                    white-space: pre-wrap;
                    word-break: break-word;
                }
                .empty {
                    display: grid;
                    place-items: center;
                    min-height: 160px;
                    padding: 24px;
                    border-radius: 18px;
                    border: 1px dashed var(--line-strong);
                    background: rgba(255, 255, 255, 0.56);
                    color: var(--muted);
                    text-align: center;
                    font-size: 13px;
                    line-height: 1.6;
                }
            </style>
            <div class="detail-list scroll-panel" data-ref="body"></div>
        `;
    }

    render({ selected = null } = {}) {
        this.ensureInitialized();
        this.refs.body.innerHTML = renderLogDetail(selected);
    }
}

if (!customElements.get('keel-log-detail')) {
    customElements.define('keel-log-detail', LogDetail);
}
