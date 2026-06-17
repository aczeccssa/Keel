import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  BarChart,
  DataTable,
  type DataTableColumn,
  DonutChart,
  EmptyState,
  ErrorBanner,
  PageHeader,
  SectionHeader,
  SparklineChart,
  StatGrid
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface UsageRecord {
  requestId?: string;
  recordId?: string;
  model?: string;
  createdAt?: string;
  totalCostUsd?: number;
  cost?: {
    totalCostUsd?: number;
    inputCostUsd?: number;
    outputCostUsd?: number;
    cacheHitRate?: number;
  };
  usage?: {
    promptTokens?: number;
    completionTokens?: number;
    cacheReadInputTokens?: number;
    cacheCreationInputTokens?: number;
  };
  cacheHitRate?: number;
  latencyMs?: number;
  status?: number;
  outcome?: string;
  streamed?: boolean;
  failoverCount?: number;
  upstreamKeyId?: string;
  poolLevelId?: string;
  errorCode?: string;
  errorDetail?: string;
}

interface DashboardStats {
  overview?: {
    totalRequests?: number;
    successRate?: number;
    totalCostUsd?: number;
    totalTokens?: number;
    avgLatencyMs?: number;
    p50LatencyMs?: number;
    p95LatencyMs?: number;
    p99LatencyMs?: number;
    cacheHitRate?: number;
    healthyChannels?: number;
    cooldownChannels?: number;
    disabledChannels?: number;
  };
  trends?: {
    requestsByHour?: Array<{ timestamp: number; requests: number; successRate: number }>;
    tokensByHour?: Array<{
      timestamp: number;
      promptTokens: number;
      completionTokens: number;
      cacheWriteTokens: number;
      cacheReadTokens: number;
      costUsd: number;
    }>;
    latencyByHour?: Array<{ timestamp: number; p50: number; p95: number; p99: number }>;
  };
  distributions?: {
    modelDistribution?: Array<{ model: string; requests: number; percentage: number; totalCostUsd: number }>;
    channelDistribution?: Array<{ channelId: string; channelName: string; requests: number; successRate: number; avgLatencyMs: number }>;
    groupDistribution?: Array<{ groupId: string; groupName: string; requests: number; totalCostUsd: number; topModels: string[] }>;
    errorDistribution?: Array<{ errorType: string; count: number; percentage: number }>;
  };
}

interface UsageGlobal {
  totalRequests?: number;
  totalCostUsd?: number;
  requestsLast24h?: number;
  costLast24hUsd?: number;
  activeKeys?: number;
  activeCustomers?: number;
  requestsByDay?: Array<{ day: string; requests: number; cost: number }>;
}

function formatTrendLabel(timestamp: number, window: string) {
  const d = new Date(timestamp);
  if (window === '1h' || window === '24h') {
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

function formatTotalTokens(value: number) {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return value.toLocaleString();
}

export function DashboardPanel({ api }: { api: AiGatewayApi }) {
  const [global, setGlobal] = useState<UsageGlobal | null>(null);
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [records, setRecords] = useState<UsageRecord[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [timeWindow, setTimeWindow] = useState<string>('24h');
  const [autoRefresh, setAutoRefresh] = useState(false);

  const loadDashboard = useCallback((cancelled: () => boolean) => {
    Promise.all([
      api.usageGlobal() as Promise<UsageGlobal>,
      api.dashboardStats(timeWindow) as Promise<DashboardStats>,
      api.usageRecords(200) as Promise<{ records?: UsageRecord[] }>
    ])
      .then(([g, s, r]) => {
        if (cancelled()) return;
        setError(null);
        setGlobal(g);
        setStats(s);
        setRecords(r.records ?? []);
      })
      .catch((err) => {
        if (!cancelled()) setError(err instanceof Error ? err.message : 'Unable to load dashboard');
      });
  }, [api, timeWindow]);

  useEffect(() => {
    let cancelled = false;
    loadDashboard(() => cancelled);
    return () => {
      cancelled = true;
    };
  }, [loadDashboard]);

  useEffect(() => {
    if (!autoRefresh) return;
    let cancelled = false;
    const timer = setInterval(() => loadDashboard(() => cancelled), 30_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [autoRefresh, loadDashboard]);

  const topModels = useMemo(() => {
    const map = new Map<string, { requests: number; cost: number }>();
    for (const r of records) {
      const k = r.model ?? 'unknown';
      const cur = map.get(k) ?? { requests: 0, cost: 0 };
      cur.requests += 1;
      cur.cost += r.cost?.totalCostUsd ?? r.totalCostUsd ?? 0;
      map.set(k, cur);
    }
    return Array.from(map.entries())
      .map(([model, v]) => ({ model, ...v }))
      .sort((a, b) => b.requests - a.requests)
      .slice(0, 5);
  }, [records]);

  const modelMix = useMemo(() => topModels.map((m) => ({ label: m.model, value: m.requests })), [topModels]);

  const cacheStats = useMemo(() => {
    const withCache = records.filter(r => {
      const rate = r.cacheHitRate ?? r.cost?.cacheHitRate;
      return rate != null && rate > 0;
    });
    const totalCacheHitRate = withCache.length > 0
      ? withCache.reduce((sum, r) => sum + (r.cacheHitRate ?? r.cost?.cacheHitRate ?? 0), 0) / withCache.length
      : 0;
    return {
      avgCacheHitRate: totalCacheHitRate,
      recordsWithCache: withCache.length,
      totalRecords: records.length
    };
  }, [records]);

  const latencyStats = useMemo(() => {
    const latencies = records.map(r => r.latencyMs).filter((l): l is number => l != null && l > 0);
    if (latencies.length === 0) return { avg: 0, p50: 0, p95: 0, p99: 0 };
    const sorted = [...latencies].sort((a, b) => a - b);
    return {
      avg: Math.round(latencies.reduce((sum, l) => sum + l, 0) / latencies.length),
      p50: sorted[Math.floor(sorted.length * 0.5)] ?? 0,
      p95: sorted[Math.floor(sorted.length * 0.95)] ?? 0,
      p99: sorted[Math.floor(sorted.length * 0.99)] ?? 0
    };
  }, [records]);

  const volumeByDay = useMemo(() => {
    if (global?.requestsByDay && global.requestsByDay.length > 0) {
      return global.requestsByDay.map((d) => ({ label: d.day.slice(5), value: d.requests }));
    }
    const buckets = new Map<string, number>();
    for (const r of records) {
      if (!r.createdAt) continue;
      const day = r.createdAt.slice(0, 10).slice(5);
      buckets.set(day, (buckets.get(day) ?? 0) + 1);
    }
    return Array.from(buckets.entries())
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([label, value]) => ({ label, value }))
      .slice(-14);
  }, [global, records]);

  const requestTrend = useMemo(() => {
    const points = stats?.trends?.requestsByHour ?? [];
    return points.map((p) => ({ label: formatTrendLabel(p.timestamp, timeWindow), value: p.requests }));
  }, [stats, timeWindow]);

  const tokenTrend = useMemo(() => {
    const points = stats?.trends?.tokensByHour ?? [];
    return points.map((p) => ({
      label: formatTrendLabel(p.timestamp, timeWindow),
      value: p.promptTokens + p.completionTokens + p.cacheWriteTokens + p.cacheReadTokens
    }));
  }, [stats, timeWindow]);

  const latencyTrend = useMemo(() => {
    const points = stats?.trends?.latencyByHour ?? [];
    return points.map((p) => ({ label: formatTrendLabel(p.timestamp, timeWindow), value: p.p95 }));
  }, [stats, timeWindow]);

  const recentCols: DataTableColumn<UsageRecord>[] = [
    {
      key: 'requestId',
      header: 'Request',
      mono: true,
      render: (r) => (r.requestId ? r.requestId.slice(0, 14) : '—')
    },
    { key: 'createdAt', header: 'When', render: (r) => (r.createdAt ? r.createdAt.replace('T', ' ').slice(0, 19) : '—') },
    { key: 'model', header: 'Model' },
    {
      key: 'cost',
      header: 'Cost (USD)',
      align: 'right',
      mono: true,
      render: (r) => `$${(r.cost?.totalCostUsd ?? r.totalCostUsd ?? 0).toFixed(4)}`
    }
  ];

  const topModelsCols: DataTableColumn<(typeof topModels)[number]>[] = [
    { key: 'model', header: 'Model' },
    { key: 'requests', header: 'Requests', align: 'right', mono: true },
    { key: 'cost', header: 'Cost', align: 'right', mono: true, render: (r) => `$${r.cost.toFixed(2)}` }
  ];

  return (
    <>
      <PageHeader title="Dashboard" description="Cost, usage, and routing across the AI Relay." />
      {error ? <ErrorBanner message={error} /> : null}

      {/* Time Window Selector */}
      <div style={{
        display: 'flex',
        gap: '8px',
        marginBottom: '24px',
        padding: '12px',
        background: 'var(--keel-surface, #fff)',
        border: '1px solid var(--keel-border, #e5e7eb)',
        borderRadius: '8px'
      }}>
        <span style={{ fontSize: '14px', fontWeight: '500', color: 'var(--keel-ink)', lineHeight: '32px' }}>
          Time window:
        </span>
        {['1h', '24h', '7d', '30d'].map(window => (
          <button
            key={window}
            onClick={() => setTimeWindow(window)}
            style={{
              padding: '6px 16px',
              fontSize: '14px',
              fontWeight: timeWindow === window ? '600' : '400',
              background: timeWindow === window ? 'var(--keel-accent, #3b82f6)' : 'var(--keel-surface, #fff)',
              color: timeWindow === window ? 'white' : 'var(--keel-ink)',
              border: `1px solid ${timeWindow === window ? 'var(--keel-accent, #3b82f6)' : 'var(--keel-border, #e5e7eb)'}`,
              borderRadius: '4px',
              cursor: 'pointer'
            }}
          >
            {window}
          </button>
        ))}
        <label style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--keel-muted)' }}>
          <input checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} type="checkbox" />
          Auto-refresh 30s
        </label>
      </div>

      <StatGrid
        items={[
          {
            label: 'Requests 24h',
            value: (stats?.overview?.totalRequests ?? global?.requestsLast24h ?? records.length).toLocaleString(),
            accent: 'accent',
            icon: 'bolt'
          },
          {
            label: 'Success Rate',
            value: stats?.overview?.successRate != null ? `${(stats.overview.successRate * 100).toFixed(1)}%` : '—',
            accent: 'ok',
            icon: 'check_circle',
            valueMono: true
          },
          {
            label: 'Cost 24h (USD)',
            value: `$${(stats?.overview?.totalCostUsd ?? global?.costLast24hUsd ?? 0).toFixed(2)}`,
            accent: 'ok',
            icon: 'payments',
            valueMono: true
          },
          {
            label: 'Avg Latency',
            value: stats?.overview?.avgLatencyMs ? `${stats.overview.avgLatencyMs} ms` : (latencyStats.avg > 0 ? `${latencyStats.avg} ms` : '—'),
            accent: 'muted',
            icon: 'speed',
            valueMono: true
          }
        ]}
      />
      <div className="keel-grid-2">
        <BarChart
          title="Request volume"
          hint="Last 14 days"
          data={volumeByDay.length > 0 ? volumeByDay : [{ label: '—', value: 0 }]}
        />
        <DonutChart
          title="Model mix"
          hint="Top models"
          slices={modelMix.length > 0 ? modelMix : [{ label: 'No data', value: 1 }]}
          centerLabel={(stats?.overview?.totalRequests ?? global?.totalRequests ?? records.length).toLocaleString()}
          centerHint="Requests"
        />
      </div>

      <section className="keel-section">
        <SectionHeader title="Time-series trends" description={`Traffic, token, and latency trends for ${timeWindow}.`} />
        <div className="keel-grid-2">
          <BarChart
            title="Requests by bucket"
            hint="Request count"
            data={requestTrend.length > 0 ? requestTrend : [{ label: '—', value: 0 }]}
          />
          <BarChart
            title="Tokens by bucket"
            hint="Input + output + cache tokens"
            data={tokenTrend.length > 0 ? tokenTrend : [{ label: '—', value: 0 }]}
          />
        </div>
        <div style={{ marginTop: 16, padding: 16, border: '1px solid var(--keel-border)', borderRadius: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
            <div>
              <strong>Latency P95 trend</strong>
              <div style={{ color: 'var(--keel-muted)', fontSize: 12 }}>Higher values indicate slower upstream responses.</div>
            </div>
            <SparklineChart values={latencyTrend.map((p) => p.value)} width={260} height={56} title="Latency P95 trend" />
          </div>
        </div>
      </section>

      {stats?.overview && (
        <section className="keel-section">
          <SectionHeader title="Additional metrics" description="Cache efficiency and channel health" />
          <StatGrid
            items={[
              {
                label: 'Cache Hit Rate',
                value: stats.overview.cacheHitRate != null ? `${(stats.overview.cacheHitRate * 100).toFixed(1)}%` : (cacheStats.recordsWithCache > 0 ? `${(cacheStats.avgCacheHitRate * 100).toFixed(1)}%` : '—'),
                accent: 'ok',
                icon: 'storage',
                valueMono: true
              },
              {
                label: 'Healthy Channels',
                value: (stats.overview.healthyChannels ?? 0).toLocaleString(),
                accent: 'ok',
                icon: 'check_circle',
                valueMono: true
              },
              {
                label: 'Cooldown Channels',
                value: (stats.overview.cooldownChannels ?? 0).toLocaleString(),
                accent: 'warn',
                icon: 'schedule',
                valueMono: true
              },
              {
                label: 'Disabled Channels',
                value: (stats.overview.disabledChannels ?? 0).toLocaleString(),
                accent: 'danger',
                icon: 'block',
                valueMono: true
              }
            ]}
          />
        </section>
      )}
      {(stats?.overview?.p50LatencyMs || latencyStats.avg > 0) && (
        <section className="keel-section">
          <SectionHeader title="Latency percentiles" description="Response time distribution" />
          <StatGrid
            items={[
              {
                label: 'P50 (Median)',
                value: `${stats?.overview?.p50LatencyMs ?? latencyStats.p50} ms`,
                accent: 'muted',
                valueMono: true
              },
              {
                label: 'P95',
                value: `${stats?.overview?.p95LatencyMs ?? latencyStats.p95} ms`,
                accent: (stats?.overview?.p95LatencyMs ?? latencyStats.p95) > 5000 ? 'warn' : 'muted',
                valueMono: true
              },
              {
                label: 'P99',
                value: `${stats?.overview?.p99LatencyMs ?? latencyStats.p99} ms`,
                accent: (stats?.overview?.p99LatencyMs ?? latencyStats.p99) > 10000 ? 'danger' : 'muted',
                valueMono: true
              },
              {
                label: 'Average',
                value: `${stats?.overview?.avgLatencyMs ?? latencyStats.avg} ms`,
                accent: 'accent',
                valueMono: true
              }
            ]}
          />
        </section>
      )}

      {/* Distribution Charts Section */}
      {stats?.distributions && (
        <>
          <section className="keel-section">
            <SectionHeader title="Distribution analysis" description="Traffic patterns and error breakdown" />
          </section>
          <div className="keel-grid-2">
            {stats.distributions.modelDistribution && stats.distributions.modelDistribution.length > 0 && (
              <DonutChart
                title="Model distribution"
                hint="Top 10 by requests"
                slices={stats.distributions.modelDistribution.slice(0, 10).map(m => ({
                  label: m.model,
                  value: m.requests
                }))}
                centerLabel={stats.distributions.modelDistribution.reduce((sum, m) => sum + m.requests, 0).toLocaleString()}
                centerHint="Total requests"
              />
            )}
            {stats.distributions.channelDistribution && stats.distributions.channelDistribution.length > 0 && (
              <BarChart
                title="Channel distribution"
                hint="Top 10 by requests"
                data={stats.distributions.channelDistribution.slice(0, 10).map(c => ({
                  label: c.channelName.slice(0, 20),
                  value: c.requests
                }))}
              />
            )}
          </div>
          <div className="keel-grid-2" style={{ marginTop: 24 }}>
            {stats.distributions.groupDistribution && stats.distributions.groupDistribution.length > 0 && (
              <BarChart
                title="Group distribution"
                hint="Top groups by requests"
                data={stats.distributions.groupDistribution.slice(0, 10).map(g => ({
                  label: g.groupName.slice(0, 20),
                  value: g.requests
                }))}
              />
            )}
            {stats.distributions.errorDistribution && stats.distributions.errorDistribution.length > 0 && (
              <DonutChart
                title="Error distribution"
                hint="By error type"
                slices={stats.distributions.errorDistribution.slice(0, 10).map(e => ({
                  label: e.errorType,
                  value: e.count
                }))}
                centerLabel={stats.distributions.errorDistribution.reduce((sum, e) => sum + e.count, 0).toLocaleString()}
                centerHint="Total errors"
              />
            )}
          </div>
        </>
      )}

      <section className="keel-section">
        <SectionHeader title="Real-time monitor" description="Latest traffic and channel health snapshot." />
        <div className="keel-grid-2">
          <div style={{ border: '1px solid var(--keel-border)', borderRadius: 8, padding: 16 }}>
            <strong>Recent request stream</strong>
            <div style={{ display: 'grid', gap: 8, marginTop: 12, maxHeight: 260, overflow: 'auto' }}>
              {records.slice(0, 20).map((r, i) => (
                <div key={r.requestId ?? r.recordId ?? i} style={{ display: 'grid', gridTemplateColumns: '90px 1fr auto', gap: 8, alignItems: 'center', fontSize: 12 }}>
                  <span className="keel-mono" style={{ color: 'var(--keel-muted)' }}>{r.createdAt ? r.createdAt.replace('T', ' ').slice(11, 19) : '—'}</span>
                  <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.model ?? 'unknown'}</span>
                  <span style={{ color: (r.status ?? 0) >= 400 ? 'var(--keel-danger)' : 'var(--keel-success)' }}>{r.status ?? '—'}</span>
                </div>
              ))}
            </div>
          </div>
          <div style={{ border: '1px solid var(--keel-border)', borderRadius: 8, padding: 16 }}>
            <strong>Channel health board</strong>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginTop: 12 }}>
              <div style={{ padding: 12, borderRadius: 8, background: 'rgba(16,185,129,.12)' }}>
                <div style={{ color: 'var(--keel-muted)', fontSize: 12 }}>Healthy</div>
                <div style={{ fontSize: 24, fontWeight: 700 }}>{stats?.overview?.healthyChannels ?? 0}</div>
              </div>
              <div style={{ padding: 12, borderRadius: 8, background: 'rgba(245,158,11,.12)' }}>
                <div style={{ color: 'var(--keel-muted)', fontSize: 12 }}>Cooldown</div>
                <div style={{ fontSize: 24, fontWeight: 700 }}>{stats?.overview?.cooldownChannels ?? 0}</div>
              </div>
              <div style={{ padding: 12, borderRadius: 8, background: 'rgba(239,68,68,.12)' }}>
                <div style={{ color: 'var(--keel-muted)', fontSize: 12 }}>Disabled</div>
                <div style={{ fontSize: 24, fontWeight: 700 }}>{stats?.overview?.disabledChannels ?? 0}</div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="keel-section">
        <SectionHeader title="Top models" description="Highest traffic routes by request count." />
        {topModels.length === 0 ? (
          <EmptyState
            title="No traffic yet"
            detail="Top models will appear once requests flow."
            icon="data_table"
          />
        ) : (
          <DataTable columns={topModelsCols} rows={topModels} getRowKey={(r) => r.model} />
        )}
      </section>
      <section className="keel-section">
        <SectionHeader
          title="Recent requests"
          description="Latest 200 records from the usage log."
        />
        {records.length === 0 ? (
          <EmptyState
            title="No usage records"
            detail="Recent requests will appear here."
            icon="receipt_long"
          />
        ) : (
          <DataTable
            columns={recentCols}
            rows={records.slice(0, 50)}
            getRowKey={(r, i) => r.requestId ?? `row-${i}`}
            maxHeight="calc(100vh - 280px)"
          />
        )}
      </section>
    </>
  );
}
