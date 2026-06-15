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

interface ApiKey {
  keyId?: string;
  name?: string;
  owner?: string;
  prefix?: string;
  createdAt?: string;
  lastUsedAt?: string;
  status?: string;
}

function keyStatusTone(s: string | undefined): 'ok' | 'warn' | 'danger' | 'muted' {
  switch ((s ?? '').toLowerCase()) {
    case 'active':
    case 'ok':
      return 'ok';
    case 'idle':
      return 'warn';
    case 'revoked':
    case 'disabled':
      return 'danger';
    default:
      return 'muted';
  }
}

function formatRelative(iso?: string): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  const diff = Date.now() - t;
  const m = 60_000, h = 3_600_000, d = 86_400_000;
  if (diff < m) return 'just now';
  if (diff < h) return `${Math.floor(diff / m)}m ago`;
  if (diff < d) return `${Math.floor(diff / h)}h ago`;
  return `${Math.floor(diff / d)}d ago`;
}

export function KeysPanel({ api }: { api: AiGatewayApi }) {
  const [rows, setRows] = useState<ApiKey[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.keys()
      .then((data) => {
        if (cancelled) return;
        setRows(((data as { keys?: ApiKey[] }).keys ?? []) as ApiKey[]);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load API keys');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const columns: DataTableColumn<ApiKey>[] = [
    { key: 'name', header: 'Name' },
    {
      key: 'prefix',
      header: 'Prefix',
      mono: true,
      render: (r) => (r.prefix ? `${r.prefix}…` : '—')
    },
    { key: 'owner', header: 'Owner' },
    { key: 'createdAt', header: 'Created', render: (r) => (r.createdAt ? r.createdAt.replace('T', ' ').slice(0, 19) : '—') },
    { key: 'lastUsedAt', header: 'Last used', render: (r) => formatRelative(r.lastUsedAt) },
    {
      key: 'status',
      header: 'Status',
      render: (r) => <Chip tone={keyStatusTone(r.status)}>{r.status ?? 'active'}</Chip>
    }
  ];

  return (
    <>
      <PageHeader
        title="API Keys"
        description="Virtual tokens issued to your customers."
        actions={
          <Button variant="primary" size="sm" disabled title="Backend not yet exposed">
            + Create key
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No API keys"
          detail="Create a key to let a customer call the AI Relay."
          icon="vpn_key"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => r.keyId ?? r.name ?? `row-${i}`}
          maxHeight="calc(100vh - 220px)"
          actionsColumn={() => (
            <>
              <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                Regenerate
              </Button>
              <Button variant="danger" size="sm" disabled title="Backend not yet exposed">
                Revoke
              </Button>
            </>
          )}
        />
      )}
    </>
  );
}
