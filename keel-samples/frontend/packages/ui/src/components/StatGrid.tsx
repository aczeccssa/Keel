import type { ReactNode } from 'react';

export type StatAccent = 'accent' | 'ok' | 'warn' | 'danger' | 'muted';

export interface StatItem {
  label: string;
  value: string;
  hint?: string;
  accent?: StatAccent;
  icon?: string;
  sparkline?: number[];
  valueMono?: boolean;
}

export function StatGrid({ items }: { items: StatItem[] }) {
  return (
    <div className="keel-stat-grid">
      {items.map((item) => (
        <div
          key={item.label}
          className={`keel-stat ${item.accent ? `keel-stat--${item.accent}` : ''}`.trim()}
        >
          {item.icon ? (
            <span className="keel-stat__icon material-symbols-outlined" aria-hidden="true">
              {item.icon}
            </span>
          ) : null}
          <span className="keel-stat__label">{item.label}</span>
          <strong
            className={`keel-stat__value ${item.valueMono ? 'keel-stat__value--mono' : ''}`.trim()}
          >
            {item.value}
          </strong>
          {item.hint ? <span className="keel-stat__hint">{item.hint}</span> : null}
        </div>
      ))}
    </div>
  );
}

export function renderSparkline(values: number[] | undefined): ReactNode {
  if (!values || values.length < 2) return null;
  const width = 120;
  const height = 28;
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const range = max - min || 1;
  const stepX = width / (values.length - 1);
  const points = values
    .map((v, i) => `${(i * stepX).toFixed(1)},${(height - ((v - min) / range) * height).toFixed(1)}`)
    .join(' ');
  return (
    <svg
      className="keel-stat__sparkline"
      viewBox={`0 0 ${width} ${height}`}
      width={width}
      height={height}
      aria-hidden="true"
    >
      <polyline
        points={points}
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        strokeLinejoin="miter"
        strokeLinecap="square"
      />
    </svg>
  );
}
