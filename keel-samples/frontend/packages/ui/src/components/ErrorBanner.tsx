import type { ReactNode } from 'react';

export function ErrorBanner({ message, variant = 'compact', action }: { message: string; variant?: 'compact' | 'large'; action?: ReactNode }) {
  return (
    <div role="alert" className={`keel-error-banner keel-error-banner-${variant}`}>
      <span>{message}</span>
      {action ? <div>{action}</div> : null}
    </div>
  );
}
