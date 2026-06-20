import { beforeEach, describe, expect, it, vi } from 'vitest';

const { requestJson, postJson, putJson } = vi.hoisted(() => ({
  requestJson: vi.fn(),
  postJson: vi.fn(),
  putJson: vi.fn(),
}));

vi.mock('../api.js', () => ({
  requestJson,
  postJson,
  putJson,
}));

// @ts-ignore legacy custom element module has no TypeScript declarations
import './shared/KeelHero.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './shared/KeelDataTable.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './PanelUsers.js';

describe('legacy users panel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    requestJson.mockReset();
    postJson.mockReset();
    putJson.mockReset();
  });

  it('updates account role/group/status through the admin user API', async () => {
    requestJson.mockImplementation(async (url: string) => {
      if (url.endsWith('/admin/users')) {
        return {
          users: [{
            userId: 'usr-1',
            email: 'user@example.com',
            displayName: 'User One',
            role: 'user',
            groupId: 'free',
            status: 'active',
          }],
        };
      }
      if (url.endsWith('/admin/groups')) {
        return {
          groups: [
            { groupId: 'free', name: 'Free', costMultiplier: 1, defaultBudgetUsd: 10 },
            { groupId: 'pro', name: 'Pro', costMultiplier: 1, defaultBudgetUsd: 100 },
          ],
        };
      }
      throw new Error(`Unexpected request ${url}`);
    });
    putJson.mockRejectedValue(new Error('Update failed'));

    const panel = document.createElement('ai-panel-users') as any;
    document.body.appendChild(panel);

    await panel.refresh();

    panel.refs.usersTable.shadowRoot.querySelector('[data-user-role="usr-1"]').value = 'admin';
    panel.refs.usersTable.shadowRoot.querySelector('[data-user-group="usr-1"]').value = 'pro';
    panel.refs.usersTable.shadowRoot.querySelector('[data-user-status="usr-1"]').value = 'suspended';
    panel.refs.usersTable.shadowRoot.querySelector('[data-save-user="usr-1"]').click();
    await Promise.resolve();
    await Promise.resolve();

    expect(putJson).toHaveBeenCalledWith('/api/plugins/account/admin/users/usr-1', {
      role: 'admin',
      groupId: 'pro',
      status: 'suspended',
    });
    expect(panel.shadowRoot.textContent).toContain('Update failed');
  });
});
