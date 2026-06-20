import { beforeEach, describe, expect, it, vi } from 'vitest';

const { requestJson, postJson, deleteJson, putJson } = vi.hoisted(() => ({
  requestJson: vi.fn(),
  postJson: vi.fn(),
  deleteJson: vi.fn(),
  putJson: vi.fn(),
}));

vi.mock('../api.js', () => ({
  requestJson,
  postJson,
  deleteJson,
  putJson,
}));

// @ts-ignore legacy custom element module has no TypeScript declarations
import './shared/KeelHero.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './PanelGroups.js';

describe('legacy groups admin actions', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    requestJson.mockReset();
    postJson.mockReset();
    deleteJson.mockReset();
    putJson.mockReset();
    window.confirm = vi.fn(() => true);
  });

  it('renders membership attach failures inline on the group card', async () => {
    postJson.mockRejectedValue(new Error('Attach failed'));

    const panel = document.createElement('ai-panel-groups') as any;
    document.body.appendChild(panel);
    panel._groups = [{ groupId: 'premium', name: 'Premium', description: '', enabled: true, exposureMode: 'ALL_MODELS', aliasRoutes: [] }];
    panel._channels = [{ channelId: 'ch-1', name: 'OpenAI Main', protocol: 'openai', memberships: [], models: [], enabled: true }];
    panel._chains = [];

    panel._render();
    await panel._attachMembership('premium');

    expect(panel.shadowRoot.textContent).toContain('Attach failed');
  });
});
