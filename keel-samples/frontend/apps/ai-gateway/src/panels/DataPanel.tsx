import { useEffect, useState } from 'react';
import { Card, DataTable, ErrorBanner } from '@keel/sample-ui';

export interface DataPanelProps {
  title: string;
  load: () => Promise<unknown>;
  columns: string[];
  rows: (data: unknown) => Array<Array<string | number>>;
  emptyText: string;
}

export function DataPanel({ title, load, columns, rows, emptyText }: DataPanelProps) {
  const [data, setData] = useState<unknown>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    load().then(setData).catch((err) => setError(err instanceof Error ? err.message : `Unable to load ${title}`));
  }, [load, title]);

  return (
    <Card>
      <h1>{title}</h1>
      {error ? <ErrorBanner message={error} /> : null}
      <DataTable headers={columns} rows={data == null ? [] : rows(data)} emptyText={emptyText} />
    </Card>
  );
}
