import { KeelElement } from './base/KeelElement.js';
import { login, register } from '../api.js';
import { oauthStub } from '../auth.js';

export class PanelLogin extends KeelElement {
    hostStyles() { return 'display:flex; align-items:center; justify-content:center; min-height:100%;'; }

    template() {
        return `
            <style>
                .card {
                    width: min(420px, 92vw);
                    border: 2px solid var(--ink);
                    box-shadow: var(--shadow-lg);
                    background: var(--paper);
                }
                .card-head {
                    padding: 18px 22px;
                    background: var(--ink);
                    color: var(--paper);
                    font-family: var(--font-display);
                    font-size: 20px;
                    text-transform: uppercase;
                    letter-spacing: -0.04em;
                }
                .card-body { padding: 22px; display: grid; gap: 14px; }
                .field label {
                    display: block; margin-bottom: 6px;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted);
                }
                .field input {
                    width: 100%; padding: 10px 12px;
                    border: 2px solid var(--ink); background: var(--paper);
                    font-family: var(--font-mono); font-size: 13px; color: var(--ink);
                }
                .field input:focus { outline: 2px solid var(--ink); outline-offset: 2px; }
                .btn {
                    width: 100%; padding: 13px 0;
                    border: 2px solid var(--ink); background: var(--ink); color: var(--paper);
                    font-family: var(--font-mono); font-size: 11px; font-weight: 800;
                    text-transform: uppercase; letter-spacing: 0.14em; cursor: pointer;
                }
                .btn:hover { background: var(--red); border-color: var(--red); }
                .btn:disabled { opacity: 0.6; cursor: not-allowed; }
                .btn-ghost {
                    width: 100%; padding: 10px 0;
                    border: 2px solid var(--ink); background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800;
                    text-transform: uppercase; letter-spacing: 0.12em; cursor: pointer;
                }
                .btn-ghost:hover { background: var(--ink); color: var(--paper); }
                .error { display:none; padding:10px 14px; background:var(--red); color:var(--paper); font-family:var(--font-mono); font-size:11px; font-weight:800; }
                .error.show { display:block; }
                .oauth { display:grid; gap:8px; margin-top:4px; padding-top:16px; border-top:1px solid var(--ink); }
                .oauth .label {
                    text-align:center; font-family:var(--font-mono); font-size:9px; font-weight:800;
                    text-transform:uppercase; letter-spacing:0.12em; color:var(--muted);
                }
                .oauth-row { display:flex; gap:8px; }
                .oauth-btn {
                    flex:1; padding:9px 0; border:2px solid var(--ink); background:var(--paper);
                    font-family:var(--font-mono); font-size:9px; font-weight:800;
                    text-transform:uppercase; letter-spacing:0.08em; cursor:pointer;
                }
                .oauth-btn:hover { background:var(--ink); color:var(--paper); }
                .toggle {
                    text-align:center; font-family:var(--font-mono); font-size:10px; color:var(--muted);
                }
                .toggle a { color:var(--ink); font-weight:800; cursor:pointer; text-decoration:underline; }
            </style>
            <div class="card">
                <div class="card-head" data-ref="title">Sign In</div>
                <div class="error" data-ref="error"></div>
                <div class="card-body">
                    <div class="field"><label>Email</label><input data-ref="email" type="email" placeholder="you@example.com" autocomplete="email" /></div>
                    <div class="field"><label>Password</label><input data-ref="password" type="password" autocomplete="current-password" /></div>
                    <div class="field" data-ref="nameField" style="display:none;"><label>Display Name</label><input data-ref="displayName" type="text" placeholder="Your name" /></div>
                    <button class="btn" data-ref="submitBtn">Sign In</button>
                    <div class="oauth">
                        <div class="label">Or continue with (coming soon)</div>
                        <div class="oauth-row">
                            <button class="oauth-btn" data-ref="oauthApple">Apple</button>
                            <button class="oauth-btn" data-ref="oauthGoogle">Google</button>
                            <button class="oauth-btn" data-ref="oauthGithub">GitHub</button>
                        </div>
                    </div>
                    <div class="toggle">
                        <span data-ref="toggleText">Don't have an account?</span>
                        <a data-ref="toggleLink" href="#">Sign Up</a>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this._mode = 'login'; // 'login' | 'signup'
        this.refs.submitBtn.addEventListener('click', () => this._submit());
        this.refs.toggleLink.addEventListener('click', (e) => { e.preventDefault(); this._toggleMode(); });
        this.refs.oauthApple.addEventListener('click', () => this._oauthStub('apple'));
        this.refs.oauthGoogle.addEventListener('click', () => this._oauthStub('google'));
        this.refs.oauthGithub.addEventListener('click', () => this._oauthStub('github'));
        this.refs.password.addEventListener('keydown', (e) => { if (e.key === 'Enter') this._submit() });
    }

    _toggleMode() {
        this._mode = this._mode === 'login' ? 'signup' : 'login';
        this.refs.title.textContent = this._mode === 'login' ? 'Sign In' : 'Sign Up';
        this.refs.submitBtn.textContent = this._mode === 'login' ? 'Sign In' : 'Create Account';
        this.refs.nameField.style.display = this._mode === 'login' ? 'none' : '';
        this.refs.toggleText.textContent = this._mode === 'login' ? "Don't have an account?" : "Already have an account?";
        this.refs.toggleLink.textContent = this._mode === 'login' ? 'Sign Up' : 'Sign In';
    }

    async _submit() {
        const email = this.refs.email.value.trim();
        const password = this.refs.password.value;
        if (!email || !password) { this._error('Email and password are required.'); return; }
        this.refs.submitBtn.disabled = true;
        this.refs.submitBtn.textContent = 'Please wait…';
        try {
            if (this._mode === 'login') {
                await login(email, password);
            } else {
                const displayName = this.refs.displayName.value.trim();
                if (!displayName) { this._error('Display name is required.'); return; }
                await register(email, password, displayName);
            }
            window.dispatchEvent(new Event('auth-change'));
        } catch (e) { this._error(e.message); }
        finally { this.refs.submitBtn.disabled = false; this.refs.submitBtn.textContent = this._mode === 'login' ? 'Sign In' : 'Create Account'; }
    }

    async _oauthStub(provider) {
        try {
            await oauthStub(provider);
            window.dispatchEvent(new Event('auth-change'));
        } catch (e) { this._error(e.message); }
    }

    _error(msg) { this.refs.error.textContent = msg; this.refs.error.classList.add('show'); }

    refresh() {}
}

customElements.define('customer-panel-login', PanelLogin);
