export function EmptyState({ title, detail }: { title: string; detail?: string }) {
  return <div className="keel-empty"><strong>{title}</strong>{detail ? <p>{detail}</p> : null}</div>;
}
