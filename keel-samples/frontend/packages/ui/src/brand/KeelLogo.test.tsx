// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { KeelLogo } from './KeelLogo';

describe('KeelLogo', () => {
  it('renders the supplied accessible label', () => {
    render(<KeelLogo label="Keel AI Relay" />);
    expect(screen.getByLabelText('Keel AI Relay')).toBeInTheDocument();
    expect(screen.getByText('Keel AI Relay')).toBeInTheDocument();
  });
});
