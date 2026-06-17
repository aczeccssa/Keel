import { useEffect, useMemo, useState } from 'react';
import {
  BarChart,
  DataTable,
  type DataTableColumn,
  DonutChart,
  EmptyState,
  ErrorBanner,
  PageHeader,
  SectionHeader,
  StatGrid
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface UsageRecord {
  requestId?: string;
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

export function DashboardPanel({ api }: { api: AiGatewayApi }) {
  const [global, setGlobal] = useState<UsageGlobal | null>(null);
  const [records, setRecords] = useState<UsageRecord[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.usageGlobal() as Promise<UsageGlobal>, api.usageRecords(200) as Promise<{ records?: UsageRecord[] }>])
      .then(([g, r]) => {
        if (cancelled) return;
        setGlobal(g);
        setRecords(r.records ?? []);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load dashboard');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

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
      <StatGrid
        items={[
          {
            label: 'Requests 24h',
            value: (global?.requestsLast24h ?? records.length).toLocaleString(),
            accent: 'accent',
            icon: 'bolt'
          },
          {
            label: 'Cost 24h (USD)',
            value: `$${(global?.costLast24hUsd ?? 0).toFixed(2)}`,
            accent: 'ok',
            icon: 'payments',
            valueMono: true
          },
          {
            label: 'Cache Hit Rate',
            value: cacheStats.recordsWithCache > 0 ? `${(cacheStats.avgCacheHitRate * 100).toFixed(1)}%` : '—',
            accent: 'ok',
            icon: 'storage',
            valueMono: true
          },
          {
            label: 'Avg Latency',
            value: latencyStats.avg > 0 ? `${latencyStats.avg} ms` : '—',
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
          centerLabel={(global?.totalRequests ?? records.length).toLocaleString()}
          centerHint="Requests"
        />
      </div>
      {latencyStats.avg > 0 && (
        <section className="keel-section">
          <SectionHeader title="Latency percentiles" description="Response time distribution" />
          <StatGrid
            items={[
              {
                label: 'P50 (Median)',
                value: `${latencyStats.p50} ms`,
                accent: 'muted',
                valueMono: true
              },
              {
                label: 'P95',
                value: `${latencyStats.p95} ms`,
                accent: latencyStats.p95 > 5000 ? 'warn' : 'muted',
                valueMono: true
              },
              {
                label: 'P99',
                value: `${latencyStats.p99} ms`,
                accent: latencyStats.p99 > 10000 ? 'danger' : 'muted',
                valueMono: true
              },
              {
                label: 'Average',
                value: `${latencyStats.avg} ms`,
                accent: 'accent',
                valueMono: true
              }
            ]}
          />
        </section>
      )}
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
