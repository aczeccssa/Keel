// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { getInitialTheme, setStoredTheme } from './theme';

describe('theme helpers', () => {
  it('uses the existing keel-theme-pref localStorage key', () => {
    setStoredTheme('dark');
    expect(localStorage.getItem('keel-theme-pref')).toBe('dark');
    expect(getInitialTheme()).toBe('dark');
  });

  it('falls back to light for invalid stored values', () => {
    localStorage.setItem('keel-theme-pref', 'solarized');
    expect(getInitialTheme()).toBe('light');
  });
});
