import { useEffect, useState } from 'react';
import {
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  PageHeader
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface UsageRecord {
  recordId?: string;
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
  status?: string | number;
  outcome?: string;
  errorCode?: string;
  errorDetail?: string;
  upstreamKeyId?: string;
  channelId?: string;
  channelName?: string;
  poolLevelId?: string;
  groupId?: string;
  streamed?: boolean;
  failoverCount?: number;
  cacheHitRate?: number;
  latencyMs?: number;
}

function renderModelRoute(model?: string) {
  const text = String(model ?? '');
  const parts = text.split(' -> ');
  if (parts.length < 2) return text || '—';
  const alias = parts.shift() ?? '';
  const target = parts.join(' -> ');
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', whiteSpace: 'nowrap' }}>
      <code>{alias}</code>
      <svg
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.65"
        aria-hidden="true"
        style={{ display: 'inline-block', width: '12px', height: '12px', minWidth: '12px', color: 'var(--keel-muted)', verticalAlign: 'middle' }}
      >
        <path d="M2 8h9" />
        <path d="m8 4 4 4-4 4" />
      </svg>
      <code>{target}</code>
    </span>
  );
}

export function UsagePanel({ api }: { api: AiGatewayApi }) {
  const [rows, setRows] = useState<UsageRecord[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.usageRecords(200)
      .then((data) => {
        if (cancelled) return;
        setRows(((data as { records?: UsageRecord[] }).records ?? []) as UsageRecord[]);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load usage');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  const columns: DataTableColumn<UsageRecord>[] = [
    { key: 'requestId', header: 'Request', mono: true, render: (r) => (r.requestId ?? r.recordId ? String(r.requestId ?? r.recordId).slice(0, 16) : '—') },
    {
      key: 'createdAt',
      header: 'Timestamp',
      render: (r) => (r.createdAt ? r.createdAt.replace('T', ' ').slice(0, 19) : '—')
    },
    {
      key: 'group',
      header: 'Group',
      render: (r) => r.groupId ?? r.poolLevelId ?? '—'
    },
    {
      key: 'channel',
      header: 'Channel',
      render: (r) => {
        const name = r.channelName || r.channelId || r.upstreamKeyId;
        return name ? String(name).slice(0, 20) : '—';
      }
    },
    {
      key: 'model',
      header: 'Model',
      render: (r) => renderModelRoute(r.model)
    },
    {
      key: 'status',
      header: 'Status',
      render: (r) => {
        const status = r.status ?? '—';
        const tags = [
          r.outcome,
          r.streamed ? 'stream' : null,
          (r.failoverCount ?? 0) > 0 ? `failover ${r.failoverCount}` : null
        ].filter(Boolean).join(' · ');
        return (
          <div style={{ display: 'grid', gap: 2 }}>
            <strong>{status}</strong>
            {tags ? <span style={{ color: 'var(--keel-muted)', fontSize: 12 }}>{tags}</span> : null}
          </div>
        );
      }
    },
    {
      key: 'issue',
      header: 'Issue',
      render: (r) => {
        const parts = [r.errorCode, r.errorDetail, r.upstreamKeyId].filter(Boolean) as string[];
        if (parts.length === 0) return '—';
        return (
          <div style={{ display: 'grid', gap: 2 }}>
            {parts.map((part, index) => (
              <span key={`${part}-${index}`} style={{ fontSize: 12, color: index === 0 ? 'var(--keel-ink)' : 'var(--keel-muted)' }}>
                {part}
              </span>
            ))}
          </div>
        );
      }
    },
    {
      key: 'in',
      header: 'In tok',
      align: 'right',
      mono: true,
      render: (r) => (r.usage?.promptTokens ?? 0).toLocaleString()
    },
    {
      key: 'out',
      header: 'Out tok',
      align: 'right',
      mono: true,
      render: (r) => (r.usage?.completionTokens ?? 0).toLocaleString()
    },
    {
      key: 'cache',
      header: 'Cache',
      align: 'right',
      mono: true,
      render: (r) => {
        const rate = r.cacheHitRate ?? r.cost?.cacheHitRate;
        if (rate == null || rate === 0) return '—';
        return `${(rate * 100).toFixed(1)}%`;
      }
    },
    {
      key: 'cost',
      header: 'Cost (USD)',
      align: 'right',
      mono: true,
      render: (r) => `$${(r.cost?.totalCostUsd ?? r.totalCostUsd ?? 0).toFixed(4)}`
    }
  ];

  return (
    <>
      <PageHeader
        title="Usage"
        description="Per-request cost and routing detail."
      />
      {error ? <ErrorBanner message={error} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No usage records"
          detail="Once your customers start calling the API, every request will be logged here."
          icon="receipt_long"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => r.requestId ?? r.recordId ?? `row-${i}`}
          maxHeight="calc(100vh - 220px)"
        />
      )}
    </>
  );
}
