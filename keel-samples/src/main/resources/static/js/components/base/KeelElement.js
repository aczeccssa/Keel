let sharedCssTextPromise = null;
let sharedConstructableSheetPromise = null;

function getSharedStylesheetLink() {
    return document.querySelector('link[href$="css/style.css"]');
}

function readCssTextFromLink(link) {
    return new Promise((resolve) => {
        const resolveFromSheet = () => {
            try {
                const cssText = Array.from(link.sheet?.cssRules || []).map((rule) => rule.cssText).join('\n');
                resolve(cssText);
            } catch {
                resolve('');
            }
        };

        if (link.sheet) {
            resolveFromSheet();
            return;
        }

        link.addEventListener('load', resolveFromSheet, { once: true });
        link.addEventListener('error', () => resolve(''), { once: true });
    });
}

function getSharedCssText() {
    if (!sharedCssTextPromise) {
        const link = getSharedStylesheetLink();
        sharedCssTextPromise = link ? readCssTextFromLink(link) : Promise.resolve('');
    }
    return sharedCssTextPromise;
}

function getSharedConstructableSheet() {
    if (!sharedConstructableSheetPromise) {
        sharedConstructableSheetPromise = getSharedCssText().then((cssText) => {
            const sheet = new CSSStyleSheet();
            sheet.replaceSync(cssText);
            return sheet;
        }).catch(() => null);
    }
    return sharedConstructableSheetPromise;
}

async function applySharedStyles(root) {
    if ('adoptedStyleSheets' in Document.prototype && 'replaceSync' in CSSStyleSheet.prototype) {
        const sheet = await getSharedConstructableSheet();
        if (sheet && !root.adoptedStyleSheets.includes(sheet)) {
            root.adoptedStyleSheets = [...root.adoptedStyleSheets, sheet];
        }
        return;
    }

    const cssText = await getSharedCssText();
    if (!cssText || root.querySelector('[data-shared-style]')) return;
    const style = document.createElement('style');
    style.dataset.sharedStyle = 'true';
    style.textContent = cssText;
    root.prepend(style);
}

export class KeelElement extends HTMLElement {
    constructor() {
        super();
        this.attachShadow({ mode: 'open' });
        this.refs = {};
        this._initialized = false;
    }

    connectedCallback() {
        this.ensureInitialized();
    }

    ensureInitialized() {
        if (this._initialized) return;
        this.shadowRoot.innerHTML = `
            <style>
                :host {
                    display: block;
                    min-width: 0;
                    min-height: 0;
                    ${this.hostStyles()}
                }
            </style>
            ${this.template()}
        `;
        this.refs = collectRefs(this.shadowRoot);
        this.afterMount();
        void applySharedStyles(this.shadowRoot);
        this._initialized = true;
    }

    hostStyles() {
        return '';
    }

    template() {
        return '';
    }

    afterMount() {
        // subclass hook
    }

    emit(name, detail = {}) {
        this.dispatchEvent(new CustomEvent(name, {
            detail,
            bubbles: true,
            composed: true
        }));
    }
}

function collectRefs(root) {
    const refs = {};
    root.querySelectorAll('[data-ref]').forEach((el) => {
        refs[el.dataset.ref] = el;
    });
    return refs;
}
