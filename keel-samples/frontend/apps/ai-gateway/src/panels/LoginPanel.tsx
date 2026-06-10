import { FormEvent, useState } from 'react';
import { Button, Card, ErrorBanner, FormField } from '@keel/sample-ui';
import { AiGatewayApi, type AdminAuthResponse } from '../api/aiGatewayApi';

export function LoginPanel({
  initialMode = 'login',
  onBack,
  onAuthenticated
}: {
  initialMode?: 'login' | 'register';
  onBack?: () => void;
  onAuthenticated: (response: AdminAuthResponse) => void;
}) {
  const [email, setEmail] = useState('admin@example.com');
  const [password, setPassword] = useState('admin123');
  const [mode, setMode] = useState<'login' | 'register'>(initialMode);
  const [error, setError] = useState<string | null>(null);
  const api = new AiGatewayApi();

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
    <Card className="admin-login-card">
      {onBack ? <Button type="button" variant="ghost" size="sm" onClick={onBack}>Back</Button> : null}
      <h1>{mode === 'login' ? 'Access manager console' : 'Create manager account'}</h1>
      <p>Manage upstream providers, groups, keys, rate limits, customers, usage, and pricing.</p>
      {error ? <ErrorBanner message={error} /> : null}
      <form onSubmit={submit}>
        <FormField label="Email" value={email} onChange={(event) => setEmail(event.target.value)} />
        <FormField label="Password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
        <Button type="submit">{mode === 'login' ? 'Sign in' : 'Create account'}</Button>
      </form>
      <button type="button" className="keel-auth-switch" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
        {mode === 'login' ? 'Create account' : 'Sign in'}
      </button>
    </Card>
  );
}
