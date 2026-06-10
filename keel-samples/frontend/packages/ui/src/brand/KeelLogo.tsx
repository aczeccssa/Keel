export interface KeelLogoProps {
  label?: string;
  className?: string;
  compact?: boolean;
}

export function KeelLogo({ label = 'Keel', className, compact = false }: KeelLogoProps) {
  return (
    <span className={className ?? 'keel-logo'} aria-label={label}>
      <span className="keel-logo-mark" aria-hidden="true">
        <svg viewBox="0 0 48 48" role="img" focusable="false">
          <path className="keel-logo-soft" d="M10 11.5C10 8.5 12.5 6 15.5 6h17C35.5 6 38 8.5 38 11.5v25c0 3-2.5 5.5-5.5 5.5h-17C12.5 42 10 39.5 10 36.5v-25Z" />
          <path className="keel-logo-line" d="M17 15h14M17 24h14M17 33h14" />
          <path className="keel-logo-node" d="M17 15l7 9-7 9M31 15l-7 9 7 9" />
        </svg>
      </span>
      {!compact ? <span className="keel-logo-word">{label}</span> : null}
    </span>
  );
}
