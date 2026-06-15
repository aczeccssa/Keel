import type { ReactNode } from 'react';

export type ChipTone = 'ok' | 'warn' | 'danger' | 'accent' | 'muted' | 'neutral';

export interface ChipProps {
  tone?: ChipTone;
  children: ReactNode;
  className?: string;
}

export function Chip({ tone = 'neutral', children, className = '' }: ChipProps) {
  return <span className={`keel-chip keel-chip--${tone} ${className}`.trim()}>{children}</span>;
}
