import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('Customer Portal App', () => {
  it('renders login state when no customer token exists', () => {
    localStorage.clear();
    render(<App />);
    expect(screen.getByText('Customer Portal')).toBeInTheDocument();
  });

  it('renders shell when a customer token exists', () => {
    localStorage.setItem('keel-customer-portal-auth', JSON.stringify({ accessToken: 'token', refreshToken: 'refresh', email: 'demo@example.com' }));
    render(<App />);
    expect(screen.getByLabelText('Keel Customer Portal')).toBeInTheDocument();
    expect(screen.getByTestId('customer-active-tab')).toHaveTextContent('home');
  });
});
