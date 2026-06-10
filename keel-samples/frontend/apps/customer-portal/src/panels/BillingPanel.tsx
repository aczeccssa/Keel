import { useEffect, useState, type FormEvent } from 'react';
import { Button, Card, DataTable, ErrorBanner, FormField, StatGrid } from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

export function BillingPanel({ api }: { api: CustomerPortalApi }) {
  const [rows, setRows] = useState<Array<Array<string>>>([]);
  const [balance, setBalance] = useState<number | string>('—');
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    try {
      const [credits, ledger] = await Promise.all([api.credits(), api.creditLedger()]);
      setBalance((credits as { balanceCredits?: number }).balanceCredits ?? '—');
      const entries = (ledger as { entries?: Array<{ createdAt?: string; deltaCredits?: number; reason?: string }> }).entries ?? [];
      setRows(entries.map((entry) => [entry.createdAt ?? '—', String(entry.deltaCredits ?? 0), entry.reason ?? '—']));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to load credits');
    }
  }

  useEffect(() => { void refresh(); }, []);

  async function redeem(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const form = new FormData(event.currentTarget);
    try {
      await api.redeem(String(form.get('code') || '').trim());
      event.currentTarget.reset();
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to redeem code');
    }
  }

  return (
    <section className="keel-panel">
      <h1>Credits</h1>
      <StatGrid items={[{ label: 'Balance', value: String(balance), hint: 'credits available' }]} />
      <Card>
        {error ? <ErrorBanner message={error} /> : null}
        <form className="keel-inline-form" onSubmit={redeem}>
          <FormField label="Redemption code" name="code" placeholder="WELCOME1000" />
          <Button type="submit">Redeem</Button>
        </form>
        <DataTable headers={['Date', 'Delta', 'Reason']} rows={rows} emptyText="No credit ledger entries yet." />
      </Card>
    </section>
  );
}
