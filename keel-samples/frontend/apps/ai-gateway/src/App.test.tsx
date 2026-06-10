import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('AI Gateway App', () => {
  it('renders login state when no admin token exists', () => {
    localStorage.clear();
    render(<App />);
    expect(screen.getByText('AI Relay Console')).toBeInTheDocument();
  });

  it('renders shell when an admin token exists', () => {
    localStorage.setItem('keel-ai-gateway-auth', JSON.stringify({ accessToken: 'token', refreshToken: 'refresh', email: 'admin@example.com' }));
    render(<App />);
    expect(screen.getByLabelText('Keel AI Relay')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
  });
});
