import { useEffect, useState } from 'react';
import {
  Button,
  Chip,
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  PageHeader
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface Pricing {
  model?: string;
  inputCostPerMTok?: number;
  outputCostPerMTok?: number;
  effectiveFrom?: string;
  status?: string;
}

export function PricingPanel({ api }: { api: AiGatewayApi }) {
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
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load pricing');
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
    {
      key: 'effective',
      header: 'Effective',
      render: (r) => (r.effectiveFrom ? r.effectiveFrom.slice(0, 10) : '—')
    },
    {
      key: 'status',
      header: 'Status',
      render: (r) => <Chip tone={r.status === 'archived' ? 'muted' : 'ok'}>{r.status ?? 'active'}</Chip>
    }
  ];

  return (
    <>
      <PageHeader
        title="Pricing"
        description="Model rate cards."
        actions={
          <Button variant="primary" size="sm" disabled title="Backend not yet exposed">
            + New rate
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No pricing rows yet"
          detail="Add a model rate card to start charging customers."
          icon="attach_money"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => r.model ?? `row-${i}`}
          maxHeight="calc(100vh - 220px)"
          actionsColumn={() => (
            <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
              Edit
            </Button>
          )}
        />
      )}
    </>
  );
}
