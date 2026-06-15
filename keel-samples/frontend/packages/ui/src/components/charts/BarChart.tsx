export interface BarChartDatum {
  label: string;
  value: number;
}

export interface BarChartProps {
  title?: string;
  hint?: string;
  data: BarChartDatum[];
  height?: number;
  yAxisFormat?: (n: number) => string;
  color?: string;
}

export function BarChart({
  title,
  hint,
  data,
  height = 220,
  yAxisFormat = (n) => n.toLocaleString(),
  color = 'var(--keel-accent)'
}: BarChartProps) {
  const W = 720;
  const H = height;
  const padL = 56;
  const padR = 16;
  const padT = 12;
  const padB = 36;
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;

  const max = Math.max(1, ...data.map((d) => d.value));
  const niceMax = niceCeil(max);
  const ticks = 4;
  const tickValues = Array.from({ length: ticks + 1 }, (_, i) => (niceMax / ticks) * i);

  const barSlot = data.length > 0 ? innerW / data.length : innerW;
  const barWidth = Math.max(2, barSlot * 0.65);
  const barStep = data.length > 0 ? innerW / data.length : 0;

  return (
    <section className="keel-chart keel-chart--bar">
      {(title || hint) ? (
        <header className="keel-chart__header">
          {title ? <h3 className="keel-chart__title">{title}</h3> : <span />}
          {hint ? <span className="keel-chart__hint">{hint}</span> : null}
        </header>
      ) : null}
      <div className="keel-chart__body">
        <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} role="img" aria-label={title ?? 'Bar chart'}>
          {/* Y grid lines + tick labels */}
          {tickValues.map((t, i) => {
            const y = padT + innerH - (t / niceMax) * innerH;
            return (
              <g key={i}>
                <line
                  x1={padL}
                  y1={y}
                  x2={W - padR}
                  y2={y}
                  stroke="currentColor"
                  strokeOpacity={i === 0 ? 0.6 : 0.12}
                  strokeWidth={1}
                />
                <text
                  x={padL - 8}
                  y={y + 3}
                  textAnchor="end"
                  fontSize={10}
                  fontFamily="var(--keel-font-mono)"
                  fill="currentColor"
                  opacity={0.6}
                >
                  {yAxisFormat(t)}
                </text>
              </g>
            );
          })}
          {/* X axis labels + bars */}
          {data.map((d, i) => {
            const x = padL + i * barStep + (barStep - barWidth) / 2;
            const h = (d.value / niceMax) * innerH;
            const y = padT + innerH - h;
            return (
              <g key={i}>
                <rect x={x} y={y} width={barWidth} height={h} fill={color} stroke="currentColor" strokeWidth={1} />
                <text
                  x={x + barWidth / 2}
                  y={padT + innerH + 16}
                  textAnchor="middle"
                  fontSize={10}
                  fontFamily="var(--keel-font-mono)"
                  fill="currentColor"
                  opacity={0.7}
                >
                  {d.label}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
    </section>
  );
}

function niceCeil(n: number): number {
  if (n <= 0) return 1;
  const exp = Math.floor(Math.log10(n));
  const base = Math.pow(10, exp);
  const m = n / base;
  let nice: number;
  if (m <= 1) nice = 1;
  else if (m <= 2) nice = 2;
  else if (m <= 5) nice = 5;
  else nice = 10;
  return nice * base;
}
