import { useEffect, useState, type ReactNode } from 'react';
import { Card, DataTable, EmptyState, ErrorBanner, SkeletonBlock, Toolbar } from '@keel/sample-ui';

export interface DataPanelProps<T = unknown> {
  title: string;
  detail?: string;
  load: () => Promise<T>;
  children?: (data: T, refresh: () => void) => ReactNode;
  empty?: (data: T) => boolean;
  actions?: ReactNode;
  columns?: string[];
  rows?: (data: T) => Array<Array<string | number>>;
  emptyText?: string;
}

export function DataPanel<T = unknown>({ title, detail, load, children, empty, actions, columns, rows, emptyText = 'No records yet.' }: DataPanelProps<T>) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    let active = true;
    setError(null);
    load()
      .then((next) => { if (active) setData(next); })
      .catch((err) => { if (active) setError(err instanceof Error ? err.message : `Unable to load ${title}`); });
    return () => { active = false; };
  }, [nonce]);

  const refresh = () => setNonce((value) => value + 1);
  const isEmpty = data != null && (empty?.(data) ?? (rows ? rows(data).length === 0 : false));

  return (
    <section className="keel-panel">
      <Toolbar title={title} detail={detail} actions={actions} />
      {error ? <ErrorBanner message={error} /> : null}
      {data == null ? (
        <Card><SkeletonBlock lines={4} /></Card>
      ) : isEmpty ? (
        <Card><EmptyState title={emptyText} detail="New data appears here as soon as it is available." /></Card>
      ) : children ? (
        children(data, refresh)
      ) : columns && rows ? (
        <Card><DataTable headers={columns} rows={rows(data)} emptyText={emptyText} /></Card>
      ) : null}
    </section>
  );
}
