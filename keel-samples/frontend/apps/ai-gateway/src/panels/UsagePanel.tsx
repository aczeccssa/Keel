import { useEffect, useState } from 'react';
import {
  Button,
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

function DetailRow({ label, value }: { label: string; value: unknown }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '130px 1fr', gap: 8, fontSize: 13 }}>
      <span style={{ color: 'var(--keel-muted)' }}>{label}</span>
      <span className={typeof value === 'number' ? 'keel-mono' : undefined}>{value == null || value === '' ? '—' : String(value)}</span>
    </div>
  );
}

export function UsagePanel({ api }: { api: AiGatewayApi }) {
  const [rows, setRows] = useState<UsageRecord[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState<{
    groupId?: string;
    channelId?: string;
    model?: string;
    statusFilter?: string;
  }>({});
  const [selectedRecord, setSelectedRecord] = useState<UsageRecord | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.usageRecords(200, filters)
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
  }, [api, filters]);

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

      {/* Filters Section */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
        gap: '12px',
        marginBottom: '24px',
        padding: '16px',
        background: 'var(--keel-surface, #fff)',
        border: '1px solid var(--keel-border, #e5e7eb)',
        borderRadius: '8px'
      }}>
        <div>
          <label style={{ display: 'block', fontSize: '12px', fontWeight: '500', marginBottom: '4px', color: 'var(--keel-ink)' }}>
            Group
          </label>
          <select
            value={filters.groupId ?? ''}
            onChange={(e) => setFilters(prev => ({ ...prev, groupId: e.target.value || undefined }))}
            style={{
              width: '100%',
              padding: '6px 8px',
              fontSize: '14px',
              border: '1px solid var(--keel-border, #e5e7eb)',
              borderRadius: '4px'
            }}
          >
            <option value="">All groups</option>
            {Array.from(new Set(rows.map(r => r.groupId ?? r.poolLevelId).filter(Boolean))).map(g => (
              <option key={g} value={g}>{g}</option>
            ))}
          </select>
        </div>

        <div>
          <label style={{ display: 'block', fontSize: '12px', fontWeight: '500', marginBottom: '4px', color: 'var(--keel-ink)' }}>
            Channel
          </label>
          <select
            value={filters.channelId ?? ''}
            onChange={(e) => setFilters(prev => ({ ...prev, channelId: e.target.value || undefined }))}
            style={{
              width: '100%',
              padding: '6px 8px',
              fontSize: '14px',
              border: '1px solid var(--keel-border, #e5e7eb)',
              borderRadius: '4px'
            }}
          >
            <option value="">All channels</option>
            {Array.from(new Set(rows.map(r => r.channelId ?? r.upstreamKeyId).filter(Boolean))).map(c => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        </div>

        <div>
          <label style={{ display: 'block', fontSize: '12px', fontWeight: '500', marginBottom: '4px', color: 'var(--keel-ink)' }}>
            Model
          </label>
          <select
            value={filters.model ?? ''}
            onChange={(e) => setFilters(prev => ({ ...prev, model: e.target.value || undefined }))}
            style={{
              width: '100%',
              padding: '6px 8px',
              fontSize: '14px',
              border: '1px solid var(--keel-border, #e5e7eb)',
              borderRadius: '4px'
            }}
          >
            <option value="">All models</option>
            {Array.from(new Set(rows.map(r => r.model).filter(Boolean))).map(m => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </div>

        <div>
          <label style={{ display: 'block', fontSize: '12px', fontWeight: '500', marginBottom: '4px', color: 'var(--keel-ink)' }}>
            Status
          </label>
          <select
            value={filters.statusFilter ?? ''}
            onChange={(e) => setFilters(prev => ({ ...prev, statusFilter: e.target.value || undefined }))}
            style={{
              width: '100%',
              padding: '6px 8px',
              fontSize: '14px',
              border: '1px solid var(--keel-border, #e5e7eb)',
              borderRadius: '4px'
            }}
          >
            <option value="">All status</option>
            <option value="success">Success only</option>
            <option value="error">Error only</option>
          </select>
        </div>

        {(filters.groupId || filters.channelId || filters.model || filters.statusFilter) && (
          <div style={{ display: 'flex', alignItems: 'flex-end' }}>
            <button
              onClick={() => setFilters({})}
              style={{
                width: '100%',
                padding: '6px 8px',
                fontSize: '14px',
                background: 'var(--keel-danger, #ef4444)',
                color: 'white',
                border: 'none',
                borderRadius: '4px',
                cursor: 'pointer'
              }}
            >
              Clear filters
            </button>
          </div>
        )}
      </div>

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
          actionsHeader="Detail"
          actionsColumn={(r) => (
            <Button size="sm" variant="secondary" onClick={() => setSelectedRecord(r)}>
              View
            </Button>
          )}
        />
      )}

      {selectedRecord ? (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Usage detail"
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(15, 23, 42, .35)',
            zIndex: 100,
            display: 'flex',
            justifyContent: 'flex-end'
          }}
          onClick={() => setSelectedRecord(null)}
        >
          <aside
            style={{
              width: 'min(520px, 100vw)',
              height: '100%',
              background: 'var(--keel-surface, #fff)',
              boxShadow: '-20px 0 40px rgba(15, 23, 42, .18)',
              padding: 24,
              overflow: 'auto'
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginBottom: 20 }}>
              <div>
                <h2 style={{ margin: 0 }}>Request detail</h2>
                <p style={{ margin: '4px 0 0', color: 'var(--keel-muted)', fontSize: 13 }}>Full token, cost, routing, and error context.</p>
              </div>
              <Button size="sm" variant="secondary" onClick={() => setSelectedRecord(null)}>Close</Button>
            </div>

            <section style={{ display: 'grid', gap: 8, marginBottom: 20 }}>
              <h3>Basic</h3>
              <DetailRow label="Request ID" value={selectedRecord.requestId ?? selectedRecord.recordId} />
              <DetailRow label="Timestamp" value={selectedRecord.createdAt} />
              <DetailRow label="Model" value={selectedRecord.model} />
              <DetailRow label="Group" value={selectedRecord.groupId ?? selectedRecord.poolLevelId} />
              <DetailRow label="Channel" value={selectedRecord.channelName ?? selectedRecord.channelId ?? selectedRecord.upstreamKeyId} />
              <DetailRow label="Status" value={`${selectedRecord.status ?? '—'} (${selectedRecord.outcome ?? '—'})`} />
            </section>

            <section style={{ display: 'grid', gap: 8, marginBottom: 20 }}>
              <h3>Tokens</h3>
              <DetailRow label="Input" value={(selectedRecord.usage?.promptTokens ?? 0).toLocaleString()} />
              <DetailRow label="Output" value={(selectedRecord.usage?.completionTokens ?? 0).toLocaleString()} />
              <DetailRow label="Cache Write" value={(selectedRecord.usage?.cacheCreationInputTokens ?? 0).toLocaleString()} />
              <DetailRow label="Cache Read" value={(selectedRecord.usage?.cacheReadInputTokens ?? 0).toLocaleString()} />
              <DetailRow label="Cache Hit Rate" value={selectedRecord.cacheHitRate != null || selectedRecord.cost?.cacheHitRate != null ? `${((selectedRecord.cacheHitRate ?? selectedRecord.cost?.cacheHitRate ?? 0) * 100).toFixed(1)}%` : '—'} />
            </section>

            <section style={{ display: 'grid', gap: 8, marginBottom: 20 }}>
              <h3>Cost</h3>
              <DetailRow label="Input Cost" value={`$${(selectedRecord.cost?.inputCostUsd ?? 0).toFixed(4)}`} />
              <DetailRow label="Output Cost" value={`$${(selectedRecord.cost?.outputCostUsd ?? 0).toFixed(4)}`} />
              <DetailRow label="Total Cost" value={`$${(selectedRecord.cost?.totalCostUsd ?? selectedRecord.totalCostUsd ?? 0).toFixed(4)}`} />
            </section>

            <section style={{ display: 'grid', gap: 8 }}>
              <h3>Performance & Errors</h3>
              <DetailRow label="Latency" value={selectedRecord.latencyMs != null ? `${selectedRecord.latencyMs} ms` : '—'} />
              <DetailRow label="Failover" value={selectedRecord.failoverCount ?? 0} />
              <DetailRow label="Streamed" value={selectedRecord.streamed ? 'yes' : 'no'} />
              <DetailRow label="Error Code" value={selectedRecord.errorCode} />
              <DetailRow label="Error Detail" value={selectedRecord.errorDetail} />
            </section>
          </aside>
        </div>
      ) : null}
    </>
  );
}
