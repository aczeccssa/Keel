export function SkeletonBlock({ lines = 3 }: { lines?: number }) {
  return (
    <div className="keel-skeleton" aria-label="Loading">
      {Array.from({ length: lines }, (_, index) => <span key={index} />)}
    </div>
  );
}
