import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { KeelLogo } from '../brand/KeelLogo';
import { IconButton } from './IconButton';
import { ThemeToggle } from './ThemeToggle';

export interface ShellTab {
  id: string;
  label: string;
  hint: string;
  section: string;
  icon?: string;
  badge?: number | string;
}

export interface AppShellProps {
  productLabel: string;
  tabs: ShellTab[];
  activeTab: string;
  onSelectTab: (tabId: string) => void;
  userLabel?: string;
  userEmail?: string;
  onLogout?: () => void;
  onRefresh?: () => void;
  isRefreshing?: boolean;
  isLive?: boolean;
  navCounts?: Record<string, number | string>;
  children: ReactNode;
}

const COLLAPSED_KEY = 'keel-sidebar-collapsed';

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

function initials(label?: string): string {
  if (!label) return '·';
  const parts = label.split(/[ @.]/).filter(Boolean);
  if (parts.length === 0) return label.charAt(0).toUpperCase();
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[1]![0]!).toUpperCase();
}

export function AppShell({
  productLabel,
  tabs,
  activeTab,
  onSelectTab,
  userLabel,
  userEmail,
  onLogout,
  onRefresh,
  isRefreshing = false,
  isLive = false,
  navCounts,
  children
}: AppShellProps) {
  const [collapsed, setCollapsed] = useState<boolean>(readCollapsed);
  const [popoverOpen, setPopoverOpen] = useState(false);
  const popoverRef = useRef<HTMLDivElement | null>(null);
  const userBtnRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    try {
      localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '0');
    } catch {
      // localStorage may be unavailable; collapse state is non-essential
    }
  }, [collapsed]);

  useEffect(() => {
    if (!popoverOpen) return;
    function onDocClick(e: MouseEvent) {
      if (
        popoverRef.current?.contains(e.target as Node) ||
        userBtnRef.current?.contains(e.target as Node)
      ) {
        return;
      }
      setPopoverOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setPopoverOpen(false);
    }
    document.addEventListener('mousedown', onDocClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [popoverOpen]);

  const sections = useMemo(() => Array.from(new Set(tabs.map((tab) => tab.section))), [tabs]);
  const currentTab = tabs.find((t) => t.id === activeTab);
  const currentSection = currentTab?.section ?? sections[0] ?? '';
  const currentLabel = currentTab?.label ?? '';

  return (
    <div className="keel-app-shell" data-collapsed={collapsed ? 'true' : 'false'}>
      <aside className="keel-sidebar" aria-label="Primary navigation">
        <div className="keel-sidebar__brand">
          <KeelLogo label={collapsed ? '' : productLabel} />
          <button
            type="button"
            className="keel-sidebar__brand-collapse"
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            onClick={() => setCollapsed((c) => !c)}
            title={collapsed ? 'Expand' : 'Collapse'}
          >
            <span className="material-symbols-outlined" aria-hidden="true">
              {collapsed ? 'chevron_right' : 'chevron_left'}
            </span>
          </button>
        </div>
        <nav>
          {sections.map((section) => (
            <section key={section}>
              <p className="keel-sidebar__nav-section">{section}</p>
              {tabs
                .filter((tab) => tab.section === section)
                .map((tab) => {
                  const count = navCounts?.[tab.id];
                  return (
                    <button
                      type="button"
                      key={tab.id}
                      className={`keel-sidebar__nav-button ${tab.id === activeTab ? 'is-active' : ''}`}
                      aria-current={tab.id === activeTab ? 'page' : undefined}
                      onClick={() => onSelectTab(tab.id)}
                      title={collapsed ? `${tab.label} — ${tab.hint}` : undefined}
                    >
                      {tab.icon ? (
                        <span className="material-symbols-outlined" aria-hidden="true">
                          {tab.icon}
                        </span>
                      ) : null}
                      <span className="keel-sidebar__nav-label">{tab.label}</span>
                      {count != null ? (
                        <span className="keel-sidebar__badge">{count}</span>
                      ) : null}
                    </button>
                  );
                })}
            </section>
          ))}
        </nav>
        <div className="keel-sidebar__user">{userLabel ?? userEmail ?? ''}</div>
      </aside>
      <div className="keel-main">
        <header className="keel-topbar">
          <button
            type="button"
            className="keel-topbar__brand"
            aria-label="Toggle sidebar"
            onClick={() => setCollapsed((c) => !c)}
          >
            <KeelLogo label="" />
          </button>
          <nav className="keel-topbar__crumbs" aria-label="Breadcrumb">
            <span className="keel-topbar__crumb-section">{currentSection}</span>
            <span className="keel-topbar__crumb-sep" aria-hidden="true">
              /
            </span>
            <span className="keel-topbar__crumb-tab">{currentLabel}</span>
          </nav>
          <div className="keel-topbar__right">
            {isLive ? (
              <span className="keel-topbar__live" aria-live="polite">
                <span className="dot" aria-hidden="true" />
                LIVE
              </span>
            ) : null}
            {onRefresh ? (
              <IconButton
                ariaLabel="Refresh"
                onClick={onRefresh}
                disabled={isRefreshing}
                title={isRefreshing ? 'Refreshing…' : 'Refresh'}
              >
                <span
                  className="material-symbols-outlined"
                  style={isRefreshing ? { animation: 'keel-spin 0.8s linear infinite' } : undefined}
                >
                  refresh
                </span>
              </IconButton>
            ) : null}
            <ThemeToggle />
            <button
              ref={userBtnRef}
              type="button"
              className="keel-topbar__user"
              aria-haspopup="menu"
              aria-expanded={popoverOpen}
              onClick={() => setPopoverOpen((p) => !p)}
            >
              <span className="keel-topbar__avatar" aria-hidden="true">
                {initials(userLabel ?? userEmail)}
              </span>
              <span>{userLabel ?? userEmail ?? 'Account'}</span>
              <span className="material-symbols-outlined" aria-hidden="true">
                expand_more
              </span>
            </button>
            {popoverOpen ? (
              <div ref={popoverRef} className="keel-popover" role="menu">
                <div className="keel-popover__row">
                  <span>Signed in as</span>
                  <strong>{userEmail ?? userLabel ?? '—'}</strong>
                </div>
                <div className="keel-popover__divider" />
                {onLogout ? (
                  <button
                    type="button"
                    className="keel-popover__row"
                    role="menuitem"
                    onClick={() => {
                      setPopoverOpen(false);
                      onLogout();
                    }}
                  >
                    <span>Sign out</span>
                    <span className="material-symbols-outlined" aria-hidden="true">
                      logout
                    </span>
                  </button>
                ) : null}
              </div>
            ) : null}
          </div>
        </header>
        <div className="keel-main__inner">{children}</div>
      </div>
    </div>
  );
}
