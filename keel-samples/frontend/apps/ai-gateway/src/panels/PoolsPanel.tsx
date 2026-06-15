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

interface Pool {
  chainId?: string;
  levels?: unknown[];
  status?: string;
  lastRoutedAt?: string;
}

export function PoolsPanel({ api }: { api: AiGatewayApi }) {
  const [rows, setRows] = useState<Pool[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.pools()
      .then((data) => {
        if (cancelled) return;
        setRows(((data as { chains?: Pool[] }).chains ?? []) as Pool[]);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load pools');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const columns: DataTableColumn<Pool>[] = [
    { key: 'chainId', header: 'Chain', mono: true, render: (r) => r.chainId ?? '—' },
    { key: 'levels', header: 'Levels', align: 'right', mono: true, render: (r) => String(r.levels?.length ?? 0) },
    {
      key: 'status',
      header: 'Status',
      render: (r) => <Chip tone={(r.status ?? 'active') === 'active' ? 'ok' : 'muted'}>{r.status ?? 'active'}</Chip>
    },
    {
      key: 'lastRoutedAt',
      header: 'Last routed',
      render: (r) => (r.lastRoutedAt ? r.lastRoutedAt.replace('T', ' ').slice(0, 19) : '—')
    }
  ];

  return (
    <>
      <PageHeader title="Pools" description="Routing chains and their levels." />
      {error ? <ErrorBanner message={error} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No pool data"
          detail="Routing chains will appear here once configured."
          icon="account_tree"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => r.chainId ?? `row-${i}`}
          maxHeight="calc(100vh - 220px)"
          actionsColumn={() => (
            <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
              Inspect
            </Button>
          )}
        />
      )}
    </>
  );
}
