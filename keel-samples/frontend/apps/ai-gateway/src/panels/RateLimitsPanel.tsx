import { useEffect, useState } from 'react';
import {
  Button,
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
  limit?: number;
  windowSeconds?: number;
  refillPerSec?: number;
  bucketState?: { tokens: number; capacity: number };
  history?: number[];
}

function progressTone(pct: number): 'ok' | 'warn' | 'danger' {
  if (pct < 25) return 'danger';
  if (pct < 60) return 'warn';
  return 'ok';
}

export function RateLimitsPanel({ api }: { api: AiGatewayApi }) {
  const [rules, setRules] = useState<RateLimitRule[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.rateLimitRules()
      .then((data) => {
        if (cancelled) return;
        setRules(((data as { rules?: RateLimitRule[] }).rules ?? []) as RateLimitRule[]);
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
        actions={
          <Button variant="primary" size="sm" disabled title="Backend not yet exposed">
            + New rule
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} /> : null}
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
              const tokens = r.bucketState?.tokens ?? 0;
              const capacity = r.bucketState?.capacity ?? r.limit ?? 0;
              const pct = capacity > 0 ? Math.round((tokens / capacity) * 100) : 0;
              const hasBucket = !!r.bucketState;
              return (
                <article key={r.ruleId ?? i} className="keel-list-card">
                  <div className="keel-list-card__header">
                    <h3 className="keel-list-card__title">
                      <span className="keel-mono">{r.ruleId ?? '—'}</span>
                    </h3>
                    <Chip tone="accent">{r.scope ?? 'global'}</Chip>
                  </div>
                  <div className="keel-list-card__body">
                    <KeyValue label="Limit">
                      <span className="keel-mono">
                        {r.limit ?? '—'}
                        {r.windowSeconds ? ` / ${r.windowSeconds}s` : ''}
                      </span>
                    </KeyValue>
                    <KeyValue label="Refill">
                      <span className="keel-mono">{r.refillPerSec != null ? `${r.refillPerSec} tok/s` : '—'}</span>
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
                  <div className="keel-list-card__actions">
                    <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                      Edit
                    </Button>
                    <Button variant="secondary" size="sm" disabled title="Backend not yet exposed">
                      Disable
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
