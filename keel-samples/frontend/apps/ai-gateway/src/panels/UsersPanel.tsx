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

interface User {
  userId?: string;
  email?: string;
  role?: string;
  status?: string;
  lastLoginAt?: string;
}

function roleTone(r: string | undefined): 'accent' | 'muted' | 'warn' {
  switch ((r ?? '').toLowerCase()) {
    case 'admin':
    case 'owner':
      return 'accent';
    case 'operator':
    case 'support':
      return 'warn';
    default:
      return 'muted';
  }
}

function statusTone(s: string | undefined): 'ok' | 'warn' | 'danger' | 'muted' {
  switch ((s ?? '').toLowerCase()) {
    case 'active':
    case 'enabled':
      return 'ok';
    case 'invited':
    case 'pending':
      return 'warn';
    case 'disabled':
    case 'revoked':
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

export function UsersPanel({ api }: { api: AiGatewayApi }) {
  const [rows, setRows] = useState<User[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.users()
      .then((data) => {
        if (cancelled) return;
        setRows(((data as { users?: User[] }).users ?? []) as User[]);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load users');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const columns: DataTableColumn<User>[] = [
    { key: 'email', header: 'Email' },
    { key: 'role', header: 'Role', render: (r) => <Chip tone={roleTone(r.role)}>{r.role ?? '—'}</Chip> },
    { key: 'status', header: 'Status', render: (r) => <Chip tone={statusTone(r.status)}>{r.status ?? '—'}</Chip> },
    { key: 'lastLoginAt', header: 'Last login', render: (r) => formatRelative(r.lastLoginAt) }
  ];

  return (
    <>
      <PageHeader
        title="Users"
        description="Admin and operator accounts."
        actions={
          <Button variant="primary" size="sm" disabled title="Backend not yet exposed">
            + Invite user
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No users"
          detail="Invite operators and admins to manage the AI Relay."
          icon="group"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => r.userId ?? r.email ?? `row-${i}`}
          maxHeight="calc(100vh - 220px)"
          actionsColumn={() => (
            <>
              <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                Edit
              </Button>
              <Button variant="danger" size="sm" disabled title="Backend not yet exposed">
                Disable
              </Button>
            </>
          )}
        />
      )}
    </>
  );
}
