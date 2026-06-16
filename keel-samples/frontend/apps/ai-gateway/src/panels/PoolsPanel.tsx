import { useEffect, useState } from 'react';
import {
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  PageHeader
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface Pool {
  chainId?: string;
  modelAliases?: string[];
  levels?: Array<{
    levelId?: string;
    protocol?: string;
    providerId?: string;
    keys?: Array<{
      keyId?: string;
      status?: string;
      totalRequests?: number;
      totalFailures?: number;
      currentConcurrency?: number;
      maxConcurrency?: number;
      lastError?: string;
    }>;
  }>;
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
    {
      key: 'models',
      header: 'Models',
      render: (r) => (r.modelAliases ?? []).length ? (r.modelAliases ?? []).join(', ') : '—'
    },
    {
      key: 'levels',
      header: 'Levels',
      render: (r) => (
        <div style={{ display: 'grid', gap: 6 }}>
          {(r.levels ?? []).map((level, index) => (
            <div key={`${level.levelId ?? 'level'}-${index}`} style={{ display: 'grid', gap: 4 }}>
              <span>
                <strong>{level.levelId ?? '—'}</strong>
                {' · '}
                <span style={{ color: 'var(--keel-muted)' }}>{level.protocol ?? '—'}</span>
              </span>
              {(level.keys ?? []).map((key, keyIndex) => (
                <span key={`${key.keyId ?? 'key'}-${keyIndex}`} style={{ fontSize: 12, color: 'var(--keel-muted)' }}>
                  {key.keyId ?? '—'} · {key.status ?? '—'} · cc {key.currentConcurrency ?? 0}/{key.maxConcurrency ?? 0}
                  {(key.totalFailures ?? 0) > 0 ? ` · fail ${key.totalFailures}` : ''}
                  {key.lastError ? ` · ${key.lastError}` : ''}
                </span>
              ))}
            </div>
          ))}
        </div>
      )
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
        />
      )}
    </>
  );
}
