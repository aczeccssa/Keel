import { useState, type FormEvent } from 'react';
import { Button, Card, DataTable, ErrorBanner, FormField } from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function ProvidersPanel({ api }: { api: AiGatewayApi }) {
  const [error, setError] = useState<string | null>(null);

  return (
    <DataPanel
      title="Channels"
      detail="Configure upstream providers, health checks, and public model exposure."
      load={() => api.channels()}
      actions={<Button type="submit" form="channel-create-form">Add channel</Button>}
    >
      {(data, refresh) => {
        const channels = (data as { channels?: Array<{ channelId?: string; name?: string; protocol?: string; baseUrl?: string; enabled?: boolean }> }).channels ?? [];

        async function submit(event: FormEvent<HTMLFormElement>) {
          event.preventDefault();
          setError(null);
          const form = new FormData(event.currentTarget);
          try {
            await api.createChannel({
              name: String(form.get('name') || 'New channel'),
              protocol: String(form.get('protocol') || 'OPENAI_CHAT'),
              baseUrl: String(form.get('baseUrl') || 'mock://provider'),
              apiKey: String(form.get('apiKey') || ''),
              groupId: String(form.get('groupId') || 'default'),
              enabled: true,
              models: [{ publicModelName: String(form.get('model') || 'gpt-4o-mini'), enabled: true }]
            });
            event.currentTarget.reset();
            refresh();
          } catch (err) {
            setError(err instanceof Error ? err.message : 'Unable to create channel');
          }
        }

        return (
          <Card>
            {error ? <ErrorBanner message={error} /> : null}
            <form id="channel-create-form" className="keel-inline-form" onSubmit={submit}>
              <FormField label="Name" name="name" placeholder="Primary OpenAI" />
              <FormField label="Protocol" name="protocol" placeholder="OPENAI_CHAT" />
              <FormField label="Base URL" name="baseUrl" placeholder="https://api.openai.com/v1" />
              <FormField label="API key" name="apiKey" placeholder="sk-..." />
              <FormField label="Group" name="groupId" placeholder="default" />
              <FormField label="Model" name="model" placeholder="gpt-4o-mini" />
            </form>
            <DataTable
              headers={['Name', 'Protocol', 'Base URL', 'Status']}
              emptyText="No channels yet."
              rows={channels.map((row) => [row.name ?? 'unknown', row.protocol ?? 'unknown', row.baseUrl ?? 'unknown', row.enabled === false ? 'Off' : 'On'])}
              rowActions={(index) => (
                <Button type="button" size="sm" variant="secondary" onClick={() => channels[index]?.channelId && void api.testChannel(channels[index].channelId!)}>
                  Test
                </Button>
              )}
            />
          </Card>
        );
      }}
    </DataPanel>
  );
}
