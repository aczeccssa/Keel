/**
 * Public-facing (no login) playground for the Keel AI Gateway.
 *
 * Reuses the same visual language as the management console but trims away
 * the sidebar/login/user-pill chrome. Designed for "try it now" landing
 * pages and external link shares. Sign in is offered as a single button
 * that drops users back into the management console.
 */
import { KeelElement } from './components/base/KeelElement.js';

const DEMO_MODELS = [
    'claude-sonnet-4-20250514',
    'gpt-4o-mini',
    'gpt-4o',
];

class CEndPlayground extends KeelElement {
    hostStyles() { return 'display:block;min-height:100vh;background:var(--bg);'; }

    template() {
        return `
            <style>
                :host {
                    --font-display: "Newsreader", Georgia, "Times New Roman", serif;
                    --font-headline: "Newsreader", Georgia, "Times New Roman", serif;
                    --font-body: "Quicksand", system-ui, -apple-system, sans-serif;
                    --font-mono: ui-monospace, "SFMono-Regular", Menlo, Monaco, Consolas, monospace;
                }
                .frame {
                    max-width: 980px;
                    margin: 0 auto;
                    padding: 56px 32px 80px;
                }
                .nav {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    margin-bottom: 64px;
                }
                .brand { display:flex; align-items:center; gap:12px; }
                .brand h1 {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 22px;
                    font-weight: 700;
                    letter-spacing: -0.02em;
                }
                .brand-mark {
                    width: 28px; height: 28px;
                    background: var(--navy);
                    border-radius: 8px;
                    display: flex; align-items: center; justify-content: center;
                    color: var(--teal);
                    font-family: var(--font-mono);
                    font-weight: 800;
                    font-size: 12px;
                }
                .signin {
                    padding: 10px 18px;
                    border-radius: 999px;
                    background: var(--navy);
                    color: #f8fafc;
                    font-size: 11px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    border: 0;
                    cursor: pointer;
                    text-decoration: none;
                }
                .hero h2 {
                    font-family: var(--font-headline);
                    font-size: clamp(40px, 6vw, 64px);
                    line-height: 0.95;
                    letter-spacing: -0.04em;
                    margin: 0 0 16px;
                }
                .hero p {
                    font-size: 16px;
                    line-height: 1.5;
                    color: var(--muted);
                    margin: 0 0 40px;
                    max-width: 640px;
                }
                .playground {
                    background: var(--panel-strong);
                    border-radius: var(--radius-xl);
                    padding: 36px;
                    box-shadow: var(--shadow-lg);
                    border: 1px solid rgba(17, 24, 39, 0.04);
                }
                .row { display:grid; grid-template-columns: 1fr 1fr 1fr; gap: 16px; margin-bottom: 18px; }
                .field label {
                    display: block;
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                    color: var(--muted);
                    margin-bottom: 8px;
                }
                .field input, .field select, .field textarea {
                    width: 100%;
                    padding: 12px 14px;
                    border: 0;
                    border-radius: var(--radius-sm);
                    font-size: 14px;
                    background: var(--color-surface-container-high, #e4e2dc);
                    color: var(--ink);
                }
                .field textarea { font-family: var(--font-mono); min-height: 120px; resize: vertical; }
                .field input:focus, .field textarea:focus, .field select:focus {
                    outline: none; box-shadow: 0 2px 0 0 var(--teal);
                    background: var(--color-surface-container-lowest, #fff);
                }
                .send {
                    width: 100%;
                    padding: 16px 0;
                    background: var(--navy);
                    color: #f8fafc;
                    border: 0;
                    border-radius: 999px;
                    font-size: 12px;
                    font-weight: 800;
                    letter-spacing: 0.16em;
                    text-transform: uppercase;
                    cursor: pointer;
                    margin-top: 8px;
                }
                .send:hover { background: var(--navy-2); }
                .send:disabled { opacity: 0.6; cursor: not-allowed; }
                .output {
                    margin-top: 24px;
                    padding: 20px 22px;
                    background: var(--color-surface-container-lowest, #fff);
                    border-radius: var(--radius-md);
                    font-family: var(--font-mono);
                    font-size: 13px;
                    line-height: 1.6;
                    min-height: 120px;
                    white-space: pre-wrap;
                    word-break: break-word;
                    color: var(--ink);
                }
                .output.error { color: var(--red); }
                .meta {
                    margin-top: 12px;
                    font-size: 11px;
                    font-weight: 700;
                    color: var(--muted);
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                }
                .empty-line { color: var(--muted); }
                @media (max-width: 720px) { .row { grid-template-columns: 1fr; } }
            </style>
            <div class="frame">
                <nav class="nav">
                    <div class="brand">
                        <div class="brand-mark">K</div>
                        <h1>Keel AI Gateway</h1>
                    </div>
                    <a class="signin" href="/api/plugins/observability/ui/" data-ref="signinBtn">Sign in</a>
                </nav>
                <section class="hero" data-ref="hero">
                    <h2>Try the gateway in your browser.</h2>
                    <p>Send a real request through the AI Gateway relay. Choose any model — the gateway handles protocol transcoding automatically. No signup required for the demo key.</p>
                </section>
                <div class="playground" data-ref="card">
                    <div class="row">
                        <div class="field">
                            <label>Model</label>
                            <select data-ref="model">${DEMO_MODELS.map(m => `<option>${m}</option>`).join('')}</select>
                        </div>
                        <div class="field">
                            <label>Max tokens</label>
                            <input data-ref="maxTokens" type="number" value="256">
                        </div>
                        <div class="field">
                            <label>Temperature</label>
                            <input data-ref="temperature" type="number" step="0.1" value="0.7" min="0" max="2">
                        </div>
                    </div>
                    <div class="field">
                        <label>Prompt</label>
                        <textarea data-ref="prompt">Say hello in one short sentence.</textarea>
                    </div>
                    <button class="send" data-ref="sendBtn">Send</button>
                    <div class="output" data-ref="output"><span class="empty-line">Response will appear here.</span></div>
                    <div class="meta" data-ref="meta" style="display:none;"></div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.sendBtn.addEventListener('click', () => this._send());
        this._reveal();
    }

    _reveal() {
        const gsap = window.gsap;
        if (!gsap) return;
        gsap.fromTo(
            [this.refs.hero, this.refs.card],
            { autoAlpha: 0, y: 16 },
            { autoAlpha: 1, y: 0, duration: 0.55, ease: 'power2.out', stagger: 0.1 }
        );
    }

    async _send() {
        const model = this.refs.model.value;
        const maxTokens = parseInt(this.refs.maxTokens.value) || 256;
        const temperature = parseFloat(this.refs.temperature.value) || 0.7;
        const prompt = this.refs.prompt.value.trim();
        if (!prompt) { this.refs.output.textContent = 'Please enter a prompt.'; return; }

        this.refs.sendBtn.disabled = true;
        this.refs.sendBtn.textContent = 'Sending…';
        this.refs.output.className = 'output';
        this.refs.output.textContent = '';
        this.refs.meta.style.display = 'none';

        const start = Date.now();
        try {
            // The c-end playground posts directly to the Anthropic-compatible upstream. The
            // gateway's own playground (inside the management console) exercises the full
            // transcoding path; this is a minimal "does it work?" surface.
            const apiKey = window.__KEEL_CEND_API_KEY__ || '';
            const resp = await fetch('/api/plugins/airelay/v1/messages', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${apiKey}`,
                },
                body: JSON.stringify({
                    model,
                    max_tokens: maxTokens,
                    temperature,
                    messages: [{ role: 'user', content: prompt }],
                }),
            });
            const text = await resp.text();
            const elapsed = Date.now() - start;
            this.refs.meta.style.display = 'block';
            this.refs.meta.textContent = `${resp.status} · ${elapsed}ms · ${model}`;
            if (!resp.ok) {
                this.refs.output.className = 'output error';
                this.refs.output.textContent = text;
            } else {
                const body = JSON.parse(text);
                const inner = body.response ? JSON.parse(body.response) : body;
                const content = (inner.content || [])
                    .map(b => b.text || '')
                    .join('');
                this.refs.output.textContent = content || JSON.stringify(inner, null, 2);
            }
        } catch (e) {
            this.refs.output.className = 'output error';
            this.refs.output.textContent = e.message;
        } finally {
            this.refs.sendBtn.disabled = false;
            this.refs.sendBtn.textContent = 'Send';
        }
    }
}

customElements.define('c-end-playground', CEndPlayground);
