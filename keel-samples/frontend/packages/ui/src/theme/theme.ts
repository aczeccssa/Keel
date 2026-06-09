export type ThemePreference = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'keel-theme-pref';

export function getInitialTheme(): ThemePreference {
  const stored = localStorage.getItem(THEME_STORAGE_KEY);
  if (stored === 'dark' || stored === 'light') return stored;
  return 'light';
}

export function setStoredTheme(theme: ThemePreference): void {
  localStorage.setItem(THEME_STORAGE_KEY, theme);
  document.documentElement.setAttribute('data-theme', theme);
}
