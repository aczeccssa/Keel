import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CustomerPortalApi } from '../api/customerPortalApi';
import { BillingPanel } from './BillingPanel';
import { DashboardPanel } from './DashboardPanel';
import { KeysPanel } from './KeysPanel';
import { LoginPanel } from './LoginPanel';
import { PricingPanel } from './PricingPanel';

const api = new CustomerPortalApi('test-token');

describe('customer portal panels', () => {
  it('renders login panel actions', () => {
    render(<LoginPanel onAuthenticated={() => undefined} />);
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create account/i })).toBeInTheDocument();
  });

  it('renders dashboard heading', () => {
    render(<DashboardPanel api={api} />);
    expect(screen.getByRole('heading', { name: /dashboard/i })).toBeInTheDocument();
  });

  it('renders keys heading', () => {
    render(<KeysPanel api={api} />);
    expect(screen.getByRole('heading', { name: /api keys/i })).toBeInTheDocument();
  });

  it('renders credits heading', () => {
    render(<BillingPanel api={api} />);
    expect(screen.getByRole('heading', { name: /credits/i })).toBeInTheDocument();
  });

  it('renders rates heading', () => {
    render(<PricingPanel api={api} />);
    expect(screen.getByRole('heading', { name: /rates/i })).toBeInTheDocument();
  });
});
