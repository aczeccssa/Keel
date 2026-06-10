import { useEffect, useState, type FormEvent } from 'react';
import { Button, Card, DataTable, ErrorBanner, FormField } from '@keel/sample-ui';
import type { CustomerPortalApi } from '../api/customerPortalApi';

export function KeysPanel({ api }: { api: CustomerPortalApi }) {
  const [keys, setKeys] = useState<Array<{ keyId: string; name: string; prefix?: string; routingGroupId?: string; monthlyBudgetCredits?: number }>>([]);
  const [error, setError] = useState<string | null>(null);
  const [rawKey, setRawKey] = useState<string | null>(null);

  async function refresh() {
    try {
      const data = await api.keys() as { keys?: Array<{ keyId: string; name: string; prefix?: string; routingGroupId?: string; monthlyBudgetCredits?: number }> };
      setKeys(data.keys ?? []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to load API keys');
    }
  }

  useEffect(() => { void refresh(); }, []);

  async function createKey(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const form = new FormData(event.currentTarget);
    try {
      const result = await api.createKey({
        name: String(form.get('name') || 'Default key'),
        routingGroupId: String(form.get('routingGroupId') || '').trim() || undefined,
        monthlyBudgetCredits: Number(form.get('monthlyBudgetCredits') || 0) || undefined
      });
      setRawKey((result as { rawKey?: string }).rawKey ?? null);
      event.currentTarget.reset();
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to create API key');
    }
  }

  async function deleteKey(keyId: string) {
    setError(null);
    try {
      await api.deleteKey(keyId);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to delete API key');
    }
  }

  return (
    <section className="keel-panel">
      <h1>API Keys</h1>
      <Card>
        {error ? <ErrorBanner message={error} /> : null}
        {rawKey ? (
          <div className="keel-key-reveal" role="status">
            <strong>New key</strong>
            <code>{rawKey}</code>
          </div>
        ) : null}
        <form className="keel-inline-form" onSubmit={createKey}>
          <FormField label="Name" name="name" placeholder="Production key" />
          <FormField label="Routing group" name="routingGroupId" placeholder="default" />
          <FormField label="Monthly budget" name="monthlyBudgetCredits" type="number" placeholder="2500" />
          <Button type="submit">Create key</Button>
        </form>
        <DataTable
          headers={['Name', 'Prefix', 'Group', 'Budget']}
          rows={keys.map((key) => [key.name, key.prefix ?? key.keyId, key.routingGroupId ?? 'default', key.monthlyBudgetCredits ?? 'unlimited'])}
          emptyText="No API keys yet."
          rowActions={(index) => (
            <Button type="button" size="sm" variant="danger" onClick={() => void deleteKey(keys[index].keyId)}>Delete</Button>
          )}
        />
      </Card>
    </section>
  );
}
