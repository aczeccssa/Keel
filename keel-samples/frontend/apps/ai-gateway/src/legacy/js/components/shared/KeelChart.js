import { KeelElement } from '../base/KeelElement.js';

/**
 * <keel-chart> — Lightweight CSS/SVG chart component.
 *
 * Usage:
 *   chart.render({ type: 'bar', data: [10, 20, 30], labels: ['A','B','C'] })
 *   chart.render({ type: 'donut', data: [30, 50, 20], labels: ['A','B','C'] })
 *   chart.render({ type: 'sparkline', data: [1, 4, 2, 8, 5] })
 */
export class KeelChart extends KeelElement {
    hostStyles() { return 'display:block;width:100%;'; }

    template() {
        return `
            <style>
                .chart-container { width: 100%; position: relative; }
                /* ── Bar chart ─────────────────────────────────── */
                .bar-chart {
                    display: flex;
                    align-items: flex-end;
                    gap: 3px;
                    height: 120px;
                    padding: 0;
                }
                .bar-chart .bar-col {
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: flex-end;
                    height: 100%;
                    min-width: 0;
                }
                .bar-chart .bar {
                    width: 100%;
                    min-height: 2px;
                    background: var(--teal, #0d9488);
                    transition: height 400ms cubic-bezier(0.2, 0, 0, 1);
                    position: relative;
                }
                .bar-chart .bar:hover { opacity: 0.8; }
                .bar-chart .bar-empty {
                    height: 1px;
                    min-height: 1px;
                    background: var(--muted, #6b6b66);
                    opacity: 0.25;
                }
                .bar-chart .bar-label {
                    font-family: var(--font-mono, monospace);
                    font-size: 9px;
                    color: var(--muted, #6b6b66);
                    text-transform: uppercase;
                    letter-spacing: 0.04em;
                    margin-top: 6px;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    max-width: 100%;
                    text-align: center;
                }
                .bar-chart .bar-value {
                    font-family: var(--font-mono, monospace);
                    font-size: 9px;
                    font-weight: 700;
                    color: var(--ink, #0b0b0b);
                    margin-bottom: 3px;
                    white-space: nowrap;
                }

                /* ── Donut chart ───────────────────────────────── */
                .donut-wrap {
                    display: flex;
                    align-items: center;
                    gap: 20px;
                }
                .donut-svg { flex-shrink: 0; }
                .donut-legend {
                    display: flex;
                    flex-direction: column;
                    gap: 6px;
                    min-width: 0;
                }
                .donut-legend-item {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    font-family: var(--font-mono, monospace);
                    font-size: 11px;
                    color: var(--ink, #0b0b0b);
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .donut-legend-dot {
                    width: 10px;
                    height: 10px;
                    flex-shrink: 0;
                    border: 1px solid var(--ink, #0b0b0b);
                }
                .donut-legend-value {
                    color: var(--muted, #6b6b66);
                    margin-left: auto;
                    font-weight: 700;
                }

                /* ── Sparkline ─────────────────────────────────── */
                .sparkline-svg {
                    width: 100%;
                    height: 60px;
                    display: block;
                }
                .sparkline-svg .line {
                    fill: none;
                    stroke: var(--teal, #0d9488);
                    stroke-width: 2;
                    stroke-linecap: round;
                    stroke-linejoin: round;
                }
                .sparkline-svg .area {
                    fill: var(--teal, #0d9488);
                    opacity: 0.1;
                }

                .chart-empty {
                    text-align: center;
                    padding: 24px;
                    font-family: var(--font-mono, monospace);
                    font-size: 11px;
                    font-weight: 700;
                    color: var(--muted, #6b6b66);
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                }
            </style>
            <div class="chart-container" data-ref="container"></div>
        `;
    }

    /**
     * Render a chart.
     * @param {Object} opts
     * @param {'bar'|'donut'|'sparkline'} opts.type
     * @param {number[]} opts.data - Values
     * @param {string[]} [opts.labels] - Labels for each data point
     * @param {string[]} [opts.colors] - Per-segment colors (donut/bar)
     * @param {string} [opts.color] - Single color for all bars (time-series trends)
     * @param {number} [opts.height=120] - Bar chart height in px
     * @param {string} [opts.emptyText] - Text to show when data is empty
     */
    render(opts) {
        const { type, data = [], labels = [], colors, color, height = 120, emptyText } = opts;
        if (!data.length || data.every(v => !v)) {
            this.refs.container.innerHTML = `<div class="chart-empty">${emptyText || 'No data yet'}</div>`;
            return;
        }
        switch (type) {
            case 'bar': this._renderBar(data, labels, colors, height, color); break;
            case 'donut': this._renderDonut(data, labels, colors); break;
            case 'sparkline': this._renderSparkline(data); break;
        }
    }

    _renderBar(data, labels, colors, height, singleColor) {
        const max = Math.max(1, ...data);
        const palette = colors || this._defaultColors();
        const thinValuesWithLabels = labels.length === data.length && labels.some(label => !label);
        const cols = data.map((v, i) => {
            const label = labels[i] || '';
            const valueLabel = thinValuesWithLabels && !label ? '&nbsp;' : this._fmtNum(v);
            // Empty buckets recede to a faint baseline track instead of drawing a
            // full-color 2px dash, which otherwise litters dense trends with marks.
            if (!v) {
                return `
                <div class="bar-col">
                    <span class="bar-value">&nbsp;</span>
                    <div class="bar bar-empty" title="${label}: 0"></div>
                    <span class="bar-label">${this._escHtml(label)}</span>
                </div>
            `;
            }
            const h = Math.max(3, Math.round((height - 20) * v / max));
            const color = singleColor || palette[i % palette.length];
            return `
                <div class="bar-col">
                    <span class="bar-value">${valueLabel}</span>
                    <div class="bar" style="height:${h}px;background:${color};" title="${label}: ${v}"></div>
                    <span class="bar-label">${this._escHtml(label)}</span>
                </div>
            `;
        }).join('');
        this.refs.container.innerHTML = `<div class="bar-chart" style="height:${height}px;">${cols}</div>`;
    }

    _renderDonut(data, labels, colors) {
        const total = data.reduce((s, v) => s + v, 0) || 1;
        const palette = colors || this._defaultColors();
        const size = 120;
        const r = 45;
        const cx = size / 2;
        const cy = size / 2;
        const circumference = 2 * Math.PI * r;
        let offset = 0;

        const arcs = data.map((v, i) => {
            const pct = v / total;
            const dashLen = pct * circumference;
            const dashArr = `${dashLen} ${circumference - dashLen}`;
            const color = palette[i % palette.length];
            const el = `<circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="${color}" stroke-width="14"
                stroke-dasharray="${dashArr}" stroke-dashoffset="${-offset}"
                transform="rotate(-90 ${cx} ${cy})" />`;
            offset += dashLen;
            return el;
        }).join('');

        const legend = data.map((v, i) => {
            const pct = Math.round(100 * v / total);
            const color = palette[i % palette.length];
            const label = labels[i] || `Item ${i + 1}`;
            return `
                <div class="donut-legend-item">
                    <span class="donut-legend-dot" style="background:${color};"></span>
                    <span>${this._escHtml(label)}</span>
                    <span class="donut-legend-value">${pct}%</span>
                </div>
            `;
        }).join('');

        this.refs.container.innerHTML = `
            <div class="donut-wrap">
                <svg class="donut-svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
                    ${arcs}
                    <text x="${cx}" y="${cy}" text-anchor="middle" dominant-baseline="central"
                        font-family="var(--font-headline, sans-serif)" font-size="18" fill="var(--ink, #0b0b0b)"
                        font-weight="900">${this._fmtNum(total)}</text>
                </svg>
                <div class="donut-legend">${legend}</div>
            </div>
        `;
    }

    _renderSparkline(data) {
        const max = Math.max(1, ...data);
        const min = Math.min(0, ...data);
        const range = max - min || 1;
        const w = 300;
        const h = 60;
        const padY = 4;
        const points = data.map((v, i) => {
            const x = (i / (data.length - 1 || 1)) * w;
            const y = h - padY - ((v - min) / range) * (h - padY * 2);
            return `${x},${y}`;
        });
        const linePoints = points.join(' ');
        const areaPoints = `0,${h} ${linePoints} ${w},${h}`;

        this.refs.container.innerHTML = `
            <svg class="sparkline-svg" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none">
                <polygon class="area" points="${areaPoints}" />
                <polyline class="line" points="${linePoints}" />
            </svg>
        `;
    }

    _defaultColors() {
        return ['#0d9488', '#6366f1', '#f59e0b', '#ef4444', '#8b5cf6', '#10b981', '#f97316', '#3b82f6'];
    }

    _fmtNum(n) {
        if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
        if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
        return String(Math.round(n));
    }

    _escHtml(s) {
        const d = document.createElement('div');
        d.textContent = s;
        return d.innerHTML;
    }
}

customElements.define('keel-chart', KeelChart);
