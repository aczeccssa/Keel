import { useState, type FormEvent } from 'react';
import { Button, Card, DataTable, ErrorBanner, FormField } from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';
import { DataPanel } from './DataPanel';

export function PricingPanel({ api }: { api: AiGatewayApi }) {
  const [error, setError] = useState<string | null>(null);

  return (
    <DataPanel
      title="Pricing"
      detail="Maintain model cost multipliers for relay usage and customer billing."
      load={() => api.pricing()}
      actions={<Button type="submit" form="pricing-upsert-form">Add rate</Button>}
    >
      {(data, refresh) => {
        const pricing = (data as { pricing?: Array<{ model?: string; variantKey?: string; inputCostPerMTok?: number; outputCostPerMTok?: number; creditMultiplier?: number }> }).pricing ?? [];

        async function submit(event: FormEvent<HTMLFormElement>) {
          event.preventDefault();
          setError(null);
          const form = new FormData(event.currentTarget);
          try {
            await api.upsertPricing({
              model: String(form.get('model') || '').trim(),
              variantKey: String(form.get('variantKey') || '').trim() || undefined,
              inputCostPerMTok: Number(form.get('inputCostPerMTok') || 0),
              outputCostPerMTok: Number(form.get('outputCostPerMTok') || 0),
              creditMultiplier: Number(form.get('creditMultiplier') || 1)
            });
            event.currentTarget.reset();
            refresh();
          } catch (err) {
            setError(err instanceof Error ? err.message : 'Unable to save pricing');
          }
        }

        return (
          <Card>
            {error ? <ErrorBanner message={error} /> : null}
            <form id="pricing-upsert-form" className="keel-inline-form" onSubmit={submit}>
              <FormField label="Model" name="model" placeholder="gpt-4o-mini" />
              <FormField label="Variant" name="variantKey" placeholder="default" />
              <FormField label="Input / MTok" name="inputCostPerMTok" type="number" step="0.0001" placeholder="0.15" />
              <FormField label="Output / MTok" name="outputCostPerMTok" type="number" step="0.0001" placeholder="0.60" />
              <FormField label="Credit multiplier" name="creditMultiplier" type="number" step="0.01" placeholder="1" />
            </form>
            <DataTable
              headers={['Model', 'Variant', 'Input', 'Output', 'Credits']}
              emptyText="No pricing rows yet."
              rows={pricing.map((row) => [row.model ?? 'unknown', row.variantKey ?? 'default', row.inputCostPerMTok ?? 0, row.outputCostPerMTok ?? 0, row.creditMultiplier ?? 1])}
            />
          </Card>
        );
      }}
    </DataPanel>
  );
}
