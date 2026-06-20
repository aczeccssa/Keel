import { beforeEach, describe, expect, it, vi } from 'vitest';

const { requestJson, putJson, postJson, deleteJson } = vi.hoisted(() => ({
  requestJson: vi.fn(),
  putJson: vi.fn(),
  postJson: vi.fn(),
  deleteJson: vi.fn(),
}));

vi.mock('../api.js', () => ({
  requestJson,
  putJson,
  postJson,
  deleteJson,
}));

// @ts-ignore legacy custom element module has no TypeScript declarations
import './shared/KeelHero.js';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './PanelCustomers.js';

describe('legacy customers admin actions', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    requestJson.mockReset();
    putJson.mockReset();
    postJson.mockReset();
    deleteJson.mockReset();
    window.confirm = vi.fn(() => true);
  });

  it('calls the admin profile, credit, revoke, and delete endpoints', async () => {
    const panel = document.createElement('ai-panel-customers') as any;
    document.body.appendChild(panel);

    panel._selected = { customerId: 'cust-1', email: 'cust@example.com' };
    panel._openModal = vi.fn();
    panel.refresh = vi.fn();
    panel._closeModal = vi.fn();

    panel.refs.displayName.value = 'Renamed Customer';
    panel.refs.status.value = 'suspended';
    await panel._saveProfile();

    expect(putJson).toHaveBeenCalledWith('/api/plugins/customer-portal/admin/customers/cust-1', {
      displayName: 'Renamed Customer',
      status: 'locked',
    });

    panel.refs.creditDelta.value = '250';
    panel.refs.creditReason.value = 'manual_topup';
    await panel._adjustCredits();

    expect(postJson).toHaveBeenCalledWith('/api/plugins/customer-portal/admin/customers/cust-1/credits', {
      deltaCredits: 250,
      reason: 'manual_topup',
    });

    await panel._revokeKey('ckey-1');
    expect(deleteJson).toHaveBeenCalledWith('/api/plugins/customer-portal/admin/customers/cust-1/keys/ckey-1');

    await panel._deleteCustomer();
    expect(deleteJson).toHaveBeenCalledWith('/api/plugins/customer-portal/admin/customers/cust-1');
  });

  it('renders an inline panel error when detail loading fails', async () => {
    requestJson.mockRejectedValue(new Error('Load failed'));

    const panel = document.createElement('ai-panel-customers') as any;
    document.body.appendChild(panel);

    await panel._openModal('cust-404');

    expect(panel.shadowRoot.textContent).toContain('Load failed');
  });
});
