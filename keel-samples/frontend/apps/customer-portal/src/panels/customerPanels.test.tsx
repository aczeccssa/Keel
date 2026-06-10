import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { CustomerPortalApi } from '../api/customerPortalApi';
import { BillingPanel } from './BillingPanel';
import { DashboardPanel } from './DashboardPanel';
import { KeysPanel } from './KeysPanel';
import { LoginPanel } from './LoginPanel';
import { PricingPanel } from './PricingPanel';

const api = {
  credits: async () => ({ balanceCredits: 1000 }),
  usage: async () => ({ records: [] }),
  keys: async () => ({ keys: [] }),
  createKey: async () => ({ key: { keyId: 'key_1', name: 'Default' }, rawKey: 'sk-test' }),
  deleteKey: async () => ({}),
  creditLedger: async () => ({ entries: [] }),
  redeem: async () => ({ balanceCredits: 1000 }),
  pricing: async () => ({ pricing: [] })
} as unknown as CustomerPortalApi;

describe('customer portal panels', () => {
  it('renders login panel actions', () => {
    render(<LoginPanel onAuthenticated={() => undefined} />);
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create account/i })).toBeInTheDocument();
  });

  it('renders dashboard heading', async () => {
    render(<DashboardPanel api={api} />);
    expect(await screen.findByRole('heading', { name: /dashboard/i })).toBeInTheDocument();
    expect(await screen.findByText(/^recent$/i)).toBeInTheDocument();
  });

  it('renders keys heading', async () => {
    render(<KeysPanel api={api} />);
    expect(await screen.findByRole('heading', { name: /api keys/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Create key/i })).toBeInTheDocument();
    expect(await screen.findByText(/No API keys yet/i)).toBeInTheDocument();
  });

  it('renders credits heading', async () => {
    render(<BillingPanel api={api} />);
    expect(await screen.findByRole('heading', { name: /credits/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Redeem/i })).toBeInTheDocument();
    expect(await screen.findByText(/No credit ledger entries yet/i)).toBeInTheDocument();
  });

  it('renders rates heading', async () => {
    render(<PricingPanel api={api} />);
    expect(await screen.findByRole('heading', { name: /rates/i })).toBeInTheDocument();
    expect(await screen.findByText(/No pricing rows yet/i)).toBeInTheDocument();
  });
});
