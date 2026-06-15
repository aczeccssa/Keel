import { describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelDashboard } from './PanelDashboard.js';
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
});
