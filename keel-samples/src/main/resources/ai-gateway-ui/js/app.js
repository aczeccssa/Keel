import { KeelElement } from './components/base/KeelElement.js';
import { state, setTab, hydrateHash } from './state.js';
import { loadStoredAuth, login, register, logout } from './auth.js';
import { TABS } from './config.js';

import './components/shared/KeelStatGrid.js';
import './components/shared/KeelDataTable.js';
import './components/shared/KeelHero.js';
import './components/shared/KeelDetailList.js';

import './components/PanelDashboard.js';
import './components/PanelProviders.js';
import './components/PanelKeys.js';
import './components/PanelPools.js';
import './components/PanelRateLimits.js';
import './components/PanelUsers.js';
import './components/PanelPlayground.js';

const ICONS = {
    dashboard: '<path d="M3 3v18h18"/><path d="m7 14 3-3 3 2 4-6"/>',
    key: '<path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>',
    dns: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
    hub: '<circle cx="12" cy="12" r="3"/><circle cx="5" cy="5" r="2"/><circle cx="19" cy="5" r="2"/><circle cx="5" cy="19" r="2"/><circle cx="19" cy="19" r="2"/><line x1="7" y1="7" x2="10" y2="10"/><line x1="17" y1="7" x2="14" y2="10"/><line x1="7" y1="17" x2="10" y2="14"/><line x1="17" y1="17" x2="14" y2="14"/>',
    speed: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    group: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    terminal: '<polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/>',
};

class AiProxyApp extends KeelElement {
    hostStyles() { return 'display:block;height:100vh;'; }

    template() {
        return `
            <style>
                :host {
                    --font-display: "Newsreader", Georgia, "Times New Roman", serif;
                    --font-headline: "Newsreader", Georgia, "Times New Roman", serif;
                    --font-body: "Quicksand", system-ui, -apple-system, sans-serif;
                    --font-mono: ui-monospace, "SFMono-Regular", Menlo, Monaco, Consolas, monospace;
                }
                .app-shell {
                    display: grid;
                    grid-template-columns: 260px minmax(0, 1fr);
                    height: 100vh;
                }
                .sidebar {
                    height: 100vh;
                    padding: 28px 18px;
                    background: rgba(247, 244, 237, 0.86);
                    backdrop-filter: blur(18px);
                    border-right: 1px solid var(--line);
                    display: flex;
                    flex-direction: column;
                    gap: 24px;
                    overflow-y: auto;
                }
                .brand { padding: 6px 10px; }
                .brand h1 {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 28px;
                    font-style: italic;
                    font-weight: 700;
                    letter-spacing: -0.03em;
                }
                .brand p {
                    margin: 8px 0 0;
                    color: var(--muted);
                    font-size: 11px;
                    font-weight: 700;
                    letter-spacing: 0.16em;
                    text-transform: uppercase;
                }
                .nav-list { display: grid; gap: 6px; }
                .nav-link {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                    padding: 12px 14px;
                    border-radius: 999px;
                    color: #475467;
                    cursor: pointer;
                    transition: all 250ms var(--ease-smooth);
                    text-decoration: none;
                }
                .nav-link:hover {
                    transform: translateX(4px);
                    background: rgba(255, 255, 255, 0.7);
                }
                .nav-link.is-active {
                    background: var(--panel-strong);
                    color: var(--navy);
                    box-shadow: 0 10px 28px rgba(15, 23, 42, 0.08);
                }
                .nav-icon {
                    width: 30px;
                    height: 30px;
                    border-radius: 10px;
                    background: rgba(15, 23, 42, 0.06);
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    flex-shrink: 0;
                }
                .nav-icon svg { width: 16px; height: 16px; }
                .nav-link.is-active .nav-icon {
                    background: rgba(15, 118, 110, 0.12);
                    color: var(--teal);
                }
                .nav-copy { min-width: 0; flex: 1; }
                .nav-copy strong {
                    display: block;
                    font-size: 12px;
                    font-weight: 800;
                    letter-spacing: 0.08em;
                    text-transform: uppercase;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .nav-copy span {
                    display: block;
                    margin-top: 2px;
                    color: var(--muted);
                    font-size: 11px;
                }
                .sidebar-footer {
                    margin-top: auto;
                    display: grid;
                    gap: 10px;
                }
                .user-pill {
                    display: flex;
                    align-items: center;
                    gap: 10px;
                    padding: 10px 14px;
                    border-radius: 999px;
                    background: var(--panel-strong);
                    border: 1px solid rgba(17, 24, 39, 0.06);
                    font-size: 11px;
                    font-weight: 700;
                    color: var(--ink);
                }
                .user-dot {
                    width: 8px;
                    height: 8px;
                    border-radius: 999px;
                    background: var(--green);
                }
                .logout-btn {
                    width: 100%;
                    border: 0;
                    padding: 12px 18px;
                    border-radius: 999px;
                    background: var(--navy);
                    color: #f8fafc;
                    cursor: pointer;
                    font-size: 11px;
                    font-weight: 800;
                    letter-spacing: 0.18em;
                    text-transform: uppercase;
                    transition: background 200ms ease;
                }
                .logout-btn:hover { background: var(--navy-2); }
                .main-shell {
                    min-width: 0;
                    display: flex;
                    flex-direction: column;
                    height: 100vh;
                    overflow: hidden;
                }
                .topbar {
                    flex-shrink: 0;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    padding: 22px 28px 16px;
                    background: rgba(246, 243, 236, 0.82);
                    backdrop-filter: blur(18px);
                    border-bottom: 1px solid rgba(17, 24, 39, 0.04);
                    z-index: 10;
                }
                .topbar h2 {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: clamp(24px, 3vw, 32px);
                    line-height: 0.95;
                    letter-spacing: -0.04em;
                }
                .topbar-label {
                    color: var(--muted);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    margin-bottom: 6px;
                }
                .topbar-actions {
                    display: flex;
                    align-items: center;
                    gap: 10px;
                }
                .content {
                    padding: 28px 32px;
                    flex: 1 1 0;
                    min-height: 0;
                    overflow-y: auto;
                    overflow-x: hidden;
                }
                .panel { display: none; }
                .panel.is-active { display: block; }
                /* Tab-switch animation is driven by GSAP in _renderState; we keep a CSS fallback
                   for environments where GSAP didn't load (e.g. offline dev). */
                @keyframes panel-enter {
                    from { opacity: 0; transform: translateY(6px); }
                    to { opacity: 1; transform: translateY(0); }
                }
                .panel.fallback-enter { animation: panel-enter 220ms var(--ease-smooth); }
                /* Login overlay */
                .login-overlay {
                    position: fixed;
                    inset: 0;
                    background: var(--bg);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    z-index: 1000;
                }
                .login-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-xl);
                    padding: 48px;
                    width: 420px;
                    box-shadow: var(--shadow-lg);
                    border: 1px solid rgba(17, 24, 39, 0.06);
                }
                .login-card h2 {
                    margin: 0 0 6px;
                    font-family: var(--font-headline);
                    font-size: 32px;
                    font-style: italic;
                    font-weight: 700;
                    letter-spacing: -0.03em;
                }
                .login-card p {
                    margin: 0 0 32px;
                    color: var(--muted);
                    font-size: 13px;
                }
                .login-tabs {
                    display: flex;
                    gap: 0;
                    margin-bottom: 28px;
                    border-bottom: 1px solid var(--line);
                }
                .login-tab {
                    flex: 1;
                    padding: 10px 0;
                    text-align: center;
                    font-size: 11px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                    cursor: pointer;
                    color: var(--muted);
                    border-bottom: 2px solid transparent;
                    transition: all 150ms ease;
                }
                .login-tab.active {
                    color: var(--ink);
                    border-bottom-color: var(--teal);
                }
                .field { margin-bottom: 18px; }
                .field label {
                    display: block;
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                    color: var(--muted);
                    margin-bottom: 8px;
                }
                .field input {
                    width: 100%;
                    padding: 12px 14px;
                    border: 0;
                    border-radius: var(--radius-sm);
                    font-size: 14px;
                    background: var(--color-surface-container-high, #e4e2dc);
                    color: var(--ink);
                    transition: all 150ms ease;
                }
                .field input:focus {
                    outline: none;
                    box-shadow: 0 2px 0 0 var(--teal);
                    background: var(--color-surface-container-lowest, #fff);
                }
                .login-btn {
                    width: 100%;
                    padding: 14px 0;
                    background: var(--navy);
                    color: #f8fafc;
                    border: 0;
                    border-radius: 999px;
                    font-size: 12px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    cursor: pointer;
                    margin-top: 12px;
                    transition: background 200ms ease;
                }
                .login-btn:hover { background: var(--navy-2); }
                .login-error {
                    color: var(--red);
                    font-size: 12px;
                    margin-top: 10px;
                    display: none;
                    font-weight: 600;
                }
                .register-name { display: none; }
                .onboarding {
                    margin-top: 24px;
                    padding: 16px 18px;
                    background: rgba(15, 118, 110, 0.06);
                    border-radius: var(--radius-sm);
                    font-size: 12px;
                    line-height: 1.6;
                    color: var(--ink);
                }
                .onboarding code {
                    background: rgba(15, 23, 42, 0.08);
                    padding: 1px 6px;
                    border-radius: 4px;
                    font-family: var(--font-mono);
                    font-size: 11px;
                }
                @media (max-width: 980px) {
                    .app-shell { grid-template-columns: 1fr; }
                    .sidebar { height: auto; padding: 18px; border-right: 0; border-bottom: 1px solid var(--line); }
                }
            </style>
            <div class="login-overlay" data-ref="loginOverlay">
                <div class="login-card">
                    <h2>AI Proxy</h2>
                    <p>Sign in to manage your AI Gateway platform</p>
                    <div class="login-tabs">
                        <div class="login-tab active" data-ref="tabLogin">Sign In</div>
                        <div class="login-tab" data-ref="tabRegister">Register</div>
                    </div>
                    <div class="field register-name" data-ref="nameField">
                        <label>Display Name</label>
                        <input type="text" data-ref="inputName" placeholder="Your name">
                    </div>
                    <div class="field">
                        <label>Email</label>
                        <input type="email" data-ref="inputEmail" placeholder="admin@example.com" value="admin@example.com">
                    </div>
                    <div class="field">
                        <label>Password</label>
                        <input type="password" data-ref="inputPassword" placeholder="Password" value="admin123">
                    </div>
                    <div class="login-error" data-ref="loginError"></div>
                    <button class="login-btn" data-ref="loginBtn">Sign In</button>
                    <div class="onboarding">
                        <strong>Quick Start:</strong> Use <code>admin@example.com</code> / <code>admin123</code> to sign in.
                        Create API keys in the Keys panel, then test in Playground.
                    </div>
                </div>
            </div>
            <div class="app-shell" data-ref="appShell" hidden>
                <aside class="sidebar">
                    <div class="brand">
                        <h1>AI Proxy</h1>
                        <p>Keel Gateway Platform</p>
                    </div>
                    <nav class="nav-list" data-ref="nav"></nav>
                    <div class="sidebar-footer">
                        <div class="user-pill" data-ref="userPill">
                            <span class="user-dot"></span>
                            <span data-ref="userEmail">Signed in</span>
                        </div>
                        <button class="logout-btn" data-ref="logoutBtn">Sign Out</button>
                    </div>
                </aside>
                <div class="main-shell">
                    <header class="topbar">
                        <div>
                            <div class="topbar-label">Keel AI Gateway</div>
                            <h2 data-ref="pageTitle">Dashboard</h2>
                        </div>
                        <div class="topbar-actions">
                            <button class="logout-btn" data-ref="refreshBtn" style="width:auto;padding:8px 18px;font-size:10px;">Refresh</button>
                        </div>
                    </header>
                    <main class="content" data-ref="content">
                        <ai-panel-dashboard class="panel" data-ref="panelDashboard"></ai-panel-dashboard>
                        <ai-panel-providers class="panel" data-ref="panelProviders"></ai-panel-providers>
                        <ai-panel-keys class="panel" data-ref="panelKeys"></ai-panel-keys>
                        <ai-panel-pools class="panel" data-ref="panelPools"></ai-panel-pools>
                        <ai-panel-rate-limits class="panel" data-ref="panelRateLimits"></ai-panel-rate-limits>
                        <ai-panel-users class="panel" data-ref="panelUsers"></ai-panel-users>
                        <ai-panel-playground class="panel" data-ref="panelPlayground"></ai-panel-playground>
                    </main>
                </div>
            </div>
        `;
    }

    afterMount() {
        loadStoredAuth();
        hydrateHash();
        this._setupLogin();
        this._setupNav();
        this._renderState();
        this.refs.refreshBtn.addEventListener('click', () => this._refreshActive());
        window.addEventListener('hashchange', () => { hydrateHash(); this._renderState(); });
        this._reveal();
    }

    /**
     * First-paint reveal. Staggers the brand mark, the nav, and the user pill into view.
     * No-op when GSAP is unavailable so the UI still renders in offline / blocked-CDN
     * environments.
     */
    _reveal() {
        const gsap = window.gsap;
        if (!gsap) return;
        const targets = [
            this.shadowRoot.querySelector('.brand h1'),
            ...this.shadowRoot.querySelectorAll('.nav-link'),
            this.shadowRoot.querySelector('.user-pill'),
        ].filter(Boolean);
        if (targets.length === 0) return;
        gsap.fromTo(
            targets,
            { autoAlpha: 0, y: 8 },
            { autoAlpha: 1, y: 0, duration: 0.45, ease: 'power2.out', stagger: 0.04 }
        );
    }

    _setupLogin() {
        let isRegister = false;
        this.refs.tabLogin.addEventListener('click', () => {
            isRegister = false;
            this.refs.tabLogin.classList.add('active');
            this.refs.tabRegister.classList.remove('active');
            this.refs.nameField.style.display = 'none';
            this.refs.loginBtn.textContent = 'Sign In';
        });
        this.refs.tabRegister.addEventListener('click', () => {
            isRegister = true;
            this.refs.tabRegister.classList.add('active');
            this.refs.tabLogin.classList.remove('active');
            this.refs.nameField.style.display = 'block';
            this.refs.loginBtn.textContent = 'Register';
        });
        this.refs.loginBtn.addEventListener('click', async () => {
            const email = this.refs.inputEmail.value.trim();
            const password = this.refs.inputPassword.value;
            const name = this.refs.inputName.value.trim();
            this.refs.loginError.style.display = 'none';
            try {
                if (isRegister) {
                    await register(email, password, name || email.split('@')[0]);
                } else {
                    await login(email, password);
                }
                this._renderState();
            } catch (e) {
                this.refs.loginError.textContent = e.message;
                this.refs.loginError.style.display = 'block';
            }
        });
        this.refs.inputPassword.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') this.refs.loginBtn.click();
        });
        this.refs.logoutBtn.addEventListener('click', () => { logout(); this._renderState(); });
    }

    _setupNav() {
        this.refs.nav.addEventListener('click', (e) => {
            const link = e.target.closest('[data-tab-id]');
            if (!link) return;
            setTab(link.dataset.tabId);
        });
    }

    _renderState() {
        const isLoggedIn = state.loggedIn;
        this.refs.loginOverlay.style.display = isLoggedIn ? 'none' : 'flex';
        this.refs.appShell.hidden = !isLoggedIn;
        if (!isLoggedIn) return;

        this.refs.userEmail.textContent = state.user?.email || 'Signed in';
        this.refs.nav.innerHTML = TABS.map(tab => `
            <a class="nav-link ${state.activeTab === tab.id ? 'is-active' : ''}" data-tab-id="${tab.id}" href="#${tab.id}">
                <span class="nav-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        ${ICONS[tab.icon] || ''}
                    </svg>
                </span>
                <span class="nav-copy">
                    <strong>${tab.label}</strong>
                    <span>${tab.hint || ''}</span>
                </span>
            </a>
        `).join('');

        const tab = TABS.find(t => t.id === state.activeTab);
        this.refs.pageTitle.textContent = tab?.label || 'Dashboard';

        const panels = {
            dashboard: this.refs.panelDashboard,
            providers: this.refs.panelProviders,
            keys: this.refs.panelKeys,
            pools: this.refs.panelPools,
            ratelimits: this.refs.panelRateLimits,
            users: this.refs.panelUsers,
            playground: this.refs.panelPlayground,
        };
        const previousActive = Object.entries(panels).find(([, el]) => el.classList.contains('is-active'))?.[0];
        Object.entries(panels).forEach(([id, el]) => {
            el.classList.toggle('is-active', id === state.activeTab);
            if (id === state.activeTab && typeof el.refresh === 'function') {
                el.refresh();
            }
        });
        this._animateTabSwitch(panels[state.activeTab], previousActive);
    }

    /**
     * GSAP-driven cross-fade between the previously active panel and the newly selected
     * one. Falls back to a CSS @keyframes animation if GSAP failed to load (e.g. CDN
     * blocked, offline dev). We deliberately do NOT add scroll-driven effects here —
     * this is a workspace app, not a marketing page.
     */
    _animateTabSwitch(activeEl, previousId) {
        if (!activeEl) return;
        const gsap = window.gsap;
        if (!gsap) {
            activeEl.classList.add('fallback-enter');
            setTimeout(() => activeEl.classList.remove('fallback-enter'), 260);
            return;
        }
        // Subtle reveal: tiny upward float + opacity. Stays well under 300ms so panel
        // refreshes aren't blocked visually.
        gsap.fromTo(
            activeEl,
            { autoAlpha: 0, y: 10 },
            { autoAlpha: 1, y: 0, duration: 0.32, ease: 'power2.out', clearProps: 'transform' }
        );
        // Animate the page title in the topbar so the user gets immediate feedback that
        // the tab actually changed.
        if (this.refs.pageTitle) {
            gsap.fromTo(
                this.refs.pageTitle,
                { autoAlpha: 0, y: 4 },
                { autoAlpha: 1, y: 0, duration: 0.25, ease: 'power2.out' }
            );
        }
    }

    _refreshActive() {
        const panels = {
            dashboard: this.refs.panelDashboard,
            providers: this.refs.panelProviders,
            keys: this.refs.panelKeys,
            pools: this.refs.panelPools,
            ratelimits: this.refs.panelRateLimits,
            users: this.refs.panelUsers,
            playground: this.refs.panelPlayground,
        };
        const active = panels[state.activeTab];
        if (active && typeof active.refresh === 'function') active.refresh();
    }
}

customElements.define('ai-proxy-app', AiProxyApp);
