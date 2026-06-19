import { useEffect, useState } from 'react';
import {
  Chip,
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  PageHeader
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface Group {
  groupId?: string;
}

interface PoolChannel {
  channelId?: string;
  channelName?: string;
  effectiveStatus?: string;
  weight?: number;
  currentConcurrency?: number;
  maxConcurrency?: number;
  totalRequests?: number;
  totalFailures?: number;
  lastError?: string | null;
}

interface Pool {
  groupId?: string;
  aliasOrModel?: string;
  routingPolicy?: string;
  priority?: number;
  totalInflight?: number;
  channels?: PoolChannel[];
}

function statusTone(status: string | undefined): 'ok' | 'warn' | 'danger' | 'muted' {
  switch ((status ?? '').toUpperCase()) {
    case 'HEALTHY':
      return 'ok';
    case 'SATURATED':
    case 'COOLDOWN':
      return 'warn';
    case 'DISABLED':
    case 'DEGRADED':
      return 'danger';
    default:
      return 'muted';
  }
}

export function PoolsPanel({ api }: { api: AiGatewayApi }) {
  const [rows, setRows] = useState<Pool[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;

    const load = async () => {
      if (document.visibilityState === 'hidden') {
        timer = window.setTimeout(load, 5000);
        return;
      }
      try {
        const groupsData = await api.groups();
        const groups = ((groupsData as { groups?: Group[] }).groups ?? []) as Group[];
        const pools = await Promise.all(
          groups
            .map((group) => group.groupId)
            .filter((groupId): groupId is string => !!groupId)
            .map(async (groupId) => {
              const response = await api.groupPools(groupId);
              return ((response as { pools?: Pool[] }).pools ?? []) as Pool[];
            })
        );
        if (!cancelled) {
          setRows(pools.flat());
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Unable to load pools');
        }
      } finally {
        timer = window.setTimeout(load, 5000);
      }
    };

    void load();
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [api]);

  const columns: DataTableColumn<Pool>[] = [
    { key: 'groupId', header: 'Group', mono: true, render: (r) => r.groupId ?? '—' },
    { key: 'aliasOrModel', header: 'Alias / Model', mono: true, render: (r) => r.aliasOrModel ?? '—' },
    {
      key: 'routingPolicy',
      header: 'Policy',
      render: (r) => <Chip tone={r.routingPolicy === 'POOL_BALANCE' ? 'ok' : 'warn'}>{r.routingPolicy ?? '—'}</Chip>
    },
    {
      key: 'priority',
      header: 'Priority',
      width: '88px',
      align: 'right',
      mono: true,
      render: (r) => String(r.priority ?? 0)
    },
    {
      key: 'channels',
      header: 'Channels',
      render: (r) => (
        <div style={{ display: 'grid', gap: 6 }}>
          {(r.channels ?? []).map((channel, index) => (
            <div key={`${channel.channelId ?? 'channel'}-${index}`} style={{ display: 'grid', gap: 4 }}>
              <span>
                <strong>{channel.channelName ?? channel.channelId ?? '—'}</strong>
                {' · '}
                <Chip tone={statusTone(channel.effectiveStatus)}>{channel.effectiveStatus ?? 'UNKNOWN'}</Chip>
              </span>
              <span style={{ fontSize: 12, color: 'var(--keel-muted)' }}>
                {channel.channelId ?? '—'} · weight {channel.weight ?? 0} · inflight {channel.currentConcurrency ?? 0}/{channel.maxConcurrency ?? 0}
                {' · '}requests {channel.totalRequests ?? 0}
                {(channel.totalFailures ?? 0) > 0 ? ` · fail ${channel.totalFailures}` : ''}
                {channel.lastError ? ` · ${channel.lastError}` : ''}
              </span>
            </div>
          ))}
        </div>
      )
    }
  ];

  return (
    <>
      <PageHeader title="Pools" description="Runtime pool selection state grouped by routing group, alias/model, and priority tier." />
      {error ? <ErrorBanner message={error} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No pool data"
          detail="Routing pools will appear here once a routing group has channels or aliases."
          icon="account_tree"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => `${r.groupId ?? 'group'}:${r.aliasOrModel ?? 'pool'}:${r.priority ?? i}`}
          maxHeight="calc(100vh - 220px)"
        />
      )}
    </>
  );
}
