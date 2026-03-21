import './shared/KeelHero.js';
import './shared/KeelStatGrid.js';
import './shared/KeelDetailList.js';
import './TraceWaterfall.js';

import { KeelElement } from './base/KeelElement.js';
import { groupedTraces, selectedTraceGroup, selectedSpan } from '../state.js';
import { detailEntries } from '../render.js';
import { escapeHtml, formatDuration, formatDate, formatPercent } from '../utils.js';

export class PanelTraces extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <div class="panel-root">
                <keel-hero data-ref="hero"></keel-hero>
                <keel-stat-grid data-ref="stats"></keel-stat-grid>

                <div class="layout-grid traces">
                    <div class="card">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Recent Activity</div>
                                <h3>Trace Groups</h3>
                            </div>
                            <span class="badge neutral mono" data-ref="traceGroupCount">0 traces</span>
                        </div>
                        <div class="list scroll-panel" data-ref="traceGroupList"></div>
                    </div>

                    <div class="layout-grid traces-right">
                        <div class="card waterfall-card" data-ref="waterfallCard">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Trace Inspector</div>
                                    <h3 data-ref="waterfallTitle">Select a trace</h3>
                                </div>
                                <span class="badge neutral mono" data-ref="durationChip">—</span>
                            </div>
                            <keel-trace-waterfall data-ref="waterfall"></keel-trace-waterfall>
                        </div>

                        <div class="card span-detail-card">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Span Detail</div>
                                    <h3 data-ref="spanDetailTitle">Select a span</h3>
                                </div>
                                <span class="badge neutral mono" data-ref="spanDurationChip">—</span>
                            </div>
                            <keel-detail-list data-ref="spanDetail"></keel-detail-list>
                        </div>
                    </div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.traceGroupList.addEventListener('click', (event) => {
            const item = event.target.closest('[data-trace-id]');
            if (!item) return;
            this.emit('keel:trace-select', { traceId: item.dataset.traceId });
        });
    }

    render(appState) {
        this.ensureInitialized();
        const groups = groupedTraces();
        const selectedGroup = selectedTraceGroup();
        const span = selectedSpan();

        this.refs.hero.render({
            label: 'Trace Intelligence',
            title: 'Distributed Traces',
            metaHtml: `<span class="status-pill">${groups.length} grouped traces</span>`
        });
        this.refs.stats.render({
            entries: [
                ['Trace Groups', groups.length, `${appState.traces.length} spans`],
                ['P95 Latency', appState.metrics ? `${appState.metrics.latency.p95Ms} ms` : 'n/a', 'recent window'],
                ['Error Rate', appState.metrics ? formatPercent(appState.metrics.latency.errorRate) : 'n/a', 'trace status'],
                ['Top Service', selectedGroup ? selectedGroup.service : 'n/a', 'selected trace']
            ]
        });

        this.refs.traceGroupCount.textContent = `${groups.length} traces`;
        this.refs.traceGroupList.innerHTML = groups.length ? groups.map((group) => `
            <div class="list-item trace-group ${selectedGroup && group.traceId === selectedGroup.traceId ? 'is-selected' : ''}" data-trace-id="${escapeHtml(group.traceId)}">
                <div class="trace-title">
                    <strong>${escapeHtml(group.operation)}</strong>
                    <span class="badge ${group.errorCount ? 'err' : 'ok'}">${group.errorCount ? `${group.errorCount} errors` : 'healthy'}</span>
                </div>
                <div class="trace-meta mono">${escapeHtml(group.traceId)}</div>
                <div class="trace-meta">${escapeHtml(group.service)} · ${group.spanCount} spans · ${group.durationMs} ms</div>
            </div>
        `).join('') : '<div class="empty">No traces available in memory.</div>';

        this.refs.waterfallTitle.textContent = selectedGroup ? selectedGroup.traceId : 'Select a trace';
        this.refs.durationChip.textContent = selectedGroup ? `${selectedGroup.durationMs} ms` : '—';
        this.refs.waterfallCard.style.display = selectedGroup ? 'block' : 'none';
        this.refs.waterfall.render({ group: selectedGroup });
        this.refs.spanDetailTitle.textContent = span ? span.operation : 'Select a span';
        this.refs.spanDurationChip.textContent = span ? formatDuration(span) : '—';
        this.refs.spanDetail.render({
            html: span ? detailEntries([
                ['Service', span.service],
                ['Trace ID', span.traceId],
                ['Span ID', span.spanId],
                ['Parent Span', span.parentSpanId || 'root'],
                ['Start', formatDate(span.startEpochMs)],
                ['Duration', formatDuration(span)],
                ['Edge', `${span.edgeFrom || '?'} → ${span.edgeTo || '?'}`]
            ], span.attributes) : '<div class="empty">Select a trace to inspect its spans.</div>',
            className: 'detail-list span-detail-list scroll-panel'
        });
    }
}

if (!customElements.get('keel-panel-traces')) {
    customElements.define('keel-panel-traces', PanelTraces);
}
