import { KeelElement } from './base/KeelElement.js';
import { fetchWithAuth } from '../api.js';
import { API, DEMO_MODELS } from '../config.js';

export class PanelPlayground extends KeelElement {
    hostStyles() { return 'height:100%;'; }

    template() {
        return `
            <style>
                .panel-layout { display: flex; flex-direction: column; gap: 28px; }
                .playground-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; min-height: 60vh; }
                .pane {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    box-shadow: var(--shadow-sm);
                    border: 2px solid var(--ink);
                    display: flex;
                    flex-direction: column;
                    overflow: hidden;
                }
                .pane-header {
                    padding: 18px 24px;
                    background: var(--color-surface-container-low, #f3f1ed);
                    border-bottom: 2px solid var(--ink);
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                }
                .pane-title {
                    font-family: var(--font-headline);
                    font-size: 18px;
                    font-weight: 500;
                    color: var(--ink);
                    margin: 0;
                }
                .pane-body { flex: 1; overflow: auto; padding: 24px; }
                .field { margin-bottom: 16px; }
                .field label {
                    display: block; font-size: 10px; font-weight: 800; text-transform: uppercase;
                    letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px;
                }
                .field input, .field select, .field textarea {
                    width: 100%; padding: 10px 12px; border: 0; border-radius: var(--radius-sm);
                    font-size: 13px; font-family: var(--font-body);
                    background: var(--color-surface-container-high, #e4e2dc); color: var(--ink);
                    transition: all 150ms ease;
                }
                .field textarea {
                    font-family: var(--font-mono);
                    min-height: 100px;
                    resize: vertical;
                }
                .field input:focus, .field select:focus, .field textarea:focus {
                    outline: none; box-shadow: var(--shadow-sm); background: var(--color-surface-container-lowest, #fff);
                }
                .form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
                .toggle-row { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
                .toggle-row input[type="checkbox"] { width: 18px; height: 18px; accent-color: var(--teal); }
                .toggle-row label { font-size: 12px; font-weight: 700; color: var(--ink); }
                .btn-send {
                    width: 100%; padding: 14px 0; border: 0; border-radius: 0;
                    background: var(--navy); color: #f8fafc; font-size: 12px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; cursor: pointer;
                    margin-top: 12px; transition: background 200ms ease;
                }
                .btn-send:hover { background: var(--navy-2); }
                .btn-send:disabled { opacity: 0.5; cursor: not-allowed; }
                .response-meta {
                    font-size: 11px; color: var(--muted); margin-bottom: 16px;
                    padding-bottom: 16px; border-bottom: 2px solid var(--ink);
                    font-weight: 600;
                }
                .response-area {
                    font-family: var(--font-mono);
                    font-size: 12px;
                    white-space: pre-wrap;
                    word-break: break-word;
                    line-height: 1.7;
                    color: var(--ink);
                }
                .response-area.error { color: var(--red); }
                .response-area.streaming { color: var(--teal); }
                .status-badge {
                    padding: 4px 12px;
                    border-radius: 0;
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.06em;
                }
                .tip-card {
                    background: rgba(15, 118, 110, 0.06);
                    border-radius: var(--radius-sm);
                    padding: 16px 20px;
                    font-size: 12px;
                    line-height: 1.6;
                    color: var(--ink);
                }
                .tip-card strong { color: var(--teal); }
                @media (max-width: 1024px) { .playground-grid { grid-template-columns: 1fr; } }
            </style>
            <div class="panel-layout">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="tip-card">
                    <strong>Tip:</strong> The Playground sends requests through the AI Gateway relay.
                    Choose any protocol and model &mdash; the gateway handles protocol transcoding automatically.
                    For example, send an OpenAI Chat request for a Claude model and the gateway will convert it to Anthropic Messages format upstream.
                </div>
                <div class="playground-grid">
                    <div class="pane">
                        <div class="pane-header">
                            <h3 class="pane-title">Request</h3>
                        </div>
                        <div class="pane-body">
                            <div class="field">
                                <label>Protocol</label>
                                <select data-ref="protocol">
                                    <option value="chat">OpenAI Chat Completions</option>
                                    <option value="responses">OpenAI Responses API</option>
                                    <option value="messages">Anthropic Messages</option>
                                </select>
                            </div>
                            <div class="form-row">
                                <div class="field">
                                    <label>Model</label>
                                    <select data-ref="model">${DEMO_MODELS.map(m => `<option>${m}</option>`).join('')}</select>
                                </div>
                                <div class="field">
                                    <label>Max Tokens</label>
                                    <input type="number" data-ref="maxTokens" value="256">
                                </div>
                            </div>
                            <div class="field">
                                <label>Temperature</label>
                                <input type="number" data-ref="temperature" step="0.1" value="0.7" min="0" max="2">
                            </div>
                            <div class="field">
                                <label>System Instruction</label>
                                <input data-ref="systemMsg" placeholder="You are a helpful assistant." value="You are a helpful assistant.">
                            </div>
                            <div class="field">
                                <label>User Message</label>
                                <textarea data-ref="userMsg" placeholder="Enter your message...">Hello! What is the capital of France?</textarea>
                            </div>
                            <div class="toggle-row">
                                <input type="checkbox" data-ref="streamToggle" id="streamToggle">
                                <label for="streamToggle">Stream response (SSE)</label>
                            </div>
                            <button class="btn-send" data-ref="sendBtn">Send Request</button>
                        </div>
                    </div>
                    <div class="pane">
                        <div class="pane-header">
                            <h3 class="pane-title">Response</h3>
                            <span class="status-badge" data-ref="statusBadge" style="display:none;"></span>
                        </div>
                        <div class="pane-body">
                            <div class="response-meta" data-ref="responseMeta" style="display:none;"></div>
                            <div class="response-area" data-ref="responseArea">Send a request to see the response here.</div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.hero.render({ label: 'API Testing', title: 'Playground', metaHtml: '' });
        this.refs.sendBtn.addEventListener('click', () => this._sendRequest());
    }

    async _sendRequest() {
        const protocol = this.refs.protocol.value;
        const model = this.refs.model.value;
        const maxTokens = parseInt(this.refs.maxTokens.value) || 256;
        const temperature = parseFloat(this.refs.temperature.value) || 0.7;
        const system = this.refs.systemMsg.value.trim();
        const user = this.refs.userMsg.value.trim();
        const stream = this.refs.streamToggle.checked;

        if (!user) { alert('Please enter a message.'); return; }

        this.refs.sendBtn.disabled = true;
        this.refs.sendBtn.textContent = stream ? 'Streaming...' : 'Sending...';
        this.refs.responseArea.textContent = '';
        this.refs.responseArea.className = 'response-area';
        this.refs.responseMeta.style.display = 'none';
        this.refs.statusBadge.style.display = 'none';

        let url, body;
        if (protocol === 'chat') {
            url = `${API.airelay}/v1/chat/completions`;
            const messages = [];
            if (system) messages.push({ role: 'system', content: system });
            messages.push({ role: 'user', content: user });
            body = { model, messages, max_tokens: maxTokens, temperature, stream };
        } else if (protocol === 'responses') {
            url = `${API.airelay}/v1/responses`;
            const input = [];
            if (system) input.push({ role: 'user', content: [{ type: 'input_text', text: `[System] ${system}\n\n${user}` }] });
            else input.push({ role: 'user', content: [{ type: 'input_text', text: user }] });
            body = { model, input, max_output_tokens: maxTokens, temperature, store: false, stream };
        } else {
            url = `${API.airelay}/v1/messages`;
            const messages = [];
            messages.push({ role: 'user', content: system ? `[System] ${system}\n\n${user}` : user });
            body = { model, messages, max_tokens: maxTokens, temperature, stream };
        }

        const startTime = Date.now();

        try {
            const resp = await fetchWithAuth(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });

            this.refs.statusBadge.style.display = 'inline-flex';
            this.refs.statusBadge.textContent = `${resp.status} ${resp.ok ? 'OK' : 'Error'}`;
            this.refs.statusBadge.style.background = resp.ok ? 'var(--green-soft)' : 'var(--red-soft)';
            this.refs.statusBadge.style.color = resp.ok ? 'var(--green)' : 'var(--red)';

            if (!resp.ok) {
                const err = await resp.text();
                this.refs.responseArea.className = 'response-area error';
                this.refs.responseArea.textContent = err;
                return;
            }

            if (stream) {
                this.refs.responseArea.className = 'response-area streaming';
                const reader = resp.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';
                let fullText = '';
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop() || '';
                    for (const line of lines) {
                        if (!line.startsWith('data: ')) continue;
                        const data = line.slice(6).trim();
                        if (data === '[DONE]') continue;
                        try {
                            const obj = JSON.parse(data);
                            let delta = '';
                            if (protocol === 'chat') delta = obj.choices?.[0]?.delta?.content || '';
                            else if (protocol === 'responses') delta = obj.delta || '';
                            else delta = obj.delta?.text || '';
                            if (delta) { fullText += delta; this.refs.responseArea.textContent = fullText; }
                        } catch {}
                    }
                }
                const elapsed = Date.now() - startTime;
                this.refs.responseMeta.style.display = 'block';
                this.refs.responseMeta.textContent = `Streamed in ${elapsed}ms`;
                this.refs.responseArea.className = 'response-area';
            } else {
                const outerData = await resp.json();
                const innerJson = outerData.response ? JSON.parse(outerData.response) : outerData;
                const elapsed = Date.now() - startTime;
                this.refs.responseArea.className = 'response-area';
                this.refs.responseArea.textContent = JSON.stringify(innerJson, null, 2);

                let tokens = 0;
                if (innerJson.usage?.total_tokens) tokens = innerJson.usage.total_tokens;
                else if (innerJson.usage?.input_tokens && innerJson.usage?.output_tokens) tokens = innerJson.usage.input_tokens + innerJson.usage.output_tokens;
                this.refs.responseMeta.style.display = 'block';
                this.refs.responseMeta.textContent = `${elapsed}ms · ${tokens} tokens · upstream: ${resp.headers.get('X-Upstream-Protocol') || 'n/a'}`;
            }
        } catch (e) {
            this.refs.responseArea.className = 'response-area error';
            this.refs.responseArea.textContent = e.message;
            this.refs.statusBadge.style.display = 'inline-flex';
            this.refs.statusBadge.textContent = 'Error';
            this.refs.statusBadge.style.background = 'var(--red-soft)';
            this.refs.statusBadge.style.color = 'var(--red)';
        } finally {
            this.refs.sendBtn.disabled = false;
            this.refs.sendBtn.textContent = 'Send Request';
        }
    }

    refresh() {}
}

customElements.define('ai-panel-playground', PanelPlayground);
