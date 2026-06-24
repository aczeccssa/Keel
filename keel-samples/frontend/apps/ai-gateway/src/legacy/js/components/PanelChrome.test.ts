import { describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelDashboard } from './PanelDashboard.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelGroups } from './PanelGroups.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelProviders } from './PanelProviders.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelUsage } from './PanelUsage.js';

describe('legacy panel chrome', () => {
  it('renders a hero in dashboard and usage panels', () => {
    expect(new PanelDashboard().template()).toContain('<keel-hero');
    expect(new PanelUsage().template()).toContain('<keel-hero');
  });

  it('keeps the providers hero outside the action toolbar', () => {
    const template = new PanelProviders().template();
    expect(template.indexOf('<keel-hero')).toBeLessThan(template.indexOf('<div class="toolbar">'));
  });

  it('renders cooldown channels with a cooldown label in providers', () => {
    const html = new PanelProviders()._cardHtml({
      channelId: 'ch-1',
      name: 'Test',
      enabled: true,
      status: 'COOLDOWN',
      protocol: 'OPENAI_CHAT',
      baseUrl: 'mock://test',
      memberships: [],
      groupId: 'default',
      priority: 0,
      weight: 100,
      models: [],
    });
    expect(html).toContain('Cooldown');
  });

  it('renders cooldown rows as warning state in groups', () => {
    expect(new PanelGroups()._statusCell({ enabled: true, status: 'COOLDOWN' })).toContain('Cooldown');
  });
});
