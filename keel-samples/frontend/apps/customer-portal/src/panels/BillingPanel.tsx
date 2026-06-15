import { useEffect, useState } from 'react';
import {
  Button,
  DataTable,
  type DataTableColumn,
  EmptyState,
  ErrorBanner,
  IconButton,
  PageHeader
} from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

interface LedgerEntry {
  createdAt?: string;
  deltaCredits?: number;
  reason?: string;
  balanceAfter?: number;
}

function formatDate(iso?: string): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  return t.toLocaleString();
}

export function BillingPanel({ api }: { api: CustomerPortalApi }) {
  const [rows, setRows] = useState<LedgerEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [redeemOpen, setRedeemOpen] = useState(false);
  const [redeemCode, setRedeemCode] = useState('');
  const [redeemError, setRedeemError] = useState<string | null>(null);
  const [redeemSuccess, setRedeemSuccess] = useState<string | null>(null);
  const [redeeming, setRedeeming] = useState(false);

  function refresh() {
    setError(null);
    api.creditLedger()
      .then((data) => {
        setRows(((data as { entries?: LedgerEntry[] }).entries ?? []) as LedgerEntry[]);
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Unable to load credits'));
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function submitRedeem() {
    setRedeemError(null);
    setRedeemSuccess(null);
    setRedeeming(true);
    try {
      const result = (await api.redeem(redeemCode)) as { newBalance?: number; deltaCredits?: number };
      setRedeemSuccess(
        result.deltaCredits != null ? `Added ${result.deltaCredits} credits.` : 'Code redeemed.'
      );
      setRedeemCode('');
      refresh();
    } catch (err) {
      setRedeemError(err instanceof Error ? err.message : 'Redemption failed');
    } finally {
      setRedeeming(false);
    }
  }

  const columns: DataTableColumn<LedgerEntry>[] = [
    { key: 'createdAt', header: 'Date', render: (r) => formatDate(r.createdAt) },
    {
      key: 'delta',
      header: 'Delta',
      align: 'right',
      mono: true,
      render: (r) => {
        const d = r.deltaCredits ?? 0;
        return (
          <span className={d >= 0 ? 'keel-pos' : 'keel-neg'}>
            {d >= 0 ? '+' : ''}
            {d}
          </span>
        );
      }
    },
    { key: 'reason', header: 'Reason' },
    {
      key: 'balanceAfter',
      header: 'Balance after',
      align: 'right',
      mono: true,
      render: (r) => (r.balanceAfter ?? '—')
    }
  ];

  return (
    <>
      <PageHeader
        title="Credits"
        description="Your credit ledger and balance history."
        actions={
          <Button variant="primary" size="sm" onClick={() => setRedeemOpen(true)}>
            Redeem code
          </Button>
        }
      />
      {error ? <ErrorBanner message={error} onRetry={refresh} /> : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No credit ledger entries yet"
          detail="Redeem a code or call the API to see transactions appear here."
          icon="receipt_long"
          action={
            <Button variant="primary" size="sm" onClick={() => setRedeemOpen(true)}>
              Redeem code
            </Button>
          }
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(r, i) => `${r.createdAt ?? ''}-${i}`}
          maxHeight="calc(100vh - 220px)"
        />
      )}
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
                <div
                  className="keel-error-banner"
                  style={{ borderLeftColor: 'var(--keel-ok)', background: 'var(--keel-ok-soft)' }}
                >
                  <span className="material-symbols-outlined" style={{ color: 'var(--keel-ok)' }}>check_circle</span>
                  <span>{redeemSuccess}</span>
                </div>
              ) : null}
              <div className="keel-field">
                <label htmlFor="redeem-code">Code</label>
                <input
                  id="redeem-code"
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
