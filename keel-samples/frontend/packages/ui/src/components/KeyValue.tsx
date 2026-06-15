import type { ReactNode } from 'react';

export interface KeyValueProps {
  label: string;
  children: ReactNode;
  inline?: boolean;
  className?: string;
}

export function KeyValue({ label, children, inline = false, className = '' }: KeyValueProps) {
  return (
    <div className={`keel-kv ${inline ? 'keel-kv--inline' : ''} ${className}`.trim()}>
      <span className="keel-kv__label">{label}</span>
      <span className="keel-kv__value">{children}</span>
    </div>
  );
}
