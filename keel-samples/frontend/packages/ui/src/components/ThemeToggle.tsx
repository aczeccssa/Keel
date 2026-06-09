import { getInitialTheme, setStoredTheme, type ThemePreference } from '../theme/theme';

export function ThemeToggle() {
  const current = getInitialTheme();
  const setTheme = (theme: ThemePreference) => setStoredTheme(theme);
  return (
    <div className="keel-theme-toggle" aria-label="Theme">
      <button type="button" aria-pressed={current === 'light'} onClick={() => setTheme('light')}>Light</button>
      <button type="button" aria-pressed={current === 'dark'} onClick={() => setTheme('dark')}>Dark</button>
    </div>
  );
}
