import { useEffect, useState } from 'react';
import { Button, Card, DataTable, ErrorBanner } from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

export function KeysPanel({ api }: { api: CustomerPortalApi }) {
  const [rows, setRows] = useState<Array<Array<string>>>([]);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    try {
      const data = await api.keys() as { keys?: Array<{ keyId: string; name: string; prefix?: string }> };
      setRows((data.keys ?? []).map((key) => [key.name, key.prefix ?? key.keyId, key.keyId]));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to load API keys');
    }
  }

  useEffect(() => { void refresh(); }, []);

  return (
    <Card>
      <h1>API Keys</h1>
      {error ? <ErrorBanner message={error} /> : null}
      <Button type="button" onClick={() => void api.createKey({ name: 'Default key' }).then(refresh)}>Create key</Button>
      <DataTable headers={['Name', 'Prefix', 'ID']} rows={rows} emptyText="No API keys yet." />
    </Card>
  );
}
