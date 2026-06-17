import { KeelElement } from './components/base/KeelElement.js';
import { state, setTab, hydrateHash } from './state.js';
import { loadStoredAuth, login, register, logout } from './auth.js';
import { requestJson } from './api.js';
import { TABS } from './config.js';

import './components/shared/KeelStatGrid.js';
import './components/shared/KeelDataTable.js';
import './components/shared/KeelHero.js';
import './components/shared/KeelDetailList.js';
import './components/shared/KeelChart.js';

import './components/PanelOverview.js';
import './components/PanelDashboard.js';
import './components/PanelUsage.js';
import './components/PanelAvailability.js';
import './components/PanelProviders.js';
import './components/PanelGroups.js';
import './components/PanelKeys.js';
import './components/PanelPricing.js';
import './components/PanelPools.js';
import './components/PanelRateLimits.js';
import './components/PanelUsers.js';
import './components/PanelCustomers.js';
import './components/PanelRedemptionCodes.js';

const ICONS = {
    dashboard: '<path d="M3 3v18h18"/><path d="m7 14 3-3 3 2 4-6"/>',
    usage: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>',
    pricing: '<line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>',
    key: '<path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>',
    dns: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
    hub: '<circle cx="12" cy="12" r="3"/><circle cx="5" cy="5" r="2"/><circle cx="19" cy="5" r="2"/><circle cx="5" cy="19" r="2"/><circle cx="19" cy="19" r="2"/><line x1="7" y1="7" x2="10" y2="10"/><line x1="17" y1="7" x2="14" y2="10"/><line x1="7" y1="17" x2="10" y2="14"/><line x1="17" y1="17" x2="14" y2="14"/>',
    speed: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    group: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    terminal: '<polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/>',
    gift: '<polyline points="20 12 20 22 4 22 4 12"/><rect x="2" y="7" width="20" height="5"/><line x1="12" y1="22" x2="12" y2="7"/><path d="M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7z"/><path d="M12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z"/>',
};

class AiProxyApp extends KeelElement {
    hostStyles() { return 'display:block;height:100vh;'; }

    template() {
        return `
            <style>
                :host {
                    --font-headline: "Archivo Black", "Helvetica Neue", Arial, sans-serif;
                    --font-body: "Archivo", "Helvetica Neue", Arial, sans-serif;
                    --font-mono: "JetBrains Mono", ui-monospace, monospace;
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
                /* Brand — clickable as a whole to toggle collapse; chevron is a visual affordance */
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

                /* Section labels */
                .nav-section-label {
                    padding: 10px 10px 4px;
                    font-family: var(--font-mono);
                    font-size: 9px; font-weight: 800;
                    letter-spacing: 0.2em; text-transform: uppercase;
                    color: var(--muted);
                }
                .collapsed .nav-section-label { font-size: 0; height: 8px; padding: 4px 0; overflow: hidden; }

                /* Nav list */
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
                .nav-badge {
                    display: inline-flex; align-items: center; justify-content: center;
                    min-width: 22px; height: 18px; padding: 0 6px;
                    background: var(--surface-strong); color: var(--ink);
                    font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                    letter-spacing: 0.04em;
                    border: 2px solid var(--ink);
                }
                .nav-link.is-active .nav-badge { background: var(--teal); color: var(--on-accent); border-color: var(--teal); }
                .collapsed .nav-badge { display: none; }

                /* Collapsed: 56px square icon-only cells, sidebar is 64px wide with 4px padding */
                .collapsed .nav-link { width: 40px; height: 40px; padding: 0; justify-content: center; gap: 0; margin: 4px auto; }
                .collapsed .nav-copy { display: none; }
                .collapsed .nav-icon { width: 28px; height: 28px; }
                /* Collapsed toggle: show favicon (per observability pattern) */
                .collapsed .sidebar-toggle { width: 40px; height: 40px; padding: 0; border: 0; background: transparent; }
                .collapsed .sidebar-toggle:hover { background: var(--surface-muted); }
                .collapsed .sidebar-toggle svg { width: 18px; height: 18px; stroke-width: 2; }

                /* Sidebar footer */
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

                /* User popover (theme + sign out) */
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
                .live-indicator {
                    align-self: stretch;
                    display: inline-flex; align-items: center; gap: 6px;
                    padding: 0 16px;
                    background: var(--surface-muted);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.12em;
                    color: var(--teal); cursor: pointer; user-select: none;
                    border-left: 2px solid var(--ink);
                    transition: color 120ms;
                }
                .live-dot {
                    width: 8px; height: 8px; background: var(--teal); border-radius: 50%;
                    animation: livePulse 2s ease-in-out infinite;
                }
                .live-indicator.paused { color: var(--muted); }
                .live-indicator.paused .live-dot { background: var(--muted); animation: none; }
                @keyframes livePulse { 0%,100%{ opacity:1; } 50%{ opacity:0.3; } }
                .refresh-btn {
                    align-self: stretch;
                    border: 0;
                    border-left: 2px solid var(--ink);
                    background: var(--surface-soft); color: var(--ink); cursor: pointer;
                    padding: 0 18px; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .refresh-btn:hover { background: var(--teal); color: var(--paper); }
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
                .panel.fallback-enter { animation: panel-enter 200ms var(--ease-smooth); }
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
                    cursor: pointer; color: var(--ink); background: var(--paper); transition: all 120ms ease;
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
                .onboarding { margin-top: 24px; padding: 16px 18px; background: var(--teal-soft); border: 2px solid var(--ink); font-family: var(--font-mono); font-size: 11px; line-height: 1.7; color: var(--ink); }
                .onboarding code { background: var(--surface-accent); color: var(--on-accent); padding: 2px 6px; font-family: var(--font-mono); font-size: 11px; }
                @media (max-width: 980px) {
                    .app-shell { grid-template-columns: 1fr; }
                    .sidebar { height: auto; border-right: 0; border-bottom: 2px solid var(--ink); }
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
                        Create API keys in the Keys panel, then test channels and models from the routing panels.
                    </div>
                </div>
            </div>
            <div class="app-shell" data-ref="appShell" hidden>
                <aside class="sidebar">
                    <div class="brand" data-ref="brand" title="Click to collapse sidebar">
                        <div class="brand-copy">
                            <h1>Keel</h1>
                            <p>AI Proxy</p>
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
                        <div class="topbar-sys" data-ref="sysLine">
                            <span class="sys-cross">+</span>
                            <span data-ref="sysCrumb">SECTOR / DASHBOARD</span>
                        </div>
                        <div class="topbar-actions">
                            <span class="sys-stat"><span class="sys-dot"></span>ONLINE</span>
                            <span class="live-indicator" data-ref="liveIndicator" title="Click to toggle live updates">
                                <span class="live-dot"></span>LIVE
                            </span>
                            <button class="refresh-btn" data-ref="refreshBtn">↻ REFRESH</button>
                        </div>
                    </header>
                    <main class="content" data-ref="content">
                        <ai-panel-overview class="panel" data-ref="panelOverview"></ai-panel-overview>
                        <ai-panel-dashboard class="panel" data-ref="panelDashboard"></ai-panel-dashboard>
                        <ai-panel-usage class="panel" data-ref="panelUsage"></ai-panel-usage>
                        <ai-panel-availability class="panel" data-ref="panelAvailability"></ai-panel-availability>
                        <ai-panel-providers class="panel" data-ref="panelChannels"></ai-panel-providers>
                        <ai-panel-groups class="panel" data-ref="panelGroups"></ai-panel-groups>
                        <ai-panel-keys class="panel" data-ref="panelKeys"></ai-panel-keys>
                        <ai-panel-pricing class="panel" data-ref="panelPricing"></ai-panel-pricing>
                        <ai-panel-rate-limits class="panel" data-ref="panelRateLimits"></ai-panel-rate-limits>
                        <ai-panel-users class="panel" data-ref="panelUsers"></ai-panel-users>
                        <ai-panel-customers class="panel" data-ref="panelCustomers"></ai-panel-customers>
                        <ai-panel-redemption-codes class="panel" data-ref="panelCodes"></ai-panel-redemption-codes>
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
        this._setupLiveIndicator();
        this._renderState();
        this.refs.refreshBtn.addEventListener('click', () => this._refreshActive());
        window.addEventListener('hashchange', () => { hydrateHash(); this._renderState(); });
        this._reveal();
        // Silent auto-refresh of the active panel every 15s.
        this._autoRefresh = setInterval(() => {
            if (!state.loggedIn) return;
            this._refreshActive();
        }, 15000);
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
        // logoutBtn is wired by _setupUserPopover()
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
        if (this.refs.popoverEmail) this.refs.popoverEmail.textContent = state.user?.email || 'Signed in';

        // Fetch nav badge counts (customers, codes, keys) and render grouped nav.
        this._loadBadges().then(() => this._renderNav());
    }

    async _loadBadges() {
        try {
            const data = await requestJson(`${API.airelay}/admin/nav-counts`);
            this._navBadges = {
                customers: data.customers || 0,
                redemptionCodes: data.redemptionCodes || 0,
                apiKeys: data.apiKeys || 0,
            };
        } catch {
            this._navBadges = this._navBadges || {};
        }
    }

    _renderNav() {
        const badges = this._navBadges || {};
        const sections = {};
        TABS.forEach(tab => {
            const s = tab.section || 'OTHER';
            (sections[s] = sections[s] || []).push(tab);
        });
        this.refs.nav.innerHTML = Object.entries(sections).map(([label, items]) => {
            const links = items.map(tab => {
                const count = badges[tab.badge];
                const badge = count > 0 ? `<span class="nav-badge">${count}</span>` : '';
                return `
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
                    ${badge}
                </a>
            `;
            }).join('');
            return `<div class="nav-section-label">${label}</div>${links}`;
        }).join('');

        const tab = TABS.find(t => t.id === state.activeTab);
        // Topbar shows a system breadcrumb (NOT the page title — the panel hero owns the title,
        // so it isn't duplicated). Uppercase telemetry style.
        if (this.refs.sysCrumb) this.refs.sysCrumb.textContent = `SECTOR / ${(tab?.label || 'Overview').toUpperCase()}`;

        const panels = {
            overview: this.refs.panelOverview,
            dashboard: this.refs.panelDashboard,
            usage: this.refs.panelUsage,
            availability: this.refs.panelAvailability,
            channels: this.refs.panelChannels,
            groups: this.refs.panelGroups,
            keys: this.refs.panelKeys,
            pricing: this.refs.panelPricing,
            ratelimits: this.refs.panelRateLimits,
            customers: this.refs.panelCustomers,
            codes: this.refs.panelCodes,
            users: this.refs.panelUsers,
        };
        const previousActive = Object.entries(panels).find(([, el]) => el.classList.contains('is-active'))?.[0];
        Object.entries(panels).forEach(([id, el]) => {
            el.classList.toggle('is-active', id === state.activeTab);
            if (id === state.activeTab && typeof el.refresh === 'function') {
                el.refresh();
            }
        });
        // Only animate when the active tab actually changed (silent refresh = no animation).
        if (previousActive !== state.activeTab) {
            this._animateTabSwitch(panels[state.activeTab], previousActive);
        }
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
        // Animate the system breadcrumb in the topbar so the user gets immediate feedback that
        // the tab actually changed.
        if (this.refs.sysCrumb) {
            gsap.fromTo(
                this.refs.sysCrumb,
                { autoAlpha: 0, x: -6 },
                { autoAlpha: 1, x: 0, duration: 0.25, ease: 'power2.out' }
            );
        }
    }

    _refreshActive() {
        const panels = {
            overview: this.refs.panelOverview,
            dashboard: this.refs.panelDashboard,
            usage: this.refs.panelUsage,
            availability: this.refs.panelAvailability,
            channels: this.refs.panelChannels,
            groups: this.refs.panelGroups,
            keys: this.refs.panelKeys,
            pricing: this.refs.panelPricing,
            ratelimits: this.refs.panelRateLimits,
            customers: this.refs.panelCustomers,
            codes: this.refs.panelCodes,
            users: this.refs.panelUsers,
        };
        const active = panels[state.activeTab];
        if (active && typeof active.refresh === 'function') active.refresh();
    }

    /* ── Sidebar collapse ─────────────────────────────────────────────── */
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
            // Show the favicon when collapsed (observability pattern).
            this.refs.sidebarToggle.innerHTML = '<img src="/favicon.svg" alt="" style="width:22px;height:22px;display:block;">';
        } else {
            this.refs.sidebarToggle.innerHTML = '<svg viewBox="0 0 24 24" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>';
        }
    }

    /* ── Theme: auto-detect from OS, user override in popover ─────────── */
    _setupTheme() {
        const stored = localStorage.getItem('keel-theme-pref'); // 'auto' | 'light' | 'dark'
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

    /* ── User popover (account + theme override + sign out) ───────────── */
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
            if (popover.classList.contains('is-open')) close();
            else open();
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
        // Click outside closes
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

    /* ── Live indicator toggle ────────────────────────────────────────── */
    _setupLiveIndicator() {
        this._livePaused = false;
        this.refs.liveIndicator.addEventListener('click', () => {
            this._livePaused = !this._livePaused;
            this.refs.liveIndicator.classList.toggle('paused', this._livePaused);
            this.refs.liveIndicator.querySelector('.live-dot').nextSibling.textContent = this._livePaused ? ' PAUSED' : ' LIVE';
            // Notify dashboard panel
            const dashboard = this.refs.panelDashboard;
            if (dashboard && typeof dashboard.setLiveMode === 'function') {
                dashboard.setLiveMode(!this._livePaused);
            }
        });
    }
}

customElements.define('ai-proxy-app', AiProxyApp);
