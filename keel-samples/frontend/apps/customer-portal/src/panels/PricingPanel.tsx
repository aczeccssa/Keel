import { useEffect, useState } from 'react';
import {
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  PageHeader
} from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

interface Pricing {
  model?: string;
  inputCostPerMTok?: number;
  outputCostPerMTok?: number;
  notes?: string;
}

export function PricingPanel({ api }: { api: CustomerPortalApi }) {
  const [rows, setRows] = useState<Pricing[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.pricing()
      .then((data) => {
        if (cancelled) return;
        setRows(((data as { pricing?: Pricing[] }).pricing ?? []) as Pricing[]);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load rates');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const columns: DataTableColumn<Pricing>[] = [
    { key: 'model', header: 'Model' },
    {
      key: 'input',
      header: 'Input ($/MTok)',
      align: 'right',
      mono: true,
      render: (r) => (r.inputCostPerMTok != null ? `$${r.inputCostPerMTok.toFixed(3)}` : '—')
    },
    {
      key: 'output',
      header: 'Output ($/MTok)',
      align: 'right',
      mono: true,
      render: (r) => (r.outputCostPerMTok != null ? `$${r.outputCostPerMTok.toFixed(3)}` : '—')
    },
    { key: 'notes', header: 'Notes' }
  ];

  return (
    <>
      <PageHeader title="Rates" description="Model pricing for the AI Relay." />
      {error ? <ErrorBanner message={error} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No pricing rows yet"
          detail="Pricing will appear here once configured by your provider."
          icon="attach_money"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => r.model ?? `row-${i}`}
          maxHeight="calc(100vh - 220px)"
        />
      )}
    </>
  );
}
