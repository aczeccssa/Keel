import { describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { PanelAvailability } from './PanelAvailability.js';

describe('legacy availability panel', () => {
  it('shows unknown availability when a channel has no request samples', () => {
    const state = new PanelAvailability()._cardState(
      { enabled: true, status: 'HEALTHY', models: [] },
      { successRate7d: null, avgLatencyMs: 0, recentTests: [] }
    );

    expect(state.availability).toBe('—');
    expect(state.availabilityPct).toBe('');
  });

  it('uses runtime pool status over persisted channel status', () => {
    const state = new PanelAvailability()._cardState(
      { enabled: true, status: 'HEALTHY', models: [] },
      { successRate7d: 1, avgLatencyMs: 12, recentTests: [] },
      { status: 'COOLDOWN' }
    );

    expect(state.statusLabel).toBe('Cooldown');
    expect(state.statusClass).toBe('warn');
    expect(state.signalText).toBe('ERR');
  });

  it('lists every enabled model on the channel card', () => {
    const state = new PanelAvailability()._cardState(
      {
        enabled: true,
        status: 'HEALTHY',
        models: [
          { publicModelName: 'gpt-4o', enabled: true },
          { publicModelName: 'claude-sonnet', enabled: true },
          { publicModelName: 'disabled-model', enabled: false },
        ],
      },
      { successRate7d: 1, avgLatencyMs: 12, recentTests: [] }
    );

    expect(state.model).toBe('gpt-4o, claude-sonnet');
    expect(state.extraModels).toBe('');
  });

  it('keeps the most severe runtime status when a channel appears in multiple pools', () => {
    const runtime = new PanelAvailability()._runtimeStatusByChannel({
      chains: [
        { chainId: 'critical', levels: [{ keys: [{ keyId: 'ch-1', status: 'COOLDOWN' }] }] },
        { chainId: 'healthy', levels: [{ keys: [{ keyId: 'ch-1', status: 'HEALTHY' }] }] },
      ],
    });

    expect(runtime.get('ch-1')?.status).toBe('COOLDOWN');
    expect(runtime.get('ch-1')?.chainId).toBe('critical');
  });

  it('offers a reset action when runtime disabled the channel', () => {
    const action = new PanelAvailability()._recoveryAction(
      { enabled: true, channelId: 'ch-1' },
      { status: 'DISABLED', chainId: 'group-a' }
    );

    expect(action).toEqual({
      visible: true,
      label: 'Unseal',
      channelId: 'ch-1',
      chainId: 'group-a',
    });
  });
});
