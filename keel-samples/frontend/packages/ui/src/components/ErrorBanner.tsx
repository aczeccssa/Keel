import type { ReactNode } from 'react';

export interface ErrorBannerProps {
  message: string;
  onRetry?: () => void;
  onDismiss?: () => void;
}

export function ErrorBanner({ message, onRetry, onDismiss }: ErrorBannerProps) {
  return (
    <div role="alert" className="keel-error-banner">
      <span className="keel-error-banner__icon material-symbols-outlined" aria-hidden="true">
        error
      </span>
      <span className="keel-error-banner__message">{message}</span>
      {(onRetry || onDismiss) ? (
        <span className="keel-error-banner__actions">
          {onRetry ? (
            <button type="button" className="keel-button keel-button--sm keel-button--secondary" onClick={onRetry}>
              Retry
            </button>
          ) : null}
          {onDismiss ? (
            <button
              type="button"
              className="keel-icon-button keel-icon-button--sm"
              aria-label="Dismiss"
              onClick={onDismiss}
            >
              <span className="material-symbols-outlined">close</span>
            </button>
          ) : null}
        </span>
      ) : null}
    </div>
  );
}
