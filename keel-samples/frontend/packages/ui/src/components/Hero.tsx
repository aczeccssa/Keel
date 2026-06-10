import type { ReactNode } from 'react';

export interface HeroProps {
  eyebrow: string;
  title: string;
  copy: string;
  primaryAction: ReactNode;
  secondaryAction?: ReactNode;
  preview: ReactNode;
  proof?: ReactNode;
}

export function Hero({ eyebrow, title, copy, primaryAction, secondaryAction, preview, proof }: HeroProps) {
  return (
    <section className="keel-hero">
      <div className="keel-hero-copy">
        <p className="keel-eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p>{copy}</p>
        <div className="keel-hero-actions">{primaryAction}{secondaryAction}</div>
        {proof ? <div className="keel-hero-proof">{proof}</div> : null}
      </div>
      <div className="keel-hero-preview">{preview}</div>
    </section>
  );
}
