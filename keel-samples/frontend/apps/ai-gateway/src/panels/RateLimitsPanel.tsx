import { useEffect, useState } from 'react';
import {
  Chip,
  EmptyState,
  ErrorBanner,
  KeyValue,
  PageHeader,
  SectionHeader,
  SparklineChart
} from '@keel/sample-ui';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface RateLimitRule {
  ruleId?: string;
  scope?: string;
  name?: string;
  dimension?: string;
  limit?: number;
  capacity?: number;
  windowSeconds?: number;
  refillPerSec?: number;
  refillRatePerSec?: number;
  bucketState?: { tokens: number; capacity: number };
  history?: number[];
}

interface RateLimitSnapshot {
  totalRejected?: number;
  totalAllowed?: number;
  bucketCount?: number;
  ruleCount?: number;
  topBuckets?: Array<{
    ruleId?: string;
    remainingTokens?: number;
    capacity?: number;
    totalRejected?: number;
    totalAllowed?: number;
  }>;
  recentRejections?: Array<{
    ruleId?: string;
    dimension?: string;
    value?: string;
    reason?: string;
    retryAfterSeconds?: number;
  }>;
}

function progressTone(pct: number): 'ok' | 'warn' | 'danger' {
  if (pct < 25) return 'danger';
  if (pct < 60) return 'warn';
  return 'ok';
}

export function RateLimitsPanel({ api }: { api: AiGatewayApi }) {
  const [rules, setRules] = useState<RateLimitRule[]>([]);
  const [snapshot, setSnapshot] = useState<RateLimitSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api.rateLimitRules(),
      api.rateLimitSnapshot()
    ])
      .then(([rulesData, snapshotData]) => {
        if (cancelled) return;
        setRules(((rulesData as { rules?: RateLimitRule[] }).rules ?? []) as RateLimitRule[]);
        setSnapshot((snapshotData as RateLimitSnapshot) ?? null);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load rate limits');
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  return (
    <>
      <PageHeader
        title="Rate Limits"
        description="Token bucket rules and current consumption."
      />
      {error ? <ErrorBanner message={error} /> : null}
      <section className="keel-section">
        <SectionHeader title="Monitor" description="Live rejection and bucket pressure." />
        <div className="keel-kv-grid">
          <KeyValue label="Rules">{snapshot?.ruleCount ?? rules.length}</KeyValue>
          <KeyValue label="Active buckets">{snapshot?.bucketCount ?? 0}</KeyValue>
          <KeyValue label="Allowed">{snapshot?.totalAllowed ?? 0}</KeyValue>
          <KeyValue label="Rejected">{snapshot?.totalRejected ?? 0}</KeyValue>
        </div>
      </section>
      <section className="keel-section">
        <SectionHeader title="Rules" description={`${rules.length} configured`} />
        {rules.length === 0 ? (
          <EmptyState
            title="No rate limit rules"
            detail="Define token-bucket rules to throttle traffic per customer or per key."
            icon="token"
          />
        ) : (
          <div className="keel-card-list">
            {rules.map((r, i) => {
              const bucket = (snapshot?.topBuckets ?? []).find((entry) => entry.ruleId === r.ruleId);
              const tokens = r.bucketState?.tokens ?? bucket?.remainingTokens ?? 0;
              const capacity = r.bucketState?.capacity ?? bucket?.capacity ?? r.capacity ?? r.limit ?? 0;
              const pct = capacity > 0 ? Math.round((tokens / capacity) * 100) : 0;
              const hasBucket = !!r.bucketState || !!bucket;
              return (
                <article key={r.ruleId ?? i} className="keel-list-card">
                  <div className="keel-list-card__header">
                    <h3 className="keel-list-card__title">
                      <span className="keel-mono">{r.ruleId ?? '—'}</span>
                    </h3>
                    <Chip tone="accent">{r.scope ?? r.dimension ?? 'global'}</Chip>
                  </div>
                  <div className="keel-list-card__body">
                    <KeyValue label="Limit">
                      <span className="keel-mono">
                        {r.capacity ?? r.limit ?? '—'}
                        {r.windowSeconds ? ` / ${r.windowSeconds}s` : ''}
                      </span>
                    </KeyValue>
                    <KeyValue label="Refill">
                      <span className="keel-mono">{(r.refillPerSec ?? r.refillRatePerSec) != null ? `${r.refillPerSec ?? r.refillRatePerSec} tok/s` : '—'}</span>
                    </KeyValue>
                    <KeyValue label="Active bucket">
                      {hasBucket ? (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 180 }}>
                          <div className="keel-progress-row">
                            <div className="keel-progress" style={{ flex: 1 }}>
                              <div
                                className={`keel-progress__bar keel-progress__bar--${progressTone(pct)}`}
                                style={{ width: `${pct}%` }}
                              />
                            </div>
                            <span className="keel-mono" style={{ fontSize: 11, whiteSpace: 'nowrap' }}>
                              {tokens} / {capacity}
                            </span>
                          </div>
                          {bucket ? (
                            <span style={{ color: 'var(--keel-muted)', fontSize: 12 }}>
                              allowed {bucket.totalAllowed ?? 0} · rejected {bucket.totalRejected ?? 0}
                            </span>
                          ) : null}
                          {r.history && r.history.length > 1 ? (
                            <SparklineChart values={r.history} title="Bucket over time" />
                          ) : null}
                        </div>
                      ) : (
                        <EmptyState
                          title="No active buckets"
                          detail="No traffic has hit this rule yet."
                          icon="token"
                        />
                      )}
                    </KeyValue>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>
      <section className="keel-section">
        <SectionHeader title="Recent Rejections" description="Latest rate-limit denials emitted by the gateway." />
        {(snapshot?.recentRejections?.length ?? 0) === 0 ? (
          <EmptyState
            title="No recent rejections"
            detail="Rejected requests will appear here with the rule and retry delay."
            icon="shield"
          />
        ) : (
          <div className="keel-card-list">
            {snapshot?.recentRejections?.map((entry, index) => (
              <article key={`${entry.ruleId}-${entry.value}-${index}`} className="keel-list-card">
                <div className="keel-list-card__header">
                  <h3 className="keel-list-card__title">
                    <span className="keel-mono">{entry.ruleId ?? '—'}</span>
                  </h3>
                  <Chip tone="danger">{entry.reason ?? 'rejected'}</Chip>
                </div>
                <div className="keel-list-card__body">
                  <KeyValue label="Dimension">{entry.dimension ?? '—'}</KeyValue>
                  <KeyValue label="Value"><span className="keel-mono">{entry.value ?? '—'}</span></KeyValue>
                  <KeyValue label="Retry after"><span className="keel-mono">{entry.retryAfterSeconds ?? 0}s</span></KeyValue>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </>
  );
}
