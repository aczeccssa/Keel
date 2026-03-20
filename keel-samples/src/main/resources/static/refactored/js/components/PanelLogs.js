export class PanelLogs extends HTMLElement {
    connectedCallback() {
        this.innerHTML = `
            <div class="hero">
                    <div>
                        <div class="section-label">Operational Intelligence</div>
                        <h2>Log Explorer</h2>
                    </div>
                    <div class="hero-meta">
                        <span class="status-pill" id="log-total-pill">0 entries</span>
                        <button class="action-btn" onclick="downloadLogsJSON()">Download JSON</button>
                        <button class="action-btn" onclick="downloadLogsText()">Download Raw Text</button>
                    </div>
                </div>

                <div class="card" style="margin-bottom: 18px; flex-shrink: 0; padding-bottom: 0;">
                    <div class="filters">
                        <label>Query<input id="log-query-input" placeholder="trace id, message, throwable"></label>
                        <label>Level
                            <select id="log-level-select">
                                <option value="">All</option>
                                <option value="DEBUG">DEBUG</option>
                                <option value="INFO">INFO</option>
                                <option value="WARN">WARN</option>
                                <option value="ERROR">ERROR</option>
                            </select>
                        </label>
                        <label>Source<input id="log-source-input" placeholder="auth, kernel, inventory"></label>
                        <label>Window
                            <select id="log-window-select">
                                <option value="15m">Last 15m</option>
                                <option value="1h" selected>Last 1h</option>
                                <option value="6h">Last 6h</option>
                                <option value="24h">Last 24h</option>
                            </select>
                        </label>
                        <button id="log-refresh-btn" type="button">Refresh Logs</button>
                    </div>
                    <div class="histogram" id="log-histogram"></div>
                </div>

                <div class="layout-grid logs">
                    <div class="card">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Stream</div>
                                <h3>Structured Records</h3>
                            </div>
                            <span class="badge neutral mono" id="log-page-chip">limit 80</span>
                        </div>
                        <div class="log-table scroll-panel" id="log-table"></div>
                    </div>

                    <div class="card">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Expanded Record</div>
                                <h3 id="log-detail-title">No record selected</h3>
                            </div>
                            <span class="badge neutral" id="log-detail-level">idle</span>
                        </div>
                        <div class="detail-list scroll-panel" id="log-detail-list"></div>
                    </div>
                </div>
        `;
    }
}
customElements.define('keel-panel-logs', PanelLogs);
