import './components/ObservabilityApp.js';

import { state, setAppShell } from './state.js';
import { hydrate } from './api.js';
import { renderChrome } from './render.js';

async function bootstrap() {
    const app = document.querySelector('keel-observability-app');
    if (!app) return;
    setAppShell(app);
    renderChrome();
    try {
        await hydrate();
    } catch (error) {
        state.connectionState = 'Error';
        renderChrome();
        app.showBootError(error && error.message ? error.message : String(error));
    }
}

document.addEventListener('DOMContentLoaded', () => {
    bootstrap().catch((error) => {
        console.error(error);
    });
});
