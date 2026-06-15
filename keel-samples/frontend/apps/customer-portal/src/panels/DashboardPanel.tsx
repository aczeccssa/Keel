import { useEffect, useMemo, useState } from 'react';
import {
  BarChart,
  Button,
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  IconButton,
  PageHeader,
  SectionHeader,
  StatGrid
} from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

interface UsageRecord {
  occurredAt?: string;
  createdAt?: string;
  model?: string;
  totalCostUsd?: number;
  requests?: number;
  requestId?: string;
}

interface Credits {
  balanceCredits?: number;
  balance?: number;
}

function dayKey(iso?: string): string {
  if (!iso) return '';
  return iso.slice(0, 10);
}

function build30DaySeries(records: UsageRecord[]) {
  const buckets = new Map<string, { cost: number; requests: number }>();
  for (const r of records) {
    const k = dayKey(r.occurredAt ?? r.createdAt);
    if (!k) continue;
    const cur = buckets.get(k) ?? { cost: 0, requests: 0 };
    cur.cost += r.totalCostUsd ?? 0;
    cur.requests += r.requests ?? 1;
    buckets.set(k, cur);
  }
  const days: Array<{ label: string; value: number }> = [];
  const now = new Date();
  for (let i = 29; i >= 0; i--) {
    const d = new Date(now.getTime() - i * 86_400_000);
    const key = d.toISOString().slice(0, 10);
    days.push({ label: key.slice(5), value: buckets.get(key)?.cost ?? 0 });
  }
  return { days, totalCost: days.reduce((s, d) => s + d.value, 0), totalRequests: records.length };
}

export function DashboardPanel({ api }: { api: CustomerPortalApi }) {
  const [credits, setCredits] = useState<Credits | null>(null);
  const [records, setRecords] = useState<UsageRecord[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [redeemOpen, setRedeemOpen] = useState(false);
  const [redeemCode, setRedeemCode] = useState('');
  const [redeemError, setRedeemError] = useState<string | null>(null);
  const [redeemSuccess, setRedeemSuccess] = useState<string | null>(null);
  const [redeeming, setRedeeming] = useState(false);

  function refresh() {
    setError(null);
    Promise.all([api.credits() as Promise<Credits>, api.usage() as Promise<{ records?: UsageRecord[] }>])
      .then(([c, u]) => {
        setCredits(c);
        setRecords(u.records ?? []);
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Unable to load dashboard'));
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api]);

  const series = useMemo(() => build30DaySeries(records), [records]);
  const sparkRequests = useMemo(() => series.days.map((d) => d.value * 1000), [series]);

  async function submitRedeem() {
    setRedeemError(null);
    setRedeemSuccess(null);
    setRedeeming(true);
    try {
      const result = (await api.redeem(redeemCode)) as { newBalance?: number; deltaCredits?: number };
      setRedeemSuccess(
        result.deltaCredits != null
          ? `Added ${result.deltaCredits} credits.`
          : 'Code redeemed.'
      );
      setRedeemCode('');
      refresh();
    } catch (err) {
      setRedeemError(err instanceof Error ? err.message : 'Redemption failed');
    } finally {
      setRedeeming(false);
    }
  }

  const recentCols: DataTableColumn<UsageRecord>[] = [
    {
      key: 'when',
      header: 'When',
      render: (r) => {
        const iso = r.occurredAt ?? r.createdAt;
        return iso ? iso.replace('T', ' ').slice(0, 19) : '—';
      }
    },
    { key: 'model', header: 'Model' },
    {
      key: 'cost',
      header: 'Cost (USD)',
      align: 'right',
      mono: true,
      render: (r) => `$${(r.totalCostUsd ?? 0).toFixed(4)}`
    }
  ];

  return (
    <>
      <PageHeader
        title="Dashboard"
        description="Your credit balance and recent API activity."
        actions={
          <Button variant="primary" size="sm" onClick={() => setRedeemOpen(true)}>
            Redeem code
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} onRetry={refresh} /> : null}
      <StatGrid
        items={[
          {
            label: 'Balance (credits)',
            value: (credits?.balanceCredits ?? credits?.balance ?? 0).toLocaleString(),
            accent: 'accent',
            icon: 'savings',
            valueMono: true
          },
          {
            label: 'Spent 30d (USD)',
            value: `$${series.totalCost.toFixed(2)}`,
            accent: 'ok',
            icon: 'trending_up',
            valueMono: true,
            sparkline: sparkRequests
          },
          {
            label: 'Requests 30d',
            value: series.totalRequests.toLocaleString(),
            accent: 'muted',
            icon: 'bolt'
          }
        ]}
      />
      <section className="keel-section">
        <SectionHeader
          title="30-day usage"
          description="Cost per day, grouped by UTC date so each day appears exactly once."
        />
        <BarChart hint="USD" data={series.days} />
      </section>
      <section className="keel-section">
        <SectionHeader title="Recent activity" description="Your last 10 API calls." />
        {records.length === 0 ? (
          <EmptyState
            title="No activity yet"
            detail="Once you start calling the API, your activity will appear here."
            icon="history"
          />
        ) : (
          <DataTable
            columns={recentCols}
            rows={records.slice(0, 10)}
            getRowKey={(r, i) => r.requestId ?? `row-${i}`}
          />
        )}
      </section>
      {redeemOpen ? (
        <div className="keel-modal-backdrop" onClick={() => setRedeemOpen(false)}>
          <div className="keel-modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
            <header className="keel-modal__head">
              <h2 className="keel-modal__title">Redeem code</h2>
              <IconButton ariaLabel="Close" size="sm" onClick={() => setRedeemOpen(false)}>
                <span className="material-symbols-outlined">close</span>
              </IconButton>
            </header>
            <div className="keel-modal__body">
              {redeemError ? <ErrorBanner message={redeemError} /> : null}
              {redeemSuccess ? (
                <div className="keel-error-banner" style={{ borderLeftColor: 'var(--keel-ok)', background: 'var(--keel-ok-soft)' }}>
                  <span className="material-symbols-outlined" style={{ color: 'var(--keel-ok)' }}>check_circle</span>
                  <span>{redeemSuccess}</span>
                </div>
              ) : null}
              <div className="keel-field">
                <label htmlFor="redeem">Code</label>
                <input
                  id="redeem"
                  type="text"
                  value={redeemCode}
                  onChange={(e) => setRedeemCode(e.target.value)}
                  placeholder="KEEL-XXXX-XXXX"
                />
              </div>
            </div>
            <footer className="keel-modal__foot">
              <Button variant="secondary" size="sm" onClick={() => setRedeemOpen(false)}>
                Cancel
              </Button>
              <Button variant="primary" size="sm" onClick={submitRedeem} disabled={redeeming || !redeemCode}>
                {redeeming ? 'Redeeming…' : 'Redeem'}
              </Button>
            </footer>
          </div>
        </div>
      ) : null}
    </>
  );
}
