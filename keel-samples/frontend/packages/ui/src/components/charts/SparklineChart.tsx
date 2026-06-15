export interface SparklineChartProps {
  values: number[];
  width?: number;
  height?: number;
  stroke?: string;
  fill?: string;
  title?: string;
}

export function SparklineChart({
  values,
  width = 120,
  height = 32,
  stroke = 'var(--keel-accent)',
  fill = 'var(--keel-accent-soft)',
  title
}: SparklineChartProps) {
  if (!values || values.length < 2) {
    return <span className="keel-mono keel-muted" style={{ fontSize: 11 }}>—</span>;
  }
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const range = max - min || 1;
  const stepX = width / (values.length - 1);

  const points = values.map((v, i) => {
    const x = i * stepX;
    const y = height - ((v - min) / range) * (height - 4) - 2;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  const linePath = `M ${points.join(' L ')}`;
  const areaPath = `M 0,${height} L ${points.join(' L ')} L ${(values.length - 1) * stepX},${height} Z`;

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      width={width}
      height={height}
      role="img"
      aria-label={title ?? 'Sparkline'}
    >
      <path d={areaPath} fill={fill} stroke="none" />
      <path d={linePath} fill="none" stroke={stroke} strokeWidth={1.5} strokeLinejoin="miter" />
    </svg>
  );
}
