import type { ReactNode } from 'react';

export interface EmptyStateProps {
  title: string;
  detail?: string;
  icon?: string;
  action?: ReactNode;
}

export function EmptyState({ title, detail, icon = 'inbox', action }: EmptyStateProps) {
  return (
    <div className="keel-empty">
      <span className="keel-empty__icon material-symbols-outlined" aria-hidden="true">
        {icon}
      </span>
      <strong className="keel-empty__title">{title}</strong>
      {detail ? <p className="keel-empty__detail">{detail}</p> : null}
      {action ? <div className="keel-empty__action">{action}</div> : null}
    </div>
  );
}
