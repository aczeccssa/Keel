import { describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import './KeelDataTable.js';

describe('legacy KeelDataTable', () => {
  it('defines brutalist table button styles inside shadow DOM', () => {
    const table = document.createElement('keel-data-table');
    document.body.appendChild(table);

    const css = table.shadowRoot?.querySelector('style:last-of-type')?.textContent ?? '';

    expect(css).toContain('tbody td button');
    expect(css).toContain('padding: 7px 11px;');
    expect(css).toContain('border: 2px solid var(--ink);');
    expect(css).toContain('.btn-action.danger');

    table.remove();
  });
});
