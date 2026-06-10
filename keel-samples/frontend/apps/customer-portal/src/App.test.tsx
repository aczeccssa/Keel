import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('Customer Portal App', () => {
  it('renders the Customer Portal landing page before auth', () => {
    localStorage.clear();
    render(<App />);
    expect(screen.getByRole('heading', { name: /Keel Customer Portal/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Create account/i })).toBeInTheDocument();
  });

  it('opens the customer register form from landing', async () => {
    localStorage.clear();
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: /^Create account$/i }));
    expect(screen.getByRole('heading', { name: /Create customer account/i })).toBeInTheDocument();
  });

  it('renders shell when a customer token exists', () => {
    localStorage.setItem('keel-customer-portal-auth', JSON.stringify({ accessToken: 'token', refreshToken: 'refresh', email: 'demo@example.com' }));
    render(<App />);
    expect(screen.getByLabelText('Keel Customer Portal')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
  });
});
