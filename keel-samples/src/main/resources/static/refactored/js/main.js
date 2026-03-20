import './components/PanelTopology.js';
import './components/PanelTraces.js';
import './components/PanelLogs.js';
import './components/PanelNodes.js';
import './components/PanelMetrics.js';

import { state } from './state.js';
import { hydrate, togglePlugin } from './api.js';
import { bindEvents, downloadLogsJSON, downloadLogsText } from './events.js';
import { renderChrome } from './render.js';
import { escapeHtml } from './utils.js';

// Expose functions globally for inline HTML event handlers (e.g. onclick="downloadLogsJSON()")
window.togglePlugin = togglePlugin;
window.downloadLogsJSON = downloadLogsJSON;
window.downloadLogsText = downloadLogsText;

document.addEventListener("DOMContentLoaded", () => {
    // 确保组件渲染完成并DOM可用后再绑定事件
    setTimeout(() => {
        bindEvents();
        hydrate().catch((error) => {
            state.connectionState = "Error";
            renderChrome();
            document.querySelectorAll(".tab-panel").forEach((panel) => {
                panel.classList.remove("is-active");
            });
            const topologyTab = document.querySelector('[data-tab="topology"]');
            if (topologyTab) {
                topologyTab.classList.add("is-active");
                topologyTab.innerHTML = `
        <div class="card">
            <div class="section-head">
                <div>
                    <div class="section-label">Boot Failure</div>
                    <h3>Observability UI could not load</h3>
                </div>
            </div>
            <div class="code-block mono">${escapeHtml(error && error.message ? error.message : String(error))}</div>
        </div>
                `;
            }
        });
    }, 0);
});
