export interface DonutSlice {
  label: string;
  value: number;
  color?: string;
}

export interface DonutChartProps {
  title?: string;
  hint?: string;
  slices: DonutSlice[];
  size?: number;
  centerLabel?: string;
  centerHint?: string;
}

const PALETTE = [
  'var(--keel-accent)',
  'var(--keel-warn)',
  'var(--keel-danger)',
  'var(--keel-ok)',
  'var(--keel-accent-strong)',
  'var(--keel-muted)'
];

export function DonutChart({
  title,
  hint,
  slices,
  size = 200,
  centerLabel,
  centerHint
}: DonutChartProps) {
  const total = slices.reduce((s, x) => s + Math.max(0, x.value), 0) || 1;
  const r = size / 2;
  const inner = r * 0.6;
  const cx = r;
  const cy = r;

  return (
    <section className="keel-chart keel-chart--donut">
      {(title || hint) ? (
        <header className="keel-chart__header">
          {title ? <h3 className="keel-chart__title">{title}</h3> : <span />}
          {hint ? <span className="keel-chart__hint">{hint}</span> : null}
        </header>
      ) : null}
      <div className="keel-chart__body">
        <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size} role="img" aria-label={title ?? 'Donut chart'}>
          {slices.length === 0 || total === 0 ? (
            <circle cx={cx} cy={cy} r={inner} fill="var(--keel-surface-soft)" stroke="currentColor" strokeWidth={1} />
          ) : (
            slices.reduce<{ nodes: React.ReactNode[]; startAngle: number }>(
              (acc, slice, i) => {
                const value = Math.max(0, slice.value);
                if (value === 0) return acc;
                const fraction = value / total;
                const endAngle = acc.startAngle + fraction * Math.PI * 2;
                const path = describeDonutSlice(cx, cy, r, inner, acc.startAngle, endAngle);
                const fill = slice.color ?? PALETTE[i % PALETTE.length];
                acc.nodes.push(
                  <path
                    key={i}
                    d={path}
                    fill={fill}
                    stroke="currentColor"
                    strokeWidth={1}
                  />
                );
                return { nodes: acc.nodes, startAngle: endAngle };
              },
              { nodes: [], startAngle: -Math.PI / 2 }
            ).nodes
          )}
          {centerLabel ? (
            <text
              x={cx}
              y={cy}
              textAnchor="middle"
              dominantBaseline="middle"
              fontFamily="var(--keel-font-display)"
              fontSize={Math.max(14, r * 0.22)}
              fill="currentColor"
            >
              {centerLabel}
            </text>
          ) : null}
          {centerHint ? (
            <text
              x={cx}
              y={cy + r * 0.28}
              textAnchor="middle"
              fontFamily="var(--keel-font-mono)"
              fontSize={Math.max(8, r * 0.1)}
              fill="currentColor"
              opacity={0.6}
              style={{ textTransform: 'uppercase', letterSpacing: '0.1em' }}
            >
              {centerHint}
            </text>
          ) : null}
        </svg>
        <ul className="keel-chart__legend" aria-label="Legend">
          {slices.map((s, i) => {
            const pct = total > 0 ? Math.round((s.value / total) * 100) : 0;
            return (
              <li key={i} className="keel-chart__legend-row">
                <span
                  className="keel-chart__legend-swatch"
                  style={{ background: s.color ?? PALETTE[i % PALETTE.length] }}
                />
                <span className="keel-chart__legend-label">{s.label}</span>
                <span className="keel-chart__legend-value">{pct}%</span>
              </li>
            );
          })}
        </ul>
      </div>
    </section>
  );
}

function describeDonutSlice(
  cx: number,
  cy: number,
  rOuter: number,
  rInner: number,
  startAngle: number,
  endAngle: number
): string {
  const largeArc = endAngle - startAngle > Math.PI ? 1 : 0;
  const x1 = cx + rOuter * Math.cos(startAngle);
  const y1 = cy + rOuter * Math.sin(startAngle);
  const x2 = cx + rOuter * Math.cos(endAngle);
  const y2 = cy + rOuter * Math.sin(endAngle);
  const x3 = cx + rInner * Math.cos(endAngle);
  const y3 = cy + rInner * Math.sin(endAngle);
  const x4 = cx + rInner * Math.cos(startAngle);
  const y4 = cy + rInner * Math.sin(startAngle);
  return [
    `M ${x1} ${y1}`,
    `A ${rOuter} ${rOuter} 0 ${largeArc} 1 ${x2} ${y2}`,
    `L ${x3} ${y3}`,
    `A ${rInner} ${rInner} 0 ${largeArc} 0 ${x4} ${y4}`,
    'Z'
  ].join(' ');
}
