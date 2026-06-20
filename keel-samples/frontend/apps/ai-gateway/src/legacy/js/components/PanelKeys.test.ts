import { beforeEach, describe, expect, it, vi } from 'vitest';

const { requestJson, postJson, deleteJson, copyText } = vi.hoisted(() => ({
  requestJson: vi.fn(),
  postJson: vi.fn(),
  deleteJson: vi.fn(),
  copyText: vi.fn(),
}));

vi.mock('../api.js', () => ({
  requestJson,
  postJson,
  deleteJson,
}));

vi.mock('../utils.js', async () => {
  const actual = await vi.importActual('../utils.js');
  return {
    ...actual,
    copyText,
  };
});

// @ts-ignore legacy custom element module has no TypeScript declarations
import './shared/KeelHero.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './shared/KeelDataTable.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './PanelKeys.js';

describe('legacy keys panel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    requestJson.mockReset();
    postJson.mockReset();
    deleteJson.mockReset();
    copyText.mockReset();
    copyText.mockResolvedValue(true);
    window.confirm = vi.fn(() => true);
  });

  it('deletes through the admin endpoint and renders inline failures', async () => {
    requestJson.mockImplementation(async (url: string) => {
      if (url.endsWith('/admin/keys')) {
        return {
          keys: [{
            keyId: 'key-1',
            displayName: 'Primary',
            groupId: 'default',
            userId: 'usr-1',
            status: 'active',
            maxBudgetUsd: 10,
            currentSpendUsd: 1.25,
            remainingBudgetUsd: 8.75,
          }],
        };
      }
      if (url.endsWith('/admin/groups')) return { groups: [] };
      throw new Error(`Unexpected request ${url}`);
    });
    deleteJson.mockRejectedValue(new Error('Delete failed'));

    const panel = document.createElement('ai-panel-keys') as any;
    document.body.appendChild(panel);

    await panel.refresh();

    const btn = panel.refs.table.shadowRoot.querySelector('[data-delete-key="key-1"]');
    btn.click();
    await Promise.resolve();
    await Promise.resolve();

    expect(deleteJson).toHaveBeenCalledWith('/api/plugins/token/admin/keys/key-1');
    expect(panel.shadowRoot.textContent).toContain('Delete failed');
  });

  it('shows non-recoverable setup copy for older keys', () => {
    const panel = document.createElement('ai-panel-keys') as any;
    document.body.appendChild(panel);

    panel._openModal('key-existing');

    expect(panel.refs.modalKeyVal.textContent).toContain('secret shown once at creation');
    expect(panel.refs.modalKeyCopy.disabled).toBe(true);
  });

  it('revokes through the admin revoke endpoint', async () => {
    requestJson.mockImplementation(async (url: string) => {
      if (url.endsWith('/admin/keys')) {
        return {
          keys: [{
            keyId: 'key-1',
            displayName: 'Primary',
            groupId: 'default',
            userId: 'usr-1',
            status: 'active',
            maxBudgetUsd: 10,
            currentSpendUsd: 1.25,
            remainingBudgetUsd: 8.75,
          }],
        };
      }
      if (url.endsWith('/admin/groups')) return { groups: [] };
      throw new Error(`Unexpected request ${url}`);
    });

    const panel = document.createElement('ai-panel-keys') as any;
    document.body.appendChild(panel);

    await panel.refresh();

    const revokeButton = panel.refs.table.shadowRoot.querySelector('[data-revoke-key="key-1"]');
    revokeButton.click();
    await Promise.resolve();
    await Promise.resolve();

    expect(postJson).toHaveBeenCalledWith('/api/plugins/token/admin/keys/key-1/revoke', {});
  });

  it('renders inline copy failure instead of alert fallback', async () => {
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});

    const panel = document.createElement('ai-panel-keys') as any;
    document.body.appendChild(panel);
    panel.refs.keyValue.textContent = 'sk-test';
    copyText.mockResolvedValue(false);

    panel.refs.copyKeyBtn.click();
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(copyText).toHaveBeenCalledWith('sk-test');
    expect(alertSpy).not.toHaveBeenCalled();
    expect(panel.refs.statusNote.textContent).toContain('Copy failed. The generated key is still visible above.');

    alertSpy.mockRestore();
  });
});
