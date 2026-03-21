import { KeelElement } from '../base/KeelElement.js';
import { REFRESH_INTERVALS } from '../../config.js';
import { apiUrl } from '../../api.js';

export class TopbarShell extends KeelElement {
    template() {
        return `
            <header class="topbar">
                <a class="topbar-showcase" data-ref="showcaseLinke" href="#" target="_blank" rel="noopener">Showcase</a>
                <div class="topbar-actions">
                    <div class="status-pill" data-ref="connectionPill">
                        <span class="status-dot"></span>
                        <span data-ref="connectionText">Booting</span>
                    </div>
                    <button class="ghost-pill" data-ref="streamToggleBtn" type="button">Live On</button>
                </div>
            </header>
        `;
    }

    afterMount() {
        this.refs.connectionPill.addEventListener('click', () => {
            this.emit('keel:open-refresh-overlay');
        });

        this.refs.streamToggleBtn.addEventListener('click', () => {
            this.emit('keel:stream-toggle');
        });
    }



    render(appState) {
        this.ensureInitialized();
        this.refs.connectionText.textContent = appState.connectionState === 'Live'
            ? `Live ${REFRESH_INTERVALS.find((item) => item.ms === appState.refreshIntervalMs)?.label || '5s'}`
            : appState.connectionState;
        this.refs.connectionPill.classList.toggle('is-offline', appState.connectionState !== 'Live');
        this.refs.streamToggleBtn.textContent = appState.streamEnabled ? 'Live On' : 'Live Off';
        this.refs.showcaseLinke.href = apiUrl('/plugins/authsample/showcase');
    }
}

if (!customElements.get('keel-topbar')) {
    customElements.define('keel-topbar', TopbarShell);
}
