export class PanelTraces extends HTMLElement {
    connectedCallback() {
        this.innerHTML = `
            <div class="hero">
                    <div>
                        <div class="section-label">Trace Intelligence</div>
                        <h2>Distributed Traces</h2>
                    </div>
                    <div class="hero-meta">
                        <span class="status-pill" id="trace-window-pill">Recent memory window</span>
                    </div>
                </div>

                <div class="stats-row" id="trace-stats"></div>

                <div class="layout-grid traces">
                    <div class="card">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Recent Activity</div>
                                <h3>Trace Groups</h3>
                            </div>
                            <span class="badge neutral mono" id="trace-group-count">0 traces</span>
                        </div>
                        <div class="list scroll-panel" id="trace-group-list"></div>
                    </div>

                    <div class="layout-grid traces-right">
                        <div class="card waterfall-card" id="trace-waterfall-card">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Trace Inspector</div>
                                    <h3 id="trace-waterfall-title">Select a trace</h3>
                                </div>
                                <span class="badge neutral mono" id="trace-duration-chip">—</span>
                            </div>
                            <div class="waterfall-shell" id="trace-waterfall"></div>
                        </div>

                        <div class="card span-detail-card">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Span Detail</div>
                                    <h3 id="span-detail-title">Select a span</h3>
                                </div>
                                <span class="badge neutral mono" id="span-duration-chip">—</span>
                            </div>
                            <div class="detail-list span-detail-list scroll-panel" id="span-detail-list"></div>
                        </div>
                    </div>
                </div>
        `;
    }
}
customElements.define('keel-panel-traces', PanelTraces);
