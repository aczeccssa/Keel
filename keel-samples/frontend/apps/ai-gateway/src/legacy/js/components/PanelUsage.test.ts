import { describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelUsage } from './PanelUsage.js';

describe('legacy usage panel', () => {
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
});
