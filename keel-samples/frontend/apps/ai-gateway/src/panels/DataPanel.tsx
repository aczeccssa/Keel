import { useEffect, useState, type ReactNode } from 'react';
import { Card, DataTable, type DataTableColumn, EmptyState, ErrorBanner } from '@keel/sample-ui';

export interface DataPanelProps<Row extends Record<string, unknown> = Record<string, unknown>> {
  title: string;
  description?: string;
  load: () => Promise<unknown>;
  columns: DataTableColumn<Row>[];
  rows: (data: unknown) => Row[];
  emptyText: string;
  emptyIcon?: string;
  actionsColumn?: (row: Row) => ReactNode;
  maxHeight?: string;
}

export function DataPanel<Row extends Record<string, unknown> = Record<string, unknown>>({
  title,
  description,
  load,
  columns,
  rows,
  emptyText,
  emptyIcon,
  actionsColumn,
  maxHeight
}: DataPanelProps<Row>) {
  const [data, setData] = useState<unknown>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    load()
      .then((d) => {
        if (!cancelled) setData(d);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : `Unable to load ${title}`);
      });
    return () => {
      cancelled = true;
    };
  }, [load, title]);

  if (error) {
    return (
      <Card>
        <h1>{title}</h1>
        {description ? <p className="keel-muted">{description}</p> : null}
        <ErrorBanner message={error} />
      </Card>
    );
  }

  const list = data == null ? [] : rows(data);

  return (
    <Card>
      <h1>{title}</h1>
      {description ? <p className="keel-muted">{description}</p> : null}
      {list.length === 0 ? (
        <EmptyState title={emptyText} icon={emptyIcon ?? 'inbox'} />
      ) : (
        <DataTable
          columns={columns}
          rows={list}
          emptyText={emptyText}
          actionsColumn={actionsColumn}
          maxHeight={maxHeight}
        />
      )}
    </Card>
  );
}
