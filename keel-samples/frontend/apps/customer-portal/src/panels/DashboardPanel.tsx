import { useEffect, useState } from 'react';
import { Card, ErrorBanner, StatGrid } from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

export function DashboardPanel({ api }: { api: CustomerPortalApi }) {
  const [error, setError] = useState<string | null>(null);
  const [summary, setSummary] = useState<{ credits?: unknown; usage?: unknown }>({});

  useEffect(() => {
    Promise.all([api.credits(), api.usage()])
      .then(([credits, usage]) => setSummary({ credits, usage }))
      .catch((err) => setError(err instanceof Error ? err.message : 'Unable to load dashboard'));
  }, [api]);

  return (
    <div className="panel-stack">
      <h1>Dashboard</h1>
      {error ? <ErrorBanner message={error} /> : null}
      <StatGrid items={[
        { label: 'Credits', value: JSON.stringify(summary.credits ?? '—'), hint: 'balance' },
        { label: 'Usage', value: JSON.stringify(summary.usage ?? '—'), hint: 'recent' }
      ]} />
    </div>
  );
}
