import type { ReactNode } from 'react';
import { KeelLogo } from '../brand/KeelLogo';
import { ThemeToggle } from './ThemeToggle';

export interface ShellTab {
  id: string;
  label: string;
  hint: string;
  section: string;
}

export interface AppShellProps {
  productLabel: string;
  tabs: ShellTab[];
  activeTab: string;
  onSelectTab: (tabId: string) => void;
  userLabel?: string;
  onLogout?: () => void;
  children: ReactNode;
}

export function AppShell({ productLabel, tabs, activeTab, onSelectTab, userLabel, onLogout, children }: AppShellProps) {
  const sections = Array.from(new Set(tabs.map((tab) => tab.section)));
  return (
    <div className="keel-app-shell">
      <aside className="keel-sidebar">
        <div className="keel-brand"><KeelLogo label={productLabel} /></div>
        <nav>
          {sections.map((section) => (
            <section key={section}>
              <p className="keel-nav-section">{section}</p>
              {tabs.filter((tab) => tab.section === section).map((tab) => (
                <button
                  type="button"
                  key={tab.id}
                  className={tab.id === activeTab ? 'is-active' : ''}
                  onClick={() => onSelectTab(tab.id)}
                >
                  <strong>{tab.label}</strong>
                  <span>{tab.hint}</span>
                </button>
              ))}
            </section>
          ))}
        </nav>
        <footer>
          {userLabel ? <span>{userLabel}</span> : null}
          <ThemeToggle />
          {onLogout ? <button type="button" onClick={onLogout}>Sign out</button> : null}
        </footer>
      </aside>
      <main className="keel-main">{children}</main>
    </div>
  );
}
