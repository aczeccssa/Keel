import { useEffect, useState, type ReactNode } from 'react';
import { Card, EmptyState, ErrorBanner, SkeletonBlock, Toolbar } from '@keel/sample-ui';

export function DataPanel<T>({
  title,
  detail,
  load,
  children,
  empty,
  actions
}: {
  title: string;
  detail?: string;
  load: () => Promise<T>;
  children: (data: T, refresh: () => void) => ReactNode;
  empty?: (data: T) => boolean;
  actions?: ReactNode;
}) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    let active = true;
    setError(null);
    load()
      .then((next) => { if (active) setData(next); })
      .catch((err) => { if (active) setError(err instanceof Error ? err.message : 'Request failed'); });
    return () => { active = false; };
  }, [nonce]);

  const refresh = () => setNonce((value) => value + 1);

  return (
    <section className="keel-panel">
      <Toolbar title={title} detail={detail} actions={actions} />
      {error ? <ErrorBanner message={error} /> : null}
      {data == null ? (
        <Card><SkeletonBlock lines={4} /></Card>
      ) : empty?.(data) ? (
        <Card><EmptyState title="No records yet" detail="New data appears here as soon as it is available." /></Card>
      ) : (
        children(data, refresh)
      )}
    </section>
  );
}
