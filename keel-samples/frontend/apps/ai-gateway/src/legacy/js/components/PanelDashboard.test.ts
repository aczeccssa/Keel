import { describe, expect, it, vi } from 'vitest';
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

  it('compresses 30-day trends into fewer bars for narrow cards', () => {
    const panel = new PanelDashboard();
    panel._window = '30d';
    const points = Array.from({ length: 30 }, (_, i) => ({
      timestamp: i,
      requests: i + 1
    }));

    const compact = panel._compactTrend(points, (group: Array<{ timestamp: number; requests: number }>) => ({
      timestamp: group[group.length - 1].timestamp,
      requests: group.reduce((sum: number, point) => sum + point.requests, 0)
    }));

    expect(compact).toHaveLength(10);
    expect(compact[0].requests).toBe(6);
    expect(compact[9].requests).toBe(87);
  });

  it('renders at most 10 request and token bars for the 30-day window', () => {
    const panel = new PanelDashboard();
    panel._window = '30d';
    panel.refs = {
      hero: { render: vi.fn() },
      overviewStats: { render: vi.fn() },
      healthGrid: { innerHTML: '' },
      healthNote: { textContent: '' },
      reqHint: { textContent: '' },
      requestChart: { render: vi.fn() },
      tokenChart: { render: vi.fn() },
      latencyChart: { render: vi.fn() },
      modelChart: { render: vi.fn() },
      channelChart: { render: vi.fn() },
      groupChart: { render: vi.fn() },
      errorChart: { render: vi.fn() },
      recentScroll: { innerHTML: '' }
    };

    const requestPoints = Array.from({ length: 30 }, (_, i) => ({
      timestamp: Date.UTC(2026, 5, i + 1),
      requests: i + 1,
      successRate: 1
    }));
    const tokenPoints = Array.from({ length: 30 }, (_, i) => ({
      timestamp: Date.UTC(2026, 5, i + 1),
      promptTokens: i + 1,
      completionTokens: i + 1,
      cacheWriteTokens: 0,
      cacheReadTokens: 0,
      costUsd: 0
    }));

    panel._render({
      overview: {},
      trends: {
        bucketGranularity: '1d',
        bucketLabelMode: 'day',
        requests: requestPoints,
        tokens: tokenPoints,
        latency: []
      },
      distributions: {}
    }, []);

    const requestCall = panel.refs.requestChart.render.mock.calls[0][0];
    const tokenCall = panel.refs.tokenChart.render.mock.calls[0][0];

    expect(requestCall.data).toHaveLength(10);
    expect(requestCall.labels).toHaveLength(10);
    expect(tokenCall.data).toHaveLength(10);
    expect(tokenCall.labels).toHaveLength(10);
    expect(panel.refs.reqHint.textContent).toBe('30d · 3d');
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
