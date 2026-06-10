export interface StatItem { label: string; value: string; hint?: string; status?: 'neutral' | 'good' | 'warn' | 'danger'; }

export function StatGrid({ items }: { items: StatItem[] }) {
  return (
    <div className="keel-stat-grid">
      {items.map((item) => (
        <div className={`keel-stat keel-stat-${item.status ?? 'neutral'}`} key={item.label}>
          <span>{item.label}</span>
          <strong>{item.value}</strong>
          {item.hint ? <small>{item.hint}</small> : null}
        </div>
      ))}
    </div>
  );
}
