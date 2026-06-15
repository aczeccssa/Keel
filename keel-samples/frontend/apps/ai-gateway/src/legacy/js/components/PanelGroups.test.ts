import { describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelGroups } from './PanelGroups.js';

describe('legacy groups panel', () => {
  it('shows alias names from saved alias routes in exposed models', () => {
    const panel = new PanelGroups();
    expect(
      panel._fallbackExposedModels('ALIASES_ONLY', ['claude-sonnet-4-6'], ['smart-claude'])
    ).toEqual(['smart-claude']);
  });

  it('combines saved aliases with direct models when exposure mode includes both', () => {
    const panel = new PanelGroups();
    expect(
      panel._fallbackExposedModels('ALIASES_AND_MODELS', ['claude-sonnet-4-6'], ['smart-claude'])
    ).toEqual(['smart-claude', 'claude-sonnet-4-6']);
  });
});
