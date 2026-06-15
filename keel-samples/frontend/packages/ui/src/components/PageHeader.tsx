import type { ReactNode } from 'react';

export interface PageHeaderProps {
  title: string;
  description?: string;
  actions?: ReactNode;
}

export function PageHeader({ title, description, actions }: PageHeaderProps) {
  return (
    <header className="keel-page-header">
      <div>
        <h1>{title}</h1>
        {description ? <p className="keel-page-header__desc">{description}</p> : null}
      </div>
      {actions ? <div className="keel-page-header__actions">{actions}</div> : null}
    </header>
  );
}
