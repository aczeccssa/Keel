import { useEffect, useState } from 'react';
import {
  Button,
  Chip,
  EmptyState,
  ErrorBanner,
  KeyValue,
  PageHeader,
  SectionHeader
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface Membership {
  groupId?: string;
  priority?: number;
  weight?: number;
  enabled?: boolean;
}

interface TestRecord {
  timestamp: number;
  ok: boolean;
  latencyMs?: number | null;
}

interface ChannelStats {
  channelId: string;
  successRate7d: number;
  totalRequests7d: number;
  avgLatencyMs: number;
  totalCostUsd: number;
  totalTokens: number;
  recentTests: TestRecord[];
}

interface Channel {
  channelId?: string;
  name?: string;
  protocol?: string;
  baseUrl?: string;
  status?: string;
  enabled?: boolean;
  latencyMs?: number;
  lastTestLatencyMs?: number;
  lastTestError?: string;
  models?: Array<{ publicModelName?: string; enabled?: boolean }>;
  memberships?: Membership[];
  groupId?: string;
  priority?: number;
  weight?: number;
  stats?: ChannelStats;
}

function statusTone(s: string | undefined): 'ok' | 'warn' | 'danger' | 'muted' {
  switch ((s ?? '').toLowerCase()) {
    case 'healthy':
    case 'active':
    case 'ok':
    case 'up':
      return 'ok';
    case 'degraded':
    case 'idle':
    case 'warn':
      return 'warn';
    case 'down':
    case 'error':
    case 'disabled':
      return 'danger';
    default:
      return 'muted';
  }
}

function summarizeMemberships(c: Channel): string {
  const list =
    c.memberships && c.memberships.length > 0
      ? c.memberships
      : [{ groupId: c.groupId ?? 'default', priority: c.priority ?? 0, weight: c.weight ?? 100, enabled: c.enabled }];
  return list
    .map((m) => `${m.groupId ?? 'default'}: P${m.priority ?? 0} / W${m.weight ?? 100}`)
    .join(' · ');
}

function formatLatency(c: Channel): string {
  if (c.lastTestLatencyMs == null) return 'Not tested yet';
  if (c.lastTestError) return 'Last test: failed';
  return `Last test: ${c.lastTestLatencyMs} ms OK`;
}

export function ProvidersPanel({ api }: { api: AiGatewayApi }) {
  const [channels, setChannels] = useState<Channel[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.channels()
      .then(async (data) => {
        if (cancelled) return;
        const channelList = ((data as { channels?: Channel[] }).channels ?? []) as Channel[];

        // Load stats for each channel in parallel
        const channelsWithStats = await Promise.all(
          channelList.map(async (ch) => {
            try {
              if (!ch.channelId) return ch;
              const stats = await api.channelStats(ch.channelId, '7d') as ChannelStats;
              return { ...ch, stats };
            } catch {
              return ch;  // If stats fail to load, return channel without stats
            }
          })
        );

        if (cancelled) return;
        setChannels(channelsWithStats);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load channels');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  return (
    <>
      <PageHeader
        title="Channels"
        description="Upstream providers, their routing priority/weight, and last-test latency."
        actions={
          <Button variant="primary" size="sm" disabled title="Backend not yet exposed">
            + New channel
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} /> : null}
      <section className="keel-section">
        <SectionHeader title="Upstreams" description={`${channels.length} configured`} />
        {channels.length === 0 ? (
          <EmptyState
            title="No channels yet"
            detail="Add an upstream provider to start routing traffic."
            icon="hub"
          />
        ) : (
          <div className="keel-card-list">
            {channels.map((ch, i) => {
              const models = (ch.models ?? [])
                .filter((m) => m.enabled)
                .map((m) => m.publicModelName)
                .filter((n): n is string => !!n);
              const enabled = ch.enabled !== false;
              return (
                <article key={ch.channelId ?? ch.name ?? i} className="keel-list-card">
                  <div className="keel-list-card__header">
                    <h3 className="keel-list-card__title">{ch.name ?? '—'}</h3>
                    <Chip tone={statusTone(enabled ? ch.status : 'disabled')}>
                      {enabled ? (ch.status ?? 'healthy') : 'disabled'}
                    </Chip>
                  </div>
                  <div className="keel-list-card__body">
                    <KeyValue label="Protocol">{ch.protocol ?? '—'}</KeyValue>
                    <KeyValue label="Base URL">
                      <span className="keel-mono keel-truncate" style={{ maxWidth: 320, display: 'inline-block' }}>
                        {ch.baseUrl ?? '—'}
                      </span>
                    </KeyValue>
                    <KeyValue label="Routing">
                      <span className="keel-mono">{summarizeMemberships(ch)}</span>
                    </KeyValue>
                    <KeyValue label="Latency">{formatLatency(ch)}</KeyValue>

                    {/* Availability and sparkline */}
                    {ch.stats && (
                      <>
                        <KeyValue label="可用率 · 7天">
                          <span style={{
                            color: ch.stats.successRate7d >= 0.95 ? 'var(--keel-success, #10b981)' :
                                   ch.stats.successRate7d >= 0.80 ? 'var(--keel-warning, #f59e0b)' :
                                   'var(--keel-danger, #ef4444)',
                            fontWeight: 'bold'
                          }}>
                            {(ch.stats.successRate7d * 100).toFixed(2)}%
                          </span>
                        </KeyValue>

                        {ch.stats.recentTests && ch.stats.recentTests.length > 0 && (
                          <div style={{ marginTop: '8px' }}>
                            <div style={{ fontSize: '12px', color: 'var(--keel-muted, #6b7280)', marginBottom: '4px' }}>
                              近 {ch.stats.recentTests.length} 次测试
                            </div>
                            <div style={{ display: 'flex', gap: '1px', height: '20px', alignItems: 'flex-end' }}>
                              {ch.stats.recentTests.slice(0, 60).reverse().map((test, i) => (
                                <div
                                  key={i}
                                  style={{
                                    backgroundColor: test.ok ? 'var(--keel-success, #10b981)' : 'var(--keel-danger, #ef4444)',
                                    height: '16px',
                                    width: '3px',
                                    borderRadius: '1px'
                                  }}
                                  title={`${new Date(test.timestamp).toLocaleString()}: ${test.ok ? 'OK' : 'FAIL'}${test.latencyMs ? ` (${test.latencyMs}ms)` : ''}`}
                                />
                              ))}
                            </div>
                          </div>
                        )}

                        {/* Call statistics */}
                        {ch.stats.totalRequests7d > 0 && (
                          <KeyValue label="调用统计 · 7天">
                            <div>
                              {ch.stats.totalRequests7d.toLocaleString()} 请求 · 平均 {ch.stats.avgLatencyMs}ms
                            </div>
                            <div style={{ fontSize: '12px', color: 'var(--keel-muted, #6b7280)' }}>
                              成本: ${ch.stats.totalCostUsd.toFixed(2)} · Token: {(ch.stats.totalTokens / 1_000_000).toFixed(1)}M
                            </div>
                          </KeyValue>
                        )}
                      </>
                    )}

                    <KeyValue label="Models">
                      {models.length === 0 ? (
                        <span className="keel-muted">No models</span>
                      ) : (
                        <div className="keel-chip-cluster">
                          {models.map((m, mi) => (
                            <Chip key={mi} tone="neutral">
                              {m}
                            </Chip>
                          ))}
                        </div>
                      )}
                    </KeyValue>
                  </div>
                  <div className="keel-list-card__actions">
                    <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                      Test
                    </Button>
                    <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                      Edit
                    </Button>
                    <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                      {enabled ? 'Disable' : 'Enable'}
                    </Button>
                    <span style={{ flex: 1 }} />
                    <Button variant="danger" size="sm" disabled title="Backend not yet exposed">
                      Delete
                    </Button>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>
    </>
  );
}
