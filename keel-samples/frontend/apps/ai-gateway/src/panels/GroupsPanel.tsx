import { useState, type FormEvent } from 'react';
import { Button, Card, DataTable, ErrorBanner, FormField } from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function GroupsPanel({ api }: { api: AiGatewayApi }) {
  const [error, setError] = useState<string | null>(null);

  return (
    <DataPanel
      title="Groups"
      detail="Manage routing groups and provider membership weights."
      load={() => api.groups()}
      actions={<Button type="submit" form="group-create-form">New group</Button>}
    >
      {(data, refresh) => {
        const groups = (data as { groups?: Array<{ groupId?: string; name?: string; aliases?: string[]; members?: unknown[] }> }).groups ?? [];

        async function createGroup(event: FormEvent<HTMLFormElement>) {
          event.preventDefault();
          setError(null);
          const form = new FormData(event.currentTarget);
          try {
            await api.createGroup({
              groupId: String(form.get('groupId') || '').trim() || undefined,
              name: String(form.get('name') || 'New group'),
              enabled: true
            });
            event.currentTarget.reset();
            refresh();
          } catch (err) {
            setError(err instanceof Error ? err.message : 'Unable to create group');
          }
        }

        async function attachMembership(event: FormEvent<HTMLFormElement>) {
          event.preventDefault();
          setError(null);
          const form = new FormData(event.currentTarget);
          try {
            await api.attachGroupMembership(String(form.get('group') || 'default'), {
              channelId: String(form.get('channelId') || ''),
              priority: Number(form.get('priority') || 0),
              weight: Number(form.get('weight') || 100),
              enabled: true
            });
            event.currentTarget.reset();
            refresh();
          } catch (err) {
            setError(err instanceof Error ? err.message : 'Unable to attach channel');
          }
        }

        return (
          <Card>
            {error ? <ErrorBanner message={error} /> : null}
            <form id="group-create-form" className="keel-inline-form" onSubmit={createGroup}>
              <FormField label="Group ID" name="groupId" placeholder="default" />
              <FormField label="Name" name="name" placeholder="Default routing" />
            </form>
            <form className="keel-inline-form" onSubmit={attachMembership}>
              <FormField label="Group" name="group" placeholder="default" />
              <FormField label="Channel ID" name="channelId" placeholder="ch_..." />
              <FormField label="Priority" name="priority" type="number" placeholder="0" />
              <FormField label="Weight" name="weight" type="number" placeholder="100" />
              <Button type="submit" variant="secondary">Attach</Button>
            </form>
            <DataTable
              headers={['ID', 'Name', 'Aliases', 'Members']}
              emptyText="No groups yet."
              rows={groups.map((row) => [row.groupId ?? 'unknown', row.name ?? 'unknown', (row.aliases ?? []).join(', ') || 'none', String(row.members?.length ?? 0)])}
            />
          </Card>
        );
      }}
    </DataPanel>
  );
}
