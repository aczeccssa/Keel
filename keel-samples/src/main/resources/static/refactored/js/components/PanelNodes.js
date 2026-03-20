export class PanelNodes extends HTMLElement {
    connectedCallback() {
        this.innerHTML = `
            <div class="hero">
                    <div>
                        <div class="section-label">Cluster Management</div>
                        <h2>Cluster Nodes</h2>
                    </div>
                    <div class="hero-meta">
                        <span class="status-pill" id="node-total-pill">0 nodes</span>
                    </div>
                </div>

                <div class="stats-row" id="node-stats" style="flex-shrink: 0;"></div>

                <!-- 动态侧滑布局 -->
                <div class="layout-grid nodes" id="nodes-layout-grid">
                    <div class="card node-directory-card">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Active Inventory</div>
                                <h3>Node Directory</h3>
                            </div>
                        </div>
                        <div class="node-grid scroll-panel" id="node-grid"></div>
                    </div>

                    <!-- 隐藏的侧边详情栏 -->
                    <div class="card" id="nodes-detail-card" style="display: none;">
                        <div class="section-head">
                            <div>
                                <div class="section-label">Selected Node</div>
                                <h3 id="nodes-detail-title">Kernel JVM</h3>
                            </div>
                            <span class="badge neutral" id="nodes-detail-health">unknown</span>
                        </div>
                        <div class="detail-list scroll-panel" id="nodes-detail-list"></div>
                    </div>
                </div>
        `;
    }
}
customElements.define('keel-panel-nodes', PanelNodes);
