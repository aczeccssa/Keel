import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('AI Gateway App', () => {
  it('renders the AI Relay landing page before auth', () => {
    localStorage.clear();
    render(<App />);
    expect(screen.getByRole('heading', { name: /Keel AI Relay/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Sign in/i })).toBeInTheDocument();
  });

  it('opens the AI Relay sign-in form from landing', async () => {
    localStorage.clear();
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: /^Sign in$/i }));
    expect(screen.getByRole('heading', { name: /Access manager console/i })).toBeInTheDocument();
  });

  it('renders shell when an admin token exists', () => {
    localStorage.setItem('keel-ai-gateway-auth', JSON.stringify({ accessToken: 'token', refreshToken: 'refresh', email: 'admin@example.com' }));
    render(<App />);
    expect(screen.getByLabelText('Keel AI Relay')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
  });
});
