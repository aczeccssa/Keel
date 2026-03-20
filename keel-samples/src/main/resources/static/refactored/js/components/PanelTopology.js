export class PanelTopology extends HTMLElement {
    connectedCallback() {
        this.innerHTML = `
            <div class="hero">
                    <div>
                        <div class="section-label">System Observer</div>
                        <h2>Topology & Live Flow</h2>
                    </div>
                    <div class="hero-meta">
                        <span class="status-pill"><span class="status-dot"></span><span id="topology-summary-pill">0 active nodes</span></span>
                        <span class="status-pill" id="panel-count-pill">0 panels</span>
                    </div>
                </div>

                <div class="layout-grid topology">
                    <div class="canvas-card topology-stage topo-canvas-drag" id="topology-stage">
                        <div class="topology-grid"></div>
                        <div class="topology-viewport" id="topology-viewport">
                            <svg class="topology-svg" id="topology-base-svg" viewBox="0 0 1000 1000" preserveAspectRatio="none"></svg>
                            <svg class="topology-svg" id="topology-flow-svg" viewBox="0 0 1000 1000" preserveAspectRatio="none"></svg>
                            <div class="topology-node-layer" id="topology-node-layer"></div>
                        </div>
                        <div class="canvas-controls">
                            <button class="canvas-btn" id="zoom-in-btn" type="button">+</button>
                            <button class="canvas-btn" id="zoom-out-btn" type="button">-</button>
                            <button class="canvas-btn" id="layout-btn" type="button">L</button>
                            <button class="canvas-btn" id="recenter-btn" type="button">C</button>
                            <div class="canvas-label mono" id="zoom-label">100%</div>
                        </div>
                    </div>

                    <div class="stack">
                        <div class="card" style="padding: 18px;">
                            <div class="grid-2x2" id="topology-stats"></div>
                        </div>

                        <!-- 动态切换的卡片：Node Detail -->
                        <div class="card" id="topology-node-card" style="display: none; flex: 1; min-height: 0;">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Selected Node</div>
                                    <h3 id="topology-node-title">Kernel JVM</h3>
                                </div>
                                <span class="badge neutral" id="topology-node-health">unknown</span>
                            </div>
                            <div class="detail-list scroll-panel" id="topology-node-detail"></div>
                        </div>

                        <!-- 动态切换的卡片：Edge Activity -->
                        <div class="card" id="topology-edge-card" style="flex: 1; min-height: 0;">
                            <div class="section-head">
                                <div>
                                    <div class="section-label">Recent Packets</div>
                                    <h3>Edge Activity</h3>
                                </div>
                                <span class="badge neutral mono" id="flow-count-chip">0 flows</span>
                            </div>
                            <div class="list scroll-panel" id="topology-flow-list"></div>
                        </div>
                    </div>
                </div>
        `;
    }
}
customElements.define('keel-panel-topology', PanelTopology);
