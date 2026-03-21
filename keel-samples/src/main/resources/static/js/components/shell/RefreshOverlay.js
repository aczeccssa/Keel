import { KeelElement } from '../base/KeelElement.js';
import { REFRESH_INTERVALS } from '../../config.js';

export class RefreshOverlay extends KeelElement {
    template() {
        return `
            <div class="refresh-interval-cover" data-ref="cover">
                <div class="refresh-interval-panel">
                    <div class="refresh-interval-label">Refresh interval</div>
                    <div class="refresh-interval-options" data-ref="options"></div>
                </div>
            </div>
        `;
    }

    afterMount() {
        this.refs.cover.addEventListener('click', (event) => {
            const option = event.target.closest('[data-ms]');
            if (option) {
                this.emit('keel:refresh-interval-change', { ms: Number(option.dataset.ms) });
                return;
            }
            if (!event.target.closest('.refresh-interval-panel')) {
                this.emit('keel:close-refresh-overlay');
            }
        });
    }

    render({ open = false, refreshIntervalMs = 5000 } = {}) {
        this.ensureInitialized();
        this.refs.cover.classList.toggle('is-open', open);
        this.refs.options.innerHTML = REFRESH_INTERVALS.map((opt) => `
            <div class="refresh-interval-option ${refreshIntervalMs === opt.ms ? 'is-active' : ''}" data-ms="${opt.ms}">
                ${opt.label}
            </div>
        `).join('');
    }
}

if (!customElements.get('keel-refresh-overlay')) {
    customElements.define('keel-refresh-overlay', RefreshOverlay);
}
