import { describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelDashboard } from './PanelDashboard.js';

describe('legacy dashboard panel', () => {
  it('keeps all labels when within the density budget', () => {
    const panel = new PanelDashboard();
    const labels = ['a', 'b', 'c'];
    expect(panel._thinLabels(labels)).toEqual(labels);
  });

  it('thins labels for wide day windows so charts stay readable', () => {
    const panel = new PanelDashboard();
    const labels = Array.from({ length: 30 }, (_, i) => `d${i}`);
    const thinned = panel._thinLabels(labels);
    expect(thinned.length).toBe(30);
    const shown = thinned.filter(Boolean);
    expect(shown.length).toBeLessThanOrEqual(10);
    expect(thinned[0]).toBe('d0');
  });

  it('labels day windows by date and time windows by clock', () => {
    const panel = new PanelDashboard();
    const ts = Date.UTC(2026, 5, 17, 9, 30);
    panel._labelMode = 'day';
    expect(panel._bucketLabel(ts)).toMatch(/[A-Za-z]{3}/);
    panel._labelMode = 'time';
    expect(panel._bucketLabel(ts)).toMatch(/\d/);
  });

  it('exposes a no-op live mode hook for the global toggle', () => {
    const panel = new PanelDashboard();
    expect(typeof panel.setLiveMode).toBe('function');
    expect(() => panel.setLiveMode(true)).not.toThrow();
  });
});
