import { useEffect, useState } from 'react';
import { Card, DataTable, ErrorBanner } from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

export function PricingPanel({ api }: { api: CustomerPortalApi }) {
  const [rows, setRows] = useState<Array<Array<string>>>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.pricing()
      .then((data) => {
        const prices = (data as { pricing?: Array<{ model?: string; inputCostPerMTok?: number; outputCostPerMTok?: number }> }).pricing ?? [];
        setRows(prices.map((price) => [price.model ?? '—', String(price.inputCostPerMTok ?? '—'), String(price.outputCostPerMTok ?? '—')]));
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Unable to load pricing'));
  }, [api]);

  return (
    <Card>
      <h1>Rates</h1>
      {error ? <ErrorBanner message={error} /> : null}
      <DataTable headers={['Model', 'Input / MTok', 'Output / MTok']} rows={rows} emptyText="No pricing rows yet." />
    </Card>
  );
}
