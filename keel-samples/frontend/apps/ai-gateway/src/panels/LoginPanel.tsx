import { FormEvent, useState } from 'react';
import { Button, Card, ErrorBanner } from '@keel/sample-ui';
import { AiGatewayApi, type AdminAuthResponse } from '../api/aiGatewayApi';

export function LoginPanel({ onAuthenticated }: { onAuthenticated: (response: AdminAuthResponse) => void }) {
  const [email, setEmail] = useState('admin@example.com');
  const [password, setPassword] = useState('admin123');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const api = new AiGatewayApi();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      onAuthenticated(await api.login({ email, password }));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Authentication failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="keel-login-shell">
      <Card>
        <h1>AI Relay Console</h1>
        <p className="keel-muted">Manage upstream providers, groups, keys, rate limits, customers, usage, and pricing.</p>
        {error ? <ErrorBanner message={error} /> : null}
        <form onSubmit={submit}>
          <div className="keel-field">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              autoComplete="username"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </div>
          <div className="keel-field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>
          <Button type="submit" disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
      </Card>
    </div>
  );
}
