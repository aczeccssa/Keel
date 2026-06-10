import { useState, type FormEvent } from 'react';
import { Button, Card, DataTable, ErrorBanner, FormField } from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function RateLimitsPanel({ api }: { api: AiGatewayApi }) {
  const [error, setError] = useState<string | null>(null);

  return (
    <DataPanel
      title="Rate Limits"
      detail="Define token bucket controls for keys, customers, models, and routes."
      load={() => api.rateLimitRules()}
      actions={<Button type="submit" form="rate-limit-create-form">New rule</Button>}
    >
      {(data, refresh) => {
        const rules = (data as { rules?: Array<{ ruleId?: string; name?: string; dimension?: string; capacity?: number; refillRatePerSec?: number; priority?: number }> }).rules ?? [];

        async function submit(event: FormEvent<HTMLFormElement>) {
          event.preventDefault();
          setError(null);
          const form = new FormData(event.currentTarget);
          try {
            await api.createRateLimitRule({
              ruleId: String(form.get('ruleId') || '').trim() || undefined,
              name: String(form.get('name') || 'New rule'),
              dimension: String(form.get('dimension') || 'API_KEY'),
              capacity: Number(form.get('capacity') || 60),
              refillRatePerSec: Number(form.get('refillRatePerSec') || 1),
              priority: Number(form.get('priority') || 10)
            });
            event.currentTarget.reset();
            refresh();
          } catch (err) {
            setError(err instanceof Error ? err.message : 'Unable to create rate limit rule');
          }
        }

        return (
          <Card>
            {error ? <ErrorBanner message={error} /> : null}
            <form id="rate-limit-create-form" className="keel-inline-form" onSubmit={submit}>
              <FormField label="Rule ID" name="ruleId" placeholder="default-key-limit" />
              <FormField label="Name" name="name" placeholder="Default key limit" />
              <FormField label="Dimension" name="dimension" placeholder="API_KEY" />
              <FormField label="Capacity" name="capacity" type="number" placeholder="60" />
              <FormField label="Refill / sec" name="refillRatePerSec" type="number" step="0.1" placeholder="1" />
              <FormField label="Priority" name="priority" type="number" placeholder="10" />
            </form>
            <DataTable
              headers={['ID', 'Name', 'Dimension', 'Capacity', 'Refill', 'Priority']}
              emptyText="No rate limit rules yet."
              rows={rules.map((row) => [row.ruleId ?? 'unknown', row.name ?? 'unknown', row.dimension ?? 'unknown', row.capacity ?? 0, row.refillRatePerSec ?? 0, row.priority ?? 0])}
            />
          </Card>
        );
      }}
    </DataPanel>
  );
}
