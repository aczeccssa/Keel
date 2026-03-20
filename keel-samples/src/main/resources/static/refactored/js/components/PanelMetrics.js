export class PanelMetrics extends HTMLElement {
    connectedCallback() {
        this.innerHTML = `
            <div class="hero">
                    <div>
                        <div class="section-label">System Performance</div>
                        <h2>Metrics & Runtime Health</h2>
                    </div>
                    <div class="hero-meta">
                        <span class="status-pill" id="metrics-window-pill">Recent snapshot</span>
                    </div>
                </div>

                <div class="scroll-panel">
                    <div class="metrics-grid" id="metrics-grid"></div>

                    <div class="chart-grid">
                        <div class="card chart-box">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">CPU Utilization</div>
                                    <h3>Kernel Load</h3>
                                </div>
                                <span class="badge neutral mono" id="cpu-value-chip">0%</span>
                            </div>
                            <svg class="sparkline" id="cpu-sparkline" viewBox="0 0 320 120"
                                preserveAspectRatio="none"></svg>
                            <div class="detail-list" id="cpu-detail-list"></div>
                        </div>

                        <div class="card chart-box">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Heap Pressure</div>
                                    <h3>Memory</h3>
                                </div>
                                <span class="badge neutral mono" id="heap-value-chip">0%</span>
                            </div>
                            <svg class="sparkline" id="heap-sparkline" viewBox="0 0 320 120"
                                preserveAspectRatio="none"></svg>
                            <div class="detail-list" id="heap-detail-list"></div>
                        </div>

                        <div class="card chart-box">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Latency & Traffic</div>
                                    <h3>Window Summary</h3>
                                </div>
                            </div>
                            <div class="detail-list" id="metrics-latency-list"></div>
                        </div>
                    </div>

                    <div class="layout-grid metrics">
                        <div class="card">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Top Edges</div>
                                    <h3>Recent Traffic</h3>
                                </div>
                            </div>
                            <div id="edge-table-wrap"></div>
                        </div>

                        <div class="card">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Node & Plugin Inventory</div>
                                    <h3>Runtime Inventory</h3>
                                </div>
                            </div>
                            <div class="table-scroll">
                                <div id="metrics-node-table-wrap"></div>
                            </div>
                        </div>
                    </div>
                </div>
        `;
    }
}
customElements.define('keel-panel-metrics', PanelMetrics);
