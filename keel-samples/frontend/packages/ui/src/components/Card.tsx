import type { PropsWithChildren, ReactNode } from 'react';

export interface CardProps {
  className?: string;
  title?: string;
  detail?: string;
  actions?: ReactNode;
  tone?: 'default' | 'raised' | 'subtle';
}

export function Card({ children, className = '', title, detail, actions, tone = 'default' }: PropsWithChildren<CardProps>) {
  return (
    <section className={`keel-card keel-card-${tone} ${className}`.trim()}>
      {title || detail || actions ? (
        <header className="keel-card-header">
          <div>
            {title ? <h2>{title}</h2> : null}
            {detail ? <p>{detail}</p> : null}
          </div>
          {actions ? <div className="keel-card-actions">{actions}</div> : null}
        </header>
      ) : null}
      {children}
    </section>
  );
}
