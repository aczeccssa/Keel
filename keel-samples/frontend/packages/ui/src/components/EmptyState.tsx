import type { ReactNode } from 'react';

export function EmptyState({ title, detail, action }: { title: string; detail?: string; action?: ReactNode }) {
  return (
    <div className="keel-empty">
      <span className="keel-empty-mark" aria-hidden="true" />
      <strong>{title}</strong>
      {detail ? <p>{detail}</p> : null}
      {action ? <div className="keel-empty-action">{action}</div> : null}
    </div>
  );
}
