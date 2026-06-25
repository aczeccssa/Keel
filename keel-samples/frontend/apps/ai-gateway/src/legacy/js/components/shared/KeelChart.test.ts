import { describe, expect, it } from 'vitest';
// @ts-ignore legacy custom element module has no TypeScript declarations
import { KeelChart } from './KeelChart.js';

describe('legacy keel chart', () => {
  it('hides crowded bar values when the caller thins labels', () => {
    const chart = new KeelChart();
    document.body.appendChild(chart);

    chart.render({
      type: 'bar',
      data: Array.from({ length: 12 }, (_, i) => i + 1),
      labels: Array.from({ length: 12 }, (_, i) => (i % 3 === 0 ? `l${i}` : '')),
      height: 110
    });

    const valueNodes = chart.shadowRoot?.querySelectorAll('.bar-value');
    const shownValues = valueNodes == null
      ? []
      : Array.from(valueNodes)
        .map((node) => (node as HTMLElement).textContent?.trim() ?? '')
        .filter(Boolean);

    expect(shownValues).toHaveLength(4);
    expect(shownValues).toEqual(['1', '4', '7', '10']);
  });
});
