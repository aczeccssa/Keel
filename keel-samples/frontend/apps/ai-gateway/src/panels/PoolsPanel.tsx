import { useEffect, useState } from 'react';
import {
  Button,
  Chip,
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  PageHeader,
  SectionHeader
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
  metrics1m?: WindowMetrics;
  metrics5m?: WindowMetrics;
  metrics15m?: WindowMetrics;
  trafficShare1m?: number;
  expectedShare?: number;
  shareDeviation?: number;
}

interface WindowMetrics {
  selectedRequests?: number;
  successRequests?: number;
  failedRequests?: number;
  errorRate?: number;
  p50LatencyMs?: number;
  p95LatencyMs?: number;
  p99LatencyMs?: number;
}

interface Pool {
  groupId?: string;
  aliasOrModel?: string;
  routingPolicy?: string;
  priority?: number;
  totalInflight?: number;
  metrics1m?: WindowMetrics;
  metrics5m?: WindowMetrics;
  metrics15m?: WindowMetrics;
  failoverCount1m?: number;
  channels?: PoolChannel[];
}

interface ExplainAttempt {
  channelId?: string;
  outcome?: string;
  status?: number | null;
  reason?: string | null;
  latencyMs?: number | null;
}

interface ExplainResult {
  selectedChannelId?: string | null;
  routingPolicy?: string;
  requestTrace?: {
    requestId?: string;
    outcome?: string;
    failoverCount?: number;
    attempts?: ExplainAttempt[];
  } | null;
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
  const [explain, setExplain] = useState<ExplainResult | null>(null);
  const [explaining, setExplaining] = useState(false);

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
        if (!cancelled) timer = window.setTimeout(load, 5000);
      }
    };

    void load();
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [api]);

  const explainPool = async (pool: Pool) => {
    if (!pool.groupId || !pool.aliasOrModel) return;
    setExplaining(true);
    try {
      const result = await api.explainGroupPool(pool.groupId, pool.aliasOrModel, {});
      setExplain(result as ExplainResult);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to explain pool selection');
    } finally {
      setExplaining(false);
    }
  };

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
      key: 'metrics1m',
      header: 'Runtime (1m)',
      render: (r) => (
        <div style={{ display: 'grid', gap: 3, fontSize: 12 }}>
          <span>{r.metrics1m?.selectedRequests ?? 0} req / 1m</span>
          <span>{((r.metrics1m?.errorRate ?? 0) * 100).toFixed(1)}% errors</span>
          <span>p95 {r.metrics1m?.p95LatencyMs ?? 0} ms</span>
          <span>{r.failoverCount1m ?? 0} failovers</span>
        </div>
      )
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
                {' · '}1m {channel.metrics1m?.selectedRequests ?? 0}
                {' · '}p95 {channel.metrics1m?.p95LatencyMs ?? 0} ms
                {(channel.totalFailures ?? 0) > 0 ? ` · fail ${channel.totalFailures}` : ''}
                {channel.lastError ? ` · ${channel.lastError}` : ''}
              </span>
              <span style={{ fontSize: 12, color: 'var(--keel-muted)' }}>
                share {((channel.trafficShare1m ?? 0) * 100).toFixed(1)}% / expected {((channel.expectedShare ?? 0) * 100).toFixed(1)}%
                {' · '}deviation {((channel.shareDeviation ?? 0) * 100).toFixed(1)}%
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
          actionsColumn={(row) => (
            <Button variant="secondary" size="sm" disabled={explaining} onClick={() => void explainPool(row)}>
              Explain
            </Button>
          )}
          maxHeight="calc(100vh - 220px)"
        />
      )}
      {explain ? (
        <section className="keel-section">
          <SectionHeader title="Request explain" description="Actual selection and failover path for the most recent matching request." />
          <div className="keel-list-card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
              <span className="keel-mono">{explain.requestTrace?.requestId ?? 'preview'}</span>
              <Chip tone={explain.requestTrace?.outcome === 'SUCCESS' ? 'ok' : 'warn'}>
                {explain.requestTrace?.outcome ?? 'PREVIEW'}
              </Chip>
              <span>{explain.requestTrace?.failoverCount ?? 0} failovers</span>
              <span>selected {explain.selectedChannelId ?? explain.requestTrace?.attempts?.find((a) => a.outcome === 'SUCCESS')?.channelId ?? '—'}</span>
            </div>
            <div style={{ display: 'grid', gap: 6 }}>
              {(explain.requestTrace?.attempts ?? []).map((attempt, index) => (
                <div key={`${attempt.channelId ?? 'attempt'}-${index}`} className="keel-mono" style={{ fontSize: 12 }}>
                  {attempt.channelId ?? '—'} · {attempt.outcome ?? 'UNKNOWN'} · {attempt.status ?? '—'}
                  {attempt.latencyMs != null ? ` · ${attempt.latencyMs} ms` : ''}
                  {attempt.reason ? ` · ${attempt.reason}` : ''}
                </div>
              ))}
            </div>
          </div>
        </section>
      ) : null}
    </>
  );
}
