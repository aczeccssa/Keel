export function ChartPlaceholder({ label }: { label: string }) {
  return <div className="keel-chart-placeholder" aria-label={label}>{label}</div>;
}
