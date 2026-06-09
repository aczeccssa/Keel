export interface StatItem { label: string; value: string; hint?: string; }

export function StatGrid({ items }: { items: StatItem[] }) {
  return (
    <div className="keel-stat-grid">
      {items.map((item) => (
        <div className="keel-stat" key={item.label}>
          <span>{item.label}</span>
          <strong>{item.value}</strong>
          {item.hint ? <small>{item.hint}</small> : null}
        </div>
      ))}
    </div>
  );
}
