import { useEffect, useState } from 'react';
import { THEME_STORAGE_KEY, type ThemePreference } from '../theme/theme';

export function ThemeToggle() {
  const [theme, setTheme] = useState<ThemePreference>(() => {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === 'dark' || stored === 'light' ? stored : 'light';
  });

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  }, [theme]);

  return (
    <div role="group" aria-label="Theme" className="keel-theme-toggle">
      <button type="button" aria-pressed={theme === 'light'} onClick={() => setTheme('light')}>
        Light
      </button>
      <button type="button" aria-pressed={theme === 'dark'} onClick={() => setTheme('dark')}>
        Dark
      </button>
    </div>
  );
}
