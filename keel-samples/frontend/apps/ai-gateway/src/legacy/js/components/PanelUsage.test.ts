import { beforeEach, describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './shared/KeelHero.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelUsage } from './PanelUsage.js';

describe('legacy usage panel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('mounts with toolbar and hero refs intact', () => {
    const panel = document.createElement('ai-panel-usage') as any;

    expect(() => document.body.appendChild(panel)).not.toThrow();
    expect(panel.refs.toolbar).toBeTruthy();
    expect(panel.refs.hero).toBeTruthy();
  });

  it('keeps the usage table dominant with a compact hero, shared overview rail, and sticky detail column', () => {
    const template = new PanelUsage().template();

    expect(template).toContain('<keel-hero data-ref="hero" compact="true"></keel-hero>');
    expect(template).toContain('<div class="overview">');
    expect(template).toContain('position: sticky; right: 0;');
    expect(template).toContain('min-height: 50vh;');
  });

  it('labels the reset filter control as an action field', () => {
    const panel = new PanelUsage();
    panel._filters = panel._defaultFilters(new Date('2026-06-26T18:45:12'));
    panel._filterOptions = {
      group: [{ value: '', label: 'All groups' }],
      channel: [{ value: '', label: 'All channels' }],
      model: [{ value: '', label: 'All models' }],
      status: [{ value: '', label: 'All status' }],
      limit: [{ value: '50', label: '50 rows' }],
    };
    panel.refs = { toolbar: document.createElement('div') } as any;

    panel._renderToolbar();

    expect(panel.refs.toolbar.innerHTML).toContain('<label>Action</label>');
    expect(panel.refs.toolbar.innerHTML).toContain('Reset Filters');
  });

  it('prefers the explicit routing group name over ids', () => {
    const panel = new PanelUsage();
    panel._groupNameById = { premium: 'Premium Pool' };
    expect(panel._groupLabel({ routingGroupName: 'Direct Name' })).toBe('Direct Name');
    expect(panel._groupLabel({ routingGroupId: 'premium' })).toBe('Premium Pool');
    expect(panel._groupLabel({ groupId: 'premium' })).toBe('Premium Pool');
  });

  it('falls back to pool level then unknown for groups', () => {
    const panel = new PanelUsage();
    panel._groupNameById = {};
    expect(panel._groupLabel({ poolLevelId: 'group-p0' })).toBe('group-p0');
    expect(panel._groupLabel({})).toBe('unknown');
  });

  it('shows the channel name when available, otherwise maps the id', () => {
    const panel = new PanelUsage();
    panel._channelNameById = { 'ch-1': 'OpenAI Main' };
    expect(panel._channelLabel({ channelName: 'Inline Name' })).toBe('Inline Name');
    expect(panel._channelLabel({ channelId: 'ch-1' })).toBe('OpenAI Main');
    expect(panel._channelLabel({ upstreamKeyId: 'ch-1' })).toBe('OpenAI Main');
    expect(panel._channelLabel({})).toBe('—');
  });

  it('converts local datetime filters into UTC query timestamps', () => {
    const panel = new PanelUsage();
    const localValue = '2026-06-26T12:34';

    expect(panel._toUtcIso(localValue)).toBe(new Date(localValue).toISOString());
    expect(panel._toUtcExclusiveIso(localValue)).toBe(new Date(new Date(localValue).getTime() + 60_000).toISOString());
  });

  it('formats timestamps for local display instead of returning the raw UTC string', () => {
    const panel = new PanelUsage();
    const raw = '2026-06-26T00:00:00Z';
    const formatted = panel._formatLocalTimestamp(raw);

    expect(formatted).not.toBe(raw);
    expect(formatted).not.toBe('—');
  });

  it('defaults the range to local start of today with no upper bound', () => {
    const panel = new PanelUsage();
    const now = new Date('2026-06-26T18:45:12');
    const filters = panel._defaultFilters(now);

    expect(filters.preset).toBe('today');
    expect(filters.to).toBeNull();
    expect(filters.from?.getFullYear()).toBe(2026);
    expect(filters.from?.getMonth()).toBe(5);
    expect(filters.from?.getDate()).toBe(26);
    expect(filters.from?.getHours()).toBe(0);
    expect(filters.from?.getMinutes()).toBe(0);
  });

  it('builds the last month preset as previous month start through final minute', () => {
    const panel = new PanelUsage();
    const now = new Date('2026-06-26T18:45:12');
    const range = panel._rangeForPreset('lastMonth', now);

    expect(range.from?.getFullYear()).toBe(2026);
    expect(range.from?.getMonth()).toBe(4);
    expect(range.from?.getDate()).toBe(1);
    expect(range.from?.getHours()).toBe(0);
    expect(range.to?.getFullYear()).toBe(2026);
    expect(range.to?.getMonth()).toBe(4);
    expect(range.to?.getDate()).toBe(31);
    expect(range.to?.getHours()).toBe(23);
    expect(range.to?.getMinutes()).toBe(59);
  });
});
