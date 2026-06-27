import { useEffect, useState } from 'react';
import {
  Button,
  Card,
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  PageHeader,
  SectionHeader,
  StatGrid
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';
import './UsagePanel.css';

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

function formatTimestamp(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—';
}

function formatCurrency(value?: number) {
  return `$${(value ?? 0).toFixed(4)}`;
}

function formatInteger(value?: number) {
  return (value ?? 0).toLocaleString();
}

function formatRate(value?: number) {
  if (value == null || value === 0) return '—';
  return `${(value * 100).toFixed(1)}%`;
}

function renderModelRoute(model?: string) {
  const text = String(model ?? '');
  const parts = text.split(' -> ');
  if (parts.length < 2) return text || '—';

  const alias = parts.shift() ?? '';
  const target = parts.join(' -> ');

  return (
    <div className="usage-panel__stack usage-panel__stack--tight">
      <code className="keel-mono">{alias}</code>
      <span className="usage-panel__route-target">
        <svg
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.65"
          aria-hidden="true"
          className="usage-panel__route-icon"
        >
          <path d="M2 8h9" />
          <path d="m8 4 4 4-4 4" />
        </svg>
        <code className="keel-mono">{target}</code>
      </span>
    </div>
  );
}

function DetailRow({ label, value }: { label: string; value: unknown }) {
  return (
    <div className="usage-panel__detail-row">
      <span className="usage-panel__detail-label">{label}</span>
      <span className={typeof value === 'number' ? 'keel-mono' : undefined}>
        {value == null || value === '' ? '—' : String(value)}
      </span>
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
    setError(null);

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

  const hasFilters = Boolean(filters.groupId || filters.channelId || filters.model || filters.statusFilter);
  const uniqueGroups = Array.from(new Set(rows.map((r) => r.groupId ?? r.poolLevelId).filter(Boolean)));
  const uniqueChannels = Array.from(new Set(rows.map((r) => r.channelId ?? r.upstreamKeyId).filter(Boolean)));
  const uniqueModels = Array.from(new Set(rows.map((r) => r.model).filter(Boolean)));

  let totalCostUsd = 0;
  let totalInputTokens = 0;
  let totalOutputTokens = 0;
  let totalCacheTokens = 0;
  let totalFailures = 0;

  for (const row of rows) {
    totalCostUsd += row.cost?.totalCostUsd ?? row.totalCostUsd ?? 0;
    totalInputTokens += row.usage?.promptTokens ?? 0;
    totalOutputTokens += row.usage?.completionTokens ?? 0;
    totalCacheTokens += (row.usage?.cacheReadInputTokens ?? 0) + (row.usage?.cacheCreationInputTokens ?? 0);

    const outcome = String(row.outcome ?? '').toLowerCase();
    const statusNumber = typeof row.status === 'number' ? row.status : Number(row.status);
    if (outcome === 'error' || (!Number.isNaN(statusNumber) && statusNumber >= 400)) {
      totalFailures += 1;
    }
  }

  const columns: DataTableColumn<UsageRecord>[] = [
    {
      key: 'request',
      header: 'Request',
      width: '13rem',
      render: (r) => (
        <div className="usage-panel__stack">
          <strong className="keel-mono">
            {r.requestId ?? r.recordId ? String(r.requestId ?? r.recordId).slice(0, 16) : '—'}
          </strong>
          <span className="keel-mono">{formatTimestamp(r.createdAt)}</span>
        </div>
      )
    },
    {
      key: 'route',
      header: 'Route',
      width: '14rem',
      render: (r) => (
        <div className="usage-panel__stack">
          <strong>{r.groupId ?? r.poolLevelId ?? '—'}</strong>
          <span>{r.channelName ?? r.channelId ?? r.upstreamKeyId ?? '—'}</span>
        </div>
      )
    },
    {
      key: 'model',
      header: 'Model',
      width: '16rem',
      render: (r) => renderModelRoute(r.model)
    },
    {
      key: 'status',
      header: 'Status',
      width: '14rem',
      render: (r) => {
        const tags = [
          r.outcome,
          r.streamed ? 'stream' : null,
          (r.failoverCount ?? 0) > 0 ? `failover ${r.failoverCount}` : null
        ].filter(Boolean).join(' · ');

        return (
          <div className="usage-panel__stack">
            <strong>{r.status ?? '—'}</strong>
            {tags ? <span>{tags}</span> : null}
            {r.errorCode ? <span>{r.errorCode}</span> : null}
            {r.errorDetail ? <span>{r.errorDetail}</span> : null}
          </div>
        );
      }
    },
    {
      key: 'tokens',
      header: 'Tokens',
      align: 'right',
      width: '11rem',
      render: (r) => (
        <div className="usage-panel__stack usage-panel__stack--numeric">
          <strong className="keel-mono">{formatInteger(r.usage?.promptTokens)}</strong>
          <span>out {formatInteger(r.usage?.completionTokens)}</span>
          <span>cache {formatInteger((r.usage?.cacheReadInputTokens ?? 0) + (r.usage?.cacheCreationInputTokens ?? 0))}</span>
        </div>
      )
    },
    {
      key: 'cost',
      header: 'Cost',
      align: 'right',
      width: '10rem',
      render: (r) => (
        <div className="usage-panel__stack usage-panel__stack--numeric">
          <strong className="keel-mono">{formatCurrency(r.cost?.totalCostUsd ?? r.totalCostUsd)}</strong>
          <span>in {formatCurrency(r.cost?.inputCostUsd)}</span>
          <span>out {formatCurrency(r.cost?.outputCostUsd)}</span>
          <span>hit {formatRate(r.cacheHitRate ?? r.cost?.cacheHitRate)}</span>
        </div>
      )
    }
  ];

  return (
    <div className="usage-panel">
      <PageHeader
        title="Usage"
        description="Compact request usage view with filters, rollups, and full per-request drilldown."
        actions={(
          <div className="usage-panel__headline">
            <span className="usage-panel__headline-label">Latest slice</span>
            <strong className="usage-panel__headline-value keel-mono">{rows.length.toLocaleString()} rows</strong>
            <span className="usage-panel__headline-hint">
              {hasFilters ? 'Filtered operational view' : 'Live operational view'}
            </span>
          </div>
        )}
      />

      {error ? <ErrorBanner message={error} /> : null}

      <Card className="usage-panel__card">
        <SectionHeader
          title="Filters"
          description="Narrow the request ledger without crowding the data table."
          action={hasFilters ? (
            <Button size="sm" variant="danger" onClick={() => setFilters({})}>
              Clear filters
            </Button>
          ) : (
            <span className="usage-panel__section-meta">No filters applied</span>
          )}
        />

        <div className="usage-panel__filter-grid">
          <div className="keel-field usage-panel__field">
            <label htmlFor="usage-filter-group">Group</label>
            <select
              id="usage-filter-group"
              value={filters.groupId ?? ''}
              onChange={(e) => setFilters((prev) => ({ ...prev, groupId: e.target.value || undefined }))}
            >
              <option value="">All groups</option>
              {uniqueGroups.map((group) => (
                <option key={group} value={group}>
                  {group}
                </option>
              ))}
            </select>
          </div>

          <div className="keel-field usage-panel__field">
            <label htmlFor="usage-filter-channel">Channel</label>
            <select
              id="usage-filter-channel"
              value={filters.channelId ?? ''}
              onChange={(e) => setFilters((prev) => ({ ...prev, channelId: e.target.value || undefined }))}
            >
              <option value="">All channels</option>
              {uniqueChannels.map((channel) => (
                <option key={channel} value={channel}>
                  {channel}
                </option>
              ))}
            </select>
          </div>

          <div className="keel-field usage-panel__field">
            <label htmlFor="usage-filter-model">Model</label>
            <select
              id="usage-filter-model"
              value={filters.model ?? ''}
              onChange={(e) => setFilters((prev) => ({ ...prev, model: e.target.value || undefined }))}
            >
              <option value="">All models</option>
              {uniqueModels.map((model) => (
                <option key={model} value={model}>
                  {model}
                </option>
              ))}
            </select>
          </div>

          <div className="keel-field usage-panel__field">
            <label htmlFor="usage-filter-status">Status</label>
            <select
              id="usage-filter-status"
              value={filters.statusFilter ?? ''}
              onChange={(e) => setFilters((prev) => ({ ...prev, statusFilter: e.target.value || undefined }))}
            >
              <option value="">All status</option>
              <option value="success">Success only</option>
              <option value="error">Error only</option>
            </select>
          </div>
        </div>
      </Card>

      <Card className="usage-panel__card">
        <SectionHeader
          title="Summary"
          description="Current slice totals, so the table below can stay focused on individual requests."
          action={<span className="usage-panel__section-meta">Based on fetched rows</span>}
        />

        <StatGrid
          items={[
            {
              label: 'Requests',
              value: rows.length.toLocaleString(),
              hint: hasFilters ? 'Visible after filters' : 'Latest fetched rows',
              icon: 'receipt_long',
              valueMono: true
            },
            {
              label: 'Total spend',
              value: formatCurrency(totalCostUsd),
              hint: 'Sum of request costs in the current slice',
              icon: 'attach_money',
              valueMono: true,
              accent: totalCostUsd > 0 ? 'accent' : 'muted'
            },
            {
              label: 'Input tokens',
              value: formatInteger(totalInputTokens),
              hint: `Output ${formatInteger(totalOutputTokens)}`,
              icon: 'input',
              valueMono: true
            },
            {
              label: 'Cache activity',
              value: formatInteger(totalCacheTokens),
              hint: 'Read + write cache tokens',
              icon: 'database',
              valueMono: true,
              accent: totalCacheTokens > 0 ? 'ok' : 'muted'
            },
            {
              label: 'Failures',
              value: totalFailures.toLocaleString(),
              hint: 'Requests with error outcome or 4xx/5xx status',
              icon: 'warning',
              valueMono: true,
              accent: totalFailures > 0 ? 'danger' : 'ok'
            }
          ]}
        />
      </Card>

      <Card className="usage-panel__card usage-panel__records-card">
        <SectionHeader
          title="Records"
          description="Lean table layout for scanning request cost, routing, and failure context."
          action={<span className="usage-panel__section-meta">{rows.length.toLocaleString()} visible rows</span>}
        />

        {rows.length === 0 ? (
          <EmptyState
            title="No usage records"
            detail="Once requests start flowing through the relay, each request will appear here with cost and routing context."
            icon="receipt_long"
          />
        ) : (
          <DataTable
            columns={columns}
            rows={rows}
            getRowKey={(r, i) => r.requestId ?? r.recordId ?? `row-${i}`}
            maxHeight="60vh"
            actionsHeader="Detail"
            actionsColumn={(r) => (
              <Button size="sm" variant="secondary" onClick={() => setSelectedRecord(r)}>
                View
              </Button>
            )}
          />
        )}
      </Card>

      {selectedRecord ? (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Usage detail"
          className="usage-panel__detail-shell"
          onClick={() => setSelectedRecord(null)}
        >
          <aside className="usage-panel__detail-panel" onClick={(e) => e.stopPropagation()}>
            <div className="usage-panel__detail-header">
              <div>
                <h2>Request detail</h2>
                <p>Full token, cost, routing, and error context.</p>
              </div>
              <Button size="sm" variant="secondary" onClick={() => setSelectedRecord(null)}>
                Close
              </Button>
            </div>

            <section className="usage-panel__detail-section">
              <h3>Basic</h3>
              <DetailRow label="Request ID" value={selectedRecord.requestId ?? selectedRecord.recordId} />
              <DetailRow label="Timestamp" value={formatTimestamp(selectedRecord.createdAt)} />
              <DetailRow label="Model" value={selectedRecord.model} />
              <DetailRow label="Group" value={selectedRecord.groupId ?? selectedRecord.poolLevelId} />
              <DetailRow label="Channel" value={selectedRecord.channelName ?? selectedRecord.channelId ?? selectedRecord.upstreamKeyId} />
              <DetailRow label="Status" value={`${selectedRecord.status ?? '—'} (${selectedRecord.outcome ?? '—'})`} />
            </section>

            <section className="usage-panel__detail-section">
              <h3>Tokens</h3>
              <DetailRow label="Input" value={formatInteger(selectedRecord.usage?.promptTokens)} />
              <DetailRow label="Output" value={formatInteger(selectedRecord.usage?.completionTokens)} />
              <DetailRow label="Cache Write" value={formatInteger(selectedRecord.usage?.cacheCreationInputTokens)} />
              <DetailRow label="Cache Read" value={formatInteger(selectedRecord.usage?.cacheReadInputTokens)} />
              <DetailRow label="Cache Hit Rate" value={formatRate(selectedRecord.cacheHitRate ?? selectedRecord.cost?.cacheHitRate)} />
            </section>

            <section className="usage-panel__detail-section">
              <h3>Cost</h3>
              <DetailRow label="Input Cost" value={formatCurrency(selectedRecord.cost?.inputCostUsd)} />
              <DetailRow label="Output Cost" value={formatCurrency(selectedRecord.cost?.outputCostUsd)} />
              <DetailRow label="Total Cost" value={formatCurrency(selectedRecord.cost?.totalCostUsd ?? selectedRecord.totalCostUsd)} />
            </section>

            <section className="usage-panel__detail-section">
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
    </div>
  );
}
