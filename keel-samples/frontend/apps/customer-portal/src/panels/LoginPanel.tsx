import { FormEvent, useState } from 'react';
import { Button, Card, ErrorBanner } from '@keel/sample-ui';
import { CustomerPortalApi, type CustomerAuthResponse } from '../api/customerPortalApi';

export function LoginPanel({ onAuthenticated }: { onAuthenticated: (response: CustomerAuthResponse) => void }) {
  const [email, setEmail] = useState('demo@example.com');
  const [password, setPassword] = useState('demo123');
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const api = new CustomerPortalApi();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const response = mode === 'login'
        ? await api.login({ email, password })
        : await api.register({ email, password, displayName: email.split('@')[0] || email });
      onAuthenticated(response);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Authentication failed');
    }
  }

  return (
    <Card className="customer-login-card">
      <h1>Customer Portal</h1>
      <p>Self-service API keys, credits, pricing, and usage for Keel AI Relay customers.</p>
      {error ? <ErrorBanner message={error} /> : null}
      <form onSubmit={submit}>
        <label>Email<input value={email} onChange={(event) => setEmail(event.target.value)} /></label>
        <label>Password<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></label>
        <Button type="submit">{mode === 'login' ? 'Sign in' : 'Create account'}</Button>
      </form>
      <button type="button" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
        {mode === 'login' ? 'Create account' : 'Sign in'}
      </button>
    </Card>
  );
}
