const SHADOW_BASE_RESET = `
    *, *::before, *::after {
        box-sizing: border-box;
    }

    button,
    input,
    select,
    textarea {
        font: inherit;
        color: inherit;
    }

    a {
        color: inherit;
        text-decoration: none;
    }

    svg,
    img {
        display: block;
        max-width: 100%;
    }

    [hidden] {
        display: none !important;
    }
`;

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

                ${SHADOW_BASE_RESET}
            </style>
            ${this.template()}
        `;
        this.refs = collectRefs(this.shadowRoot);
        this.afterMount();
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
