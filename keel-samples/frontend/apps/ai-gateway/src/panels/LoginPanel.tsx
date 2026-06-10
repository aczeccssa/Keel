import { FormEvent, useState } from 'react';
import { Button, Card, ErrorBanner } from '@keel/sample-ui';
import { AiGatewayApi, type AdminAuthResponse } from '../api/aiGatewayApi';

export function LoginPanel({ onAuthenticated }: { onAuthenticated: (response: AdminAuthResponse) => void }) {
  const [email, setEmail] = useState('admin@example.com');
  const [password, setPassword] = useState('admin123');
  const [error, setError] = useState<string | null>(null);
  const api = new AiGatewayApi();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      onAuthenticated(await api.login({ email, password }));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Authentication failed');
    }
  }

  return (
    <Card className="admin-login-card">
      <h1>AI Relay Console</h1>
      <p>Manage upstream providers, groups, keys, rate limits, customers, usage, and pricing.</p>
      {error ? <ErrorBanner message={error} /> : null}
      <form onSubmit={submit}>
        <label>Email<input value={email} onChange={(event) => setEmail(event.target.value)} /></label>
        <label>Password<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></label>
        <Button type="submit">Sign in</Button>
      </form>
    </Card>
  );
}
