import { useEffect, useState } from 'react';
import {
  Button,
  Chip,
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  IconButton,
  PageHeader
} from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

interface KeyRow {
  keyId: string;
  name: string;
  prefix?: string;
  createdAt?: string;
  lastUsedAt?: string;
  routingGroupId?: string;
}

function formatRelative(iso?: string): string {
  if (!iso) return 'Never';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  const diff = Date.now() - t;
  const m = 60_000, h = 3_600_000, d = 86_400_000;
  if (diff < m) return 'just now';
  if (diff < h) return `${Math.floor(diff / m)}m ago`;
  if (diff < d) return `${Math.floor(diff / h)}h ago`;
  return `${Math.floor(diff / d)}d ago`;
}

export function KeysPanel({ api }: { api: CustomerPortalApi }) {
  const [rows, setRows] = useState<KeyRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [latestRaw, setLatestRaw] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function refresh() {
    setError(null);
    api.keys()
      .then((data) => {
        setRows(((data as { keys?: KeyRow[] }).keys ?? []) as KeyRow[]);
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Unable to load API keys'));
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function createKey() {
    setBusy(true);
    setError(null);
    try {
      const result = (await api.createKey({ name: 'Default key' })) as { rawKey?: string; key?: KeyRow };
      if (result?.rawKey) setLatestRaw(result.rawKey);
      refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to create key');
    } finally {
      setBusy(false);
    }
  }

  async function deleteKey(keyId: string) {
    if (!confirm('Delete this API key? This cannot be undone.')) return;
    setError(null);
    try {
      await api.deleteKey(keyId);
      setRows((cur) => cur.filter((k) => k.keyId !== keyId));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Delete failed');
    }
  }

  const columns: DataTableColumn<KeyRow>[] = [
    { key: 'name', header: 'Name' },
    {
      key: 'prefix',
      header: 'Prefix',
      mono: true,
      render: (r) => (r.prefix ? `${r.prefix}…` : '—')
    },
    { key: 'createdAt', header: 'Created', render: (r) => (r.createdAt ? r.createdAt.replace('T', ' ').slice(0, 19) : '—') },
    { key: 'lastUsedAt', header: 'Last used', render: (r) => formatRelative(r.lastUsedAt) },
    {
      key: 'routingGroupId',
      header: 'Routing group',
      render: (r) =>
        r.routingGroupId ? <Chip tone="accent">{r.routingGroupId}</Chip> : <Chip tone="muted">default</Chip>
    }
  ];

  return (
    <>
      <PageHeader
        title="API Keys"
        description="Tokens you can use to call the AI Relay."
        actions={
          <Button variant="primary" size="sm" onClick={createKey} disabled={busy}>
            {busy ? 'Creating…' : '+ Create key'}
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} onRetry={refresh} /> : null}
      {latestRaw ? (
        <div
          className="keel-error-banner"
          style={{ borderLeftColor: 'var(--keel-ok)', background: 'var(--keel-ok-soft)' }}
        >
          <span className="material-symbols-outlined" style={{ color: 'var(--keel-ok)' }}>vpn_key</span>
          <span style={{ flex: 1 }}>
            Copy your new key now — you will not see it again: <code className="keel-mono">{latestRaw}</code>
          </span>
          <span className="keel-error-banner__actions">
            <Button
              variant="secondary"
              size="sm"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(latestRaw);
                } catch {
                  /* clipboard might be unavailable */
                }
              }}
            >
              Copy
            </Button>
            <IconButton ariaLabel="Dismiss" size="sm" onClick={() => setLatestRaw(null)}>
              <span className="material-symbols-outlined">close</span>
            </IconButton>
          </span>
        </div>
      ) : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No API keys"
          detail="Create your first key to start calling the AI Relay."
          icon="vpn_key"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r) => r.keyId}
          maxHeight="calc(100vh - 260px)"
          actionsColumn={(r) => (
            <Button variant="danger" size="sm" onClick={() => deleteKey(r.keyId)}>
              Delete
            </Button>
          )}
        />
      )}
    </>
  );
}
