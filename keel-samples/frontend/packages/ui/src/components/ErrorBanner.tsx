export function ErrorBanner({ message }: { message: string }) {
  return <div role="alert" className="keel-error-banner">{message}</div>;
}
