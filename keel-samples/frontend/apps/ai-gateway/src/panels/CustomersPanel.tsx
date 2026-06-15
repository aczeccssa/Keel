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

interface Customer {
  customerId?: string;
  email?: string;
  credits?: number;
  balance?: number;
  status?: string;
  createdAt?: string;
  lastActivityAt?: string;
}

function statusTone(s: string | undefined): 'ok' | 'warn' | 'danger' | 'muted' {
  switch ((s ?? '').toLowerCase()) {
    case 'active':
      return 'ok';
    case 'invited':
    case 'pending':
      return 'warn';
    case 'disabled':
    case 'banned':
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

export function CustomersPanel({ api }: { api: AiGatewayApi }) {
  const [rows, setRows] = useState<Customer[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.customers()
      .then((data) => {
        if (cancelled) return;
        setRows(((data as { customers?: Customer[] }).customers ?? []) as Customer[]);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load customers');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const columns: DataTableColumn<Customer>[] = [
    { key: 'email', header: 'Email' },
    {
      key: 'balance',
      header: 'Balance',
      align: 'right',
      mono: true,
      render: (r) => (r.credits ?? r.balance ?? 0).toLocaleString()
    },
    {
      key: 'status',
      header: 'Status',
      render: (r) => <Chip tone={statusTone(r.status)}>{r.status ?? '—'}</Chip>
    },
    { key: 'createdAt', header: 'Created', render: (r) => (r.createdAt ? r.createdAt.slice(0, 10) : '—') },
    { key: 'lastActivityAt', header: 'Last activity', render: (r) => formatRelative(r.lastActivityAt) }
  ];

  return (
    <>
      <PageHeader title="Customers" description="End-customer accounts and their balances." />
      {error ? <ErrorBanner message={error} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No customers yet"
          detail="Customers appear here once they register through the Customer Portal."
          icon="group"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => r.customerId ?? r.email ?? `row-${i}`}
          maxHeight="calc(100vh - 220px)"
          actionsColumn={() => (
            <>
              <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                Edit
              </Button>
              <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                Top up
              </Button>
              <Button variant="danger" size="sm" disabled title="Backend delete not yet exposed">
                Delete
              </Button>
            </>
          )}
        />
      )}
    </>
  );
}
