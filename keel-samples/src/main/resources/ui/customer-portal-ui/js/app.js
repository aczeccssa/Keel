import { KeelElement } from './components/base/KeelElement.js';
import { state, setTab, hydrateHash } from './state.js';
import { loadStoredAuth, login, register, logout } from './api.js';
import { TABS, ICONS } from './config.js';
import './components/PanelDashboard.js';
import './components/PanelKeys.js';
import './components/PanelBilling.js';
import './components/PanelPricing.js';

class CustomerApp extends KeelElement {
    hostStyles() { return 'display:block;height:100vh;'; }

    template() {
        return `
            <style>
                :host {
                    --font-headline: "Archivo Black", "Helvetica Neue", Arial, sans-serif;
                    --font-body: "Inter", "Helvetica Neue", Arial, sans-serif;
                    --font-mono: "JetBrains Mono", ui-monospace, monospace;
                    --font-display: "Archivo Black", "Helvetica Neue", Arial, sans-serif;
                }
                .app-shell {
                    display: grid;
                    grid-template-columns: 318px minmax(0, 1fr);
                    height: 100vh;
                    background: var(--surface-accent);
                    transition: grid-template-columns 200ms var(--ease-smooth);
                }
                .app-shell.collapsed { grid-template-columns: 64px minmax(0, 1fr); }
                .sidebar {
                    height: 100vh;
                    padding: 22px 16px;
                    background: var(--surface-soft);
                    border-right: 2px solid var(--ink);
                    display: flex;
                    flex-direction: column;
                    gap: 18px;
                    overflow-y: auto;
                    overflow-x: hidden;
                    transition: padding 200ms;
                }
                .collapsed .sidebar { padding: 22px 12px 12px; gap: 14px; }
                .brand {
                    padding: 4px 8px;
                    display: flex; align-items: flex-start; justify-content: space-between;
                    gap: 10px;
                    cursor: pointer;
                    user-select: none;
                    transition: background 120ms;
                }
                .brand:hover { background: var(--surface-muted); }
                .collapsed .brand { padding: 0; justify-content: center; }
                .collapsed .brand:hover { background: transparent; }
                .brand-copy { min-width: 0; flex: 1; }
                .collapsed .brand-copy { display: none; }
                .brand h1 {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 26px;
                    line-height: 0.92;
                    letter-spacing: -0.05em;
                    text-transform: uppercase;
                }
                .brand p {
                    margin: 6px 0 0;
                    color: var(--muted);
                    font-family: var(--font-mono);
                    font-size: 9px;
                    font-weight: 800;
                    letter-spacing: 0.16em;
                    text-transform: uppercase;
                }
                .sidebar-toggle {
                    width: 30px; height: 30px;
                    border: 2px solid var(--ink);
                    background: var(--surface-soft);
                    color: var(--ink);
                    cursor: pointer;
                    display: inline-flex; align-items: center; justify-content: center;
                    flex-shrink: 0;
                    transition: background 120ms, color 120ms, transform 200ms;
                }
                .sidebar-toggle:hover { background: var(--surface-accent); color: var(--on-accent); }
                .sidebar-toggle svg { width: 13px; height: 13px; stroke: currentColor; fill: none; stroke-width: 2.5; }
                .collapsed .sidebar-toggle { transform: rotate(180deg); }
                .nav-section-label {
                    padding: 10px 10px 4px;
                    font-family: var(--font-mono);
                    font-size: 9px; font-weight: 800;
                    letter-spacing: 0.2em; text-transform: uppercase;
                    color: var(--muted);
                }
                .collapsed .nav-section-label { font-size: 0; height: 8px; padding: 4px 0; overflow: hidden; }
                .nav-list { display: grid; gap: 4px; }
                .nav-link {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                    padding: 10px 12px;
                    color: var(--ink);
                    cursor: pointer;
                    text-decoration: none;
                    border: 2px solid transparent;
                    background: transparent;
                    transition: background 150ms, border-color 150ms, color 150ms;
                    position: relative;
                }
                .nav-link:hover { background: var(--surface-muted); }
                .nav-link.is-active {
                    background: var(--surface-accent);
                    color: var(--on-accent);
                    box-shadow: 4px 4px 0 0 var(--teal);
                }
                .nav-icon {
                    width: 30px; height: 30px;
                    border: 2px solid var(--ink);
                    background: var(--surface-soft);
                    color: var(--ink);
                    display: inline-flex; align-items: center; justify-content: center;
                    flex-shrink: 0;
                    transition: border-color 120ms, color 120ms, background 120ms;
                }
                .nav-icon svg { width: 15px; height: 15px; }
                .nav-link.is-active .nav-icon { background: var(--surface-strong); border-color: var(--ink); color: var(--ink); }
                .nav-copy { min-width: 0; flex: 1; overflow: hidden; }
                .nav-copy strong {
                    display: block;
                    font-family: var(--font-mono);
                    font-size: 12px; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase;
                    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
                }
                .nav-copy span {
                    display: block; margin-top: 3px;
                    font-family: var(--font-mono);
                    font-size: 9.5px; letter-spacing: 0.04em; opacity: 0.7; text-transform: uppercase;
                    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
                }
                .collapsed .nav-link { width: 40px; height: 40px; padding: 0; justify-content: center; gap: 0; margin: 4px auto; }
                .collapsed .nav-copy { display: none; }
                .collapsed .nav-icon { width: 28px; height: 28px; }
                .collapsed .sidebar-toggle { width: 40px; height: 40px; padding: 0; border: 0; background: transparent; }
                .collapsed .sidebar-toggle:hover { background: var(--surface-muted); }
                .collapsed .sidebar-toggle svg { width: 18px; height: 18px; stroke-width: 2; }
                .sidebar-footer { margin-top: auto; display: grid; gap: 8px; }
                .user-pill {
                    display: flex; align-items: center; gap: 10px;
                    padding: 10px 12px;
                    background: var(--surface-muted);
                    border: 2px solid var(--ink);
                    font-family: var(--font-mono);
                    font-size: 11px; font-weight: 700; color: var(--ink);
                    text-transform: uppercase; letter-spacing: 0.05em;
                    cursor: pointer;
                    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
                    transition: background 120ms, color 120ms;
                }
                .user-pill:hover { background: var(--surface-accent); color: var(--on-accent); }
                .user-pill:hover .user-dot { background: var(--teal); }
                .user-dot { width: 9px; height: 9px; background: var(--green); flex-shrink: 0; transition: background 120ms; }
                .collapsed .user-pill { width: 40px; height: 40px; padding: 0; justify-content: center; margin: 4px auto; }
                .collapsed .user-pill .user-email { display: none; }
                .collapsed .user-pill .user-dot { width: 14px; height: 14px; }
                .logout-btn {
                    width: 100%; border: 2px solid var(--ink); padding: 10px 14px;
                    background: var(--surface-accent); color: var(--on-accent); cursor: pointer;
                    font-family: var(--font-mono); font-size: 11px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .logout-btn:hover { background: var(--teal); border-color: var(--teal); }
                .collapsed .logout-btn { width: 40px; height: 40px; padding: 0; margin: 4px auto; }
                .collapsed .logout-btn::after { content: "⏻"; font-size: 18px; display: block; text-align: center; }
                .collapsed .logout-btn span { display: none; }
                /* User popover */
                .user-popover {
                    position: fixed;
                    background: var(--surface-soft);
                    border: 2px solid var(--ink);
                    box-shadow: 6px 6px 0 0 var(--ink);
                    padding: 14px;
                    display: none;
                    z-index: 50;
                    min-width: 240px;
                }
                .user-popover.is-open { display: block; }
                .popover-section { margin-bottom: 12px; }
                .popover-section:last-of-type { margin-bottom: 0; }
                .popover-label {
                    font-family: var(--font-mono);
                    font-size: 9px; font-weight: 800;
                    letter-spacing: 0.16em; text-transform: uppercase;
                    color: var(--muted);
                    margin-bottom: 6px;
                }
                .popover-email {
                    font-family: var(--font-mono);
                    font-size: 11px; font-weight: 700;
                    word-break: break-all;
                }
                .theme-toggle {
                    display: grid;
                    grid-template-columns: 1fr 1fr 1fr;
                    gap: 0;
                    border: 2px solid var(--ink);
                }
                .theme-toggle button {
                    background: var(--surface-soft);
                    color: var(--ink);
                    border: 0;
                    border-right: 2px solid var(--ink);
                    padding: 8px 0;
                    font-family: var(--font-mono);
                    font-size: 10px; font-weight: 800;
                    text-transform: uppercase; letter-spacing: 0.1em;
                    cursor: pointer;
                    transition: background 120ms, color 120ms;
                }
                .theme-toggle button:last-child { border-right: 0; }
                .theme-toggle button:hover { background: var(--surface-muted); }
                .theme-toggle button.is-active { background: var(--surface-accent); color: var(--on-accent); }
                .main-shell { min-width: 0; display: flex; flex-direction: column; height: 100vh; overflow: hidden; background: var(--bg); }
                .topbar {
                    flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; gap: 0;
                    padding: 0; background: var(--surface-soft); border-bottom: 2px solid var(--ink); z-index: 10;
                }
                .topbar-sys {
                    align-self: stretch;
                    display: flex; align-items: center; gap: 10px;
                    padding: 14px 20px;
                    background: var(--surface-soft);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink);
                }
                .sys-cross { color: var(--teal); font-weight: 800; font-size: 18px; }
                .topbar-actions {
                    align-self: stretch;
                    display: flex; align-items: center; gap: 0;
                    background: var(--surface-soft);
                }
                .sys-stat {
                    align-self: stretch;
                    display: inline-flex; align-items: center; gap: 7px;
                    padding: 0 16px;
                    background: var(--surface-muted);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.12em; color: var(--muted);
                    border-left: 2px solid var(--ink);
                }
                .sys-dot { width: 8px; height: 8px; background: var(--green); }
                .refresh-btn {
                    align-self: stretch;
                    border: 0;
                    border-left: 2px solid var(--ink);
                    background: var(--surface-soft); color: var(--ink); cursor: pointer;
                    padding: 0 18px; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .refresh-btn:hover { background: var(--teal); color: var(--on-accent); }
                .content {
                    padding: 24px 26px 60px;
                    flex: 1 1 0;
                    min-height: 0;
                    overflow-y: auto;
                    overflow-x: hidden;
                    background-image:
                        repeating-linear-gradient(0deg, transparent 0, transparent 39px, rgba(11,11,11,0.035) 39px, rgba(11,11,11,0.035) 40px),
                        repeating-linear-gradient(90deg, transparent 0, transparent 39px, rgba(11,11,11,0.035) 39px, rgba(11,11,11,0.035) 40px);
                }
                [data-theme="dark"] .content {
                    background-image:
                        repeating-linear-gradient(0deg, transparent 0, transparent 39px, rgba(255,255,255,0.02) 39px, rgba(255,255,255,0.02) 40px),
                        repeating-linear-gradient(90deg, transparent 0, transparent 39px, rgba(255,255,255,0.02) 39px, rgba(255,255,255,0.02) 40px);
                }
                .panel { display: none; }
                .panel.is-active { display: block; }
                @keyframes panel-enter { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
                .panel.fallback-enter { animation: panel-enter 200ms cubic-bezier(0.2, 0, 0, 1); }

                /* Login overlay */
                .login-overlay {
                    position: fixed; inset: 0; background: var(--bg);
                    background-image:
                        repeating-linear-gradient(0deg, transparent, transparent 39px, rgba(11,11,11,0.04) 39px, rgba(11,11,11,0.04) 40px),
                        repeating-linear-gradient(90deg, transparent, transparent 39px, rgba(11,11,11,0.04) 39px, rgba(11,11,11,0.04) 40px);
                    display: flex; align-items: center; justify-content: center; z-index: 1000;
                }
                .login-card { background: var(--surface-soft); padding: 44px; width: 440px; border: 2px solid var(--ink); box-shadow: var(--shadow-lg); }
                .login-card h2 {
                    margin: 0 0 6px; font-family: var(--font-headline); font-size: 38px; line-height: 0.9;
                    letter-spacing: -0.04em; text-transform: uppercase;
                }
                .login-card > p { margin: 0 0 28px; color: var(--muted); font-family: var(--font-mono); font-size: 11px; letter-spacing: 0.06em; text-transform: uppercase; }
                .login-tabs { display: flex; gap: 0; margin-bottom: 26px; border: 2px solid var(--ink); }
                .login-tab {
                    flex: 1; padding: 11px 0; text-align: center; font-family: var(--font-mono);
                    font-size: 11px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em;
                    cursor: pointer; color: var(--ink); background: var(--surface-soft); transition: all 120ms ease;
                }
                .login-tab + .login-tab { border-left: 2px solid var(--ink); }
                .login-tab.active { color: var(--on-accent); background: var(--surface-accent); }
                .field { margin-bottom: 18px; }
                .field label { display: block; font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.14em; color: var(--ink); margin-bottom: 8px; }
                .field input {
                    width: 100%; padding: 12px 14px; border: 2px solid var(--ink); font-size: 14px;
                    background: var(--paper); color: var(--ink); transition: all 120ms ease; font-family: var(--font-mono);
                }
                .field input:focus { outline: none; box-shadow: var(--shadow-sm); background: var(--panel-strong, #fff); }
                .login-btn {
                    width: 100%; padding: 15px 0; background: var(--teal); color: var(--paper); border: 2px solid var(--ink);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 800; letter-spacing: 0.14em; text-transform: uppercase;
                    cursor: pointer; margin-top: 12px; transition: all 120ms ease;
                }
                .login-btn:hover { background: var(--surface-accent); }
                .login-error { color: var(--red); font-family: var(--font-mono); font-size: 12px; margin-top: 12px; display: none; font-weight: 700; }
                .register-name { display: none; }
                .oauth-divider {
                    display: flex; align-items: center; gap: 12px; margin: 20px 0;
                    font-family: var(--font-mono); font-size: 10px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.1em;
                }
                .oauth-divider::before, .oauth-divider::after { content: ""; flex: 1; height: 1px; background: var(--ink); }
                .oauth-btns { display: flex; gap: 8px; }
                .oauth-btn {
                    flex: 1; padding: 10px; border: 2px solid var(--ink); background: var(--surface-soft); color: var(--ink);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.06em;
                    cursor: not-allowed; opacity: 0.5; text-align: center;
                }
                @media (max-width: 980px) {
                    .app-shell { grid-template-columns: 1fr; }
                    .sidebar { height: auto; border-right: 0; border-bottom: 2px solid var(--ink); }
                }
            </style>
            <div class="login-overlay" data-ref="loginOverlay">
                <div class="login-card">
                    <h2>Keel Portal</h2>
                    <p>Sign in to manage your account</p>
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
                        <input type="email" data-ref="inputEmail" placeholder="you@example.com">
                    </div>
                    <div class="field">
                        <label>Password</label>
                        <input type="password" data-ref="inputPassword" placeholder="Password">
                    </div>
                    <div class="login-error" data-ref="loginError"></div>
                    <button class="login-btn" data-ref="loginBtn">Sign In</button>
                </div>
            </div>
            <div class="app-shell" data-ref="appShell" hidden>
                <aside class="sidebar">
                    <div class="brand" data-ref="brand" title="Click to collapse sidebar">
                        <div class="brand-copy">
                            <h1>Keel</h1>
                            <p>Portal</p>
                        </div>
                        <button class="sidebar-toggle" data-ref="sidebarToggle" title="Toggle sidebar" aria-label="Toggle sidebar"></button>
                    </div>
                    <nav class="nav-list" data-ref="nav"></nav>
                    <div class="sidebar-footer">
                        <div class="user-pill" data-ref="userPill" title="Account & preferences">
                            <span class="user-dot"></span>
                            <span class="user-email" data-ref="userEmail">Signed in</span>
                        </div>
                        <button class="logout-btn" data-ref="logoutBtn"><span>Sign Out</span></button>
                    </div>
                </aside>
                <div class="main-shell">
                    <header class="topbar">
                        <div class="topbar-sys">
                            <span class="sys-cross">+</span>
                            <span data-ref="sysCrumb">PORTAL / DASHBOARD</span>
                        </div>
                        <div class="topbar-actions">
                            <span class="sys-stat"><span class="sys-dot"></span>ONLINE</span>
                            <button class="refresh-btn" data-ref="refreshBtn">↻ REFRESH</button>
                        </div>
                    </header>
                    <main class="content" data-ref="content">
                        <customer-panel-dashboard class="panel" data-ref="panelDashboard"></customer-panel-dashboard>
                        <customer-panel-keys class="panel" data-ref="panelKeys"></customer-panel-keys>
                        <customer-panel-billing class="panel" data-ref="panelBilling"></customer-panel-billing>
                        <customer-panel-pricing class="panel" data-ref="panelPricing"></customer-panel-pricing>
                    </main>
                </div>
            </div>
            <div class="user-popover" data-ref="userPopover">
                <div class="popover-section">
                    <div class="popover-label">Signed in as</div>
                    <div class="popover-email" data-ref="popoverEmail">—</div>
                </div>
                <div class="popover-section">
                    <div class="popover-label">Appearance</div>
                    <div class="theme-toggle" data-ref="themeToggle">
                        <button data-theme="auto">Auto</button>
                        <button data-theme="light">Light</button>
                        <button data-theme="dark">Dark</button>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        loadStoredAuth();
        hydrateHash();
        this._setupLogin();
        this._setupNav();
        this._setupSidebar();
        this._setupTheme();
        this._setupUserPopover();
        this._renderState();
        this.refs.refreshBtn.addEventListener('click', () => this._refreshActive());
        window.addEventListener('hashchange', () => { hydrateHash(); this._renderState(); });
        this._reveal();
        this._autoRefresh = setInterval(() => {
            if (!state.loggedIn) return;
            this._refreshActive();
        }, 15000);
    }

    _reveal() {
        const gsap = window.gsap;
        if (!gsap) return;
        const targets = [
            this.shadowRoot.querySelector('.brand h1'),
            ...this.shadowRoot.querySelectorAll('.nav-link'),
            this.shadowRoot.querySelector('.user-pill'),
        ].filter(Boolean);
        if (targets.length === 0) return;
        gsap.fromTo(targets, { autoAlpha: 0, y: 8 }, { autoAlpha: 1, y: 0, duration: 0.45, ease: 'power2.out', stagger: 0.04 });
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
        // logoutBtn is wired by _setupUserPopover()
    }

    _setupNav() {
        this.refs.nav.addEventListener('click', (e) => {
            const link = e.target.closest('[data-tab-id]');
            if (!link) return;
            setTab(link.dataset.tabId);
        });
    }

    _setupSidebar() {
        const shell = this.refs.appShell;
        const collapsed = localStorage.getItem('keel-sidebar-collapsed') === '1';
        if (collapsed) shell.classList.add('collapsed');
        this._renderToggleIcon();
        const toggle = () => {
            shell.classList.toggle('collapsed');
            localStorage.setItem('keel-sidebar-collapsed', shell.classList.contains('collapsed') ? '1' : '0');
            this._renderToggleIcon();
        };
        this.refs.brand.addEventListener('click', toggle);
        this.refs.sidebarToggle.addEventListener('click', (e) => { e.stopPropagation(); toggle(); });
    }

    _renderToggleIcon() {
        const collapsed = this.refs.appShell.classList.contains('collapsed');
        if (collapsed) {
            this.refs.sidebarToggle.innerHTML = '<img src="/favicon.svg" alt="" style="width:22px;height:22px;display:block;">';
        } else {
            this.refs.sidebarToggle.innerHTML = '<svg viewBox="0 0 24 24" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>';
        }
    }

    _setupTheme() {
        const stored = localStorage.getItem('keel-theme-pref');
        this._themePref = stored || 'auto';
        this._mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
        this._applyTheme();
        this._mediaQuery.addEventListener('change', () => {
            if (this._themePref === 'auto') this._applyTheme();
        });
    }

    _applyTheme() {
        const dark = this._themePref === 'dark'
            || (this._themePref === 'auto' && this._mediaQuery && this._mediaQuery.matches);
        document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
        if (this.refs.themeToggle) this._updateThemePopoverUI();
    }

    setThemePref(pref) {
        this._themePref = pref;
        if (pref === 'auto') localStorage.removeItem('keel-theme-pref');
        else localStorage.setItem('keel-theme-pref', pref);
        this._applyTheme();
    }

    _updateThemePopoverUI() {
        if (!this.refs.themeToggle) return;
        this.refs.themeToggle.querySelectorAll('button').forEach(b => {
            b.classList.toggle('is-active', b.dataset.theme === this._themePref);
        });
    }

    _setupUserPopover() {
        const popover = this.refs.userPopover;
        const close = () => { popover.classList.remove('is-open'); };
        const open = () => {
            this._positionUserPopover();
            this._updateThemePopoverUI();
            popover.classList.add('is-open');
        };
        this.refs.userPill.addEventListener('click', (e) => {
            e.stopPropagation();
            if (popover.classList.contains('is-open')) close(); else open();
        });
        this.refs.themeToggle.addEventListener('click', (e) => {
            const btn = e.target.closest('button[data-theme]');
            if (!btn) return;
            this.setThemePref(btn.dataset.theme);
        });
        this.refs.logoutBtn.addEventListener('click', () => { logout(); this._renderState(); });
        window.addEventListener('resize', () => {
            if (popover.classList.contains('is-open')) this._positionUserPopover();
        });
        document.addEventListener('click', (e) => {
            if (!popover.classList.contains('is-open')) return;
            if (popover.contains(e.target) || this.refs.userPill.contains(e.target)) return;
            close();
        });
    }

    _positionUserPopover() {
        const popover = this.refs.userPopover;
        const pill = this.refs.userPill;
        if (!popover || !pill) return;
        const rect = pill.getBoundingClientRect();
        const width = Math.max(240, popover.offsetWidth || 240);
        const height = popover.offsetHeight || 160;
        let left = rect.right - width;
        if (left < 12) left = 12;
        if (left + width > window.innerWidth - 12) left = window.innerWidth - width - 12;
        let top = rect.top - height - 12;
        if (top < 12) top = rect.bottom + 12;
        if (top + height > window.innerHeight - 12) top = window.innerHeight - height - 12;
        popover.style.left = `${left}px`;
        popover.style.top = `${Math.max(12, top)}px`;
        popover.style.right = 'auto';
        popover.style.bottom = 'auto';
        popover.style.width = `${width}px`;
    }

    _renderState() {
        const isLoggedIn = state.loggedIn;
        this.refs.loginOverlay.style.display = isLoggedIn ? 'none' : 'flex';
        this.refs.appShell.hidden = !isLoggedIn;
        if (!isLoggedIn) return;

        this.refs.userEmail.textContent = state.customer?.email || 'Signed in';
        if (this.refs.popoverEmail) this.refs.popoverEmail.textContent = state.customer?.email || 'Signed in';

        const sections = {};
        TABS.forEach(tab => {
            const s = tab.section || 'OTHER';
            (sections[s] = sections[s] || []).push(tab);
        });
        this.refs.nav.innerHTML = Object.entries(sections).map(([label, items]) => {
            const links = items.map(tab => `
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
            return `<div class="nav-section-label">${label}</div>${links}`;
        }).join('');

        const tab = TABS.find(t => t.id === state.activeTab);
        if (this.refs.sysCrumb) this.refs.sysCrumb.textContent = `PORTAL / ${(tab?.label || 'Dashboard').toUpperCase()}`;

        const panels = {
            home: this.refs.panelDashboard,
            keys: this.refs.panelKeys,
            billing: this.refs.panelBilling,
            pricing: this.refs.panelPricing,
        };
        const previousActive = Object.entries(panels).find(([, el]) => el.classList.contains('is-active'))?.[0];
        Object.entries(panels).forEach(([id, el]) => {
            el.classList.toggle('is-active', id === state.activeTab);
            if (id === state.activeTab && typeof el.refresh === 'function') el.refresh();
        });
        if (previousActive !== state.activeTab) {
            this._animateTabSwitch(panels[state.activeTab], previousActive);
        }
    }

    _animateTabSwitch(activeEl) {
        if (!activeEl) return;
        const gsap = window.gsap;
        if (!gsap) {
            activeEl.classList.add('fallback-enter');
            setTimeout(() => activeEl.classList.remove('fallback-enter'), 260);
            return;
        }
        gsap.fromTo(activeEl, { autoAlpha: 0, y: 10 }, { autoAlpha: 1, y: 0, duration: 0.32, ease: 'power2.out', clearProps: 'transform' });
        if (this.refs.sysCrumb) {
            gsap.fromTo(this.refs.sysCrumb, { autoAlpha: 0, x: -6 }, { autoAlpha: 1, x: 0, duration: 0.25, ease: 'power2.out' });
        }
    }

    _refreshActive() {
        const panels = {
            home: this.refs.panelDashboard,
            keys: this.refs.panelKeys,
            billing: this.refs.panelBilling,
            pricing: this.refs.panelPricing,
        };
        const active = panels[state.activeTab];
        if (active && typeof active.refresh === 'function') active.refresh();
    }
}

customElements.define('customer-app', CustomerApp);
