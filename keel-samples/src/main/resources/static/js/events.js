import { state } from './state.js';
import { API_BASE } from './config.js';
import { TABS } from './config.js';
import { renderChrome, renderPanels } from './render.js';

export function setActiveTab(tabId) {
    state.activeTab = TABS.some((tab) => tab.id === tabId) ? tabId : 'topology';
    if (window.location.hash !== `#${state.activeTab}`) {
        window.location.hash = state.activeTab;
        return;
    }
    renderChrome();
    renderPanels();
}

export function hydrateHash() {
    const hash = window.location.hash.replace(/^#/, '');
    state.activeTab = TABS.some((tab) => tab.id === hash) ? hash : 'topology';
}

export function downloadLogsJSON() {
    window.location.href = `${API_BASE}/_system/logs/download`;
}

export function downloadLogsText() {
    const items = state.logs.items || [];
    const text = items.map((entry) => {
        let line = `${entry.timestamp ? new Date(entry.timestamp).toISOString() : ''} ${entry.level || 'INFO'} [${entry.source || ''}] ${entry.message || ''}`;
        if (entry.throwable) line += `\n${entry.throwable}`;
        return line;
    }).join('\n');
    const blob = new Blob([text], { type: 'text/plain' });
    const anchor = document.createElement('a');
    anchor.href = URL.createObjectURL(blob);
    anchor.download = 'keel-logs.txt';
    anchor.click();
    URL.revokeObjectURL(anchor.href);
}
