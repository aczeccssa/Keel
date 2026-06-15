import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { GroupsPanel } from './GroupsPanel';

describe('GroupsPanel', () => {
  it('uses compact widths for priority and weight columns', async () => {
    const api = {
      groups: async () => ({
        groups: [{ groupId: 'core', name: 'Core' }]
      }),
      channels: async () => ({
        channels: [
          {
            channelId: 'c1',
            name: 'Primary',
            protocol: 'openai',
            enabled: true,
            memberships: [{ groupId: 'core', priority: 10, weight: 100, enabled: true }]
          }
        ]
      })
    };

    render(<GroupsPanel api={api as never} />);

    await screen.findByText('Primary');

    expect(screen.getByRole('columnheader', { name: 'Priority' })).toHaveStyle({ width: '72px' });
    expect(screen.getByRole('columnheader', { name: 'Weight' })).toHaveStyle({ width: '72px' });
  });
});
