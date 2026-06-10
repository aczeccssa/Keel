import { useEffect, useState } from 'react';
import { Card, DataTable, ErrorBanner } from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

export function BillingPanel({ api }: { api: CustomerPortalApi }) {
  const [rows, setRows] = useState<Array<Array<string>>>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.creditLedger()
      .then((data) => {
        const entries = (data as { entries?: Array<{ createdAt?: string; deltaCredits?: number; reason?: string }> }).entries ?? [];
        setRows(entries.map((entry) => [entry.createdAt ?? '—', String(entry.deltaCredits ?? 0), entry.reason ?? '—']));
      })
      .catch((err) => setError(err instanceof Error ? err.message : 'Unable to load credits'));
  }, [api]);

  return (
    <Card>
      <h1>Credits</h1>
      {error ? <ErrorBanner message={error} /> : null}
      <DataTable headers={['Date', 'Delta', 'Reason']} rows={rows} emptyText="No credit ledger entries yet." />
    </Card>
  );
}
