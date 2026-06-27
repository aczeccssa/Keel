import { beforeEach, describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './KeelHero.js';

describe('legacy hero', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders label, title, and meta content into named refs', () => {
    const hero = document.createElement('keel-hero') as any;
    document.body.appendChild(hero);

    expect(() => hero.render({
      label: 'Request Ledger',
      title: 'Usage',
      metaHtml: '<strong>327 records</strong>'
    })).not.toThrow();

    expect(hero.shadowRoot.textContent).toContain('Request Ledger');
    expect(hero.shadowRoot.textContent).toContain('Usage');
    expect(hero.shadowRoot.innerHTML).toContain('327 records');
  });
});
