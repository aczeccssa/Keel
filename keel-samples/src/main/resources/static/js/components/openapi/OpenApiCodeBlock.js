import { KeelElement } from '../base/KeelElement.js';

export class OpenApiCodeBlock extends KeelElement {
    template() {
        return `
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
