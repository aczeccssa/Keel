import { KeelElement } from '../base/KeelElement.js';

export class OpenApiCodeBlock extends KeelElement {
    template() {
        return `
            <style>
                .openapi-code-card {
                    border-radius: 0.75rem;
                    background: #0f172a;
                    overflow: hidden;
                }
                .openapi-code-head {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    gap: 12px;
                    padding: 14px 16px;
                    color: rgba(255, 255, 255, 0.65);
                    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
                }
                .openapi-code-lang {
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.12em;
                }
                .openapi-copy-btn {
                    border: 0;
                    padding: 0;
                    background: transparent;
                    color: inherit;
                    cursor: pointer;
                    font: inherit;
                }
                .openapi-copy-btn:hover {
                    color: #fff;
                }
                .openapi-code-pre {
                    margin: 0;
                    padding: 18px 16px;
                    overflow: auto;
                    white-space: pre-wrap;
                    word-break: break-word;
                    color: #dbeafe;
                    font-family: var(--font-mono);
                    font-size: 12px;
                    line-height: 1.6;
                }
            </style>
            <div class="openapi-code-card">
                <div class="openapi-code-head">
                    <span class="openapi-code-lang" data-ref="language">json</span>
                    <button class="openapi-copy-btn" data-ref="copyBtn" type="button">Copy</button>
                </div>
                <pre class="openapi-code-pre" data-ref="codePre"></pre>
            </div>
        `;
    }

    afterMount() {
        this.refs.copyBtn.addEventListener('click', async () => {
            const code = this.refs.codePre.textContent || '';
            try {
                await navigator.clipboard.writeText(code);
            } catch {
                // Ignore clipboard errors, text remains selectable.
            }
            this.emit('keel:openapi-copy', { language: this.refs.language.textContent || 'json' });
        });
    }

    render({ code = '{}', language = 'json' } = {}) {
        this.ensureInitialized();
        this.refs.language.textContent = String(language || 'json');
        this.refs.codePre.textContent = String(code || '{}');
    }
}

if (!customElements.get('keel-openapi-code-block')) {
    customElements.define('keel-openapi-code-block', OpenApiCodeBlock);
}
