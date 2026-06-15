import { FormEvent, useState } from 'react';
import { Button, Card, ErrorBanner } from '@keel/sample-ui';
import { CustomerPortalApi, type CustomerAuthResponse } from '../api/customerPortalApi';

export function LoginPanel({ onAuthenticated }: { onAuthenticated: (response: CustomerAuthResponse) => void }) {
  const [email, setEmail] = useState('demo@example.com');
  const [password, setPassword] = useState('demo123');
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [submitting, setSubmitting] = useState(false);
  const api = new CustomerPortalApi();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const response =
        mode === 'login'
          ? await api.login({ email, password })
          : await api.register({ email, password, displayName: email.split('@')[0] || email });
      onAuthenticated(response);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Authentication failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="keel-login-shell">
      <Card>
        <h1>Customer Portal</h1>
        <p className="keel-muted">
          Self-service API keys, credits, pricing, and usage for Keel AI Relay customers.
        </p>
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
            {submitting ? 'Working…' : mode === 'login' ? 'Sign in' : 'Create account'}
          </Button>
        </form>
        <div style={{ marginTop: 'var(--keel-space-3)' }}>
          <Button
            variant="ghost"
            size="sm"
            type="button"
            onClick={() => setMode(mode === 'login' ? 'register' : 'login')}
          >
            {mode === 'login' ? 'Create account' : 'Sign in'}
          </Button>
        </div>
      </Card>
    </div>
  );
}
