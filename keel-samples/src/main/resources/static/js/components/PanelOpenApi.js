import { KeelElement } from './base/KeelElement.js';
import { openApiOperations } from '../state.js';
import { escapeHtml } from '../utils.js';
import { API_BASE } from '../config.js';

const ICONS = {
    search: '<path d="M19 19l-4-4m0-7A7 7 0 1 1 1 8a7 7 0 0 1 14 0Z"/>',
    chevronRight: '<path d="m9 18 6-6-6-6"/>',
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4m4-5 5 5 5-5m-5 5V3"/>',
    lock: '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
    copy: '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    code2: '<path d="m18 16 4-4-4-4M6 8 2 12l4 4M14.5 4l-5 16"/>',
    terminal: '<polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/>',
    layoutDashboard: '<rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/><rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/>',
    settings: '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
    shield: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
    package: '<path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
    truck: '<path d="M10 17h4V5H2v12h3"/><path d="M20 17h2v-3.34a4 4 0 0 0-1.17-2.83L19 9h-5"/><path d="M14 17h1"/><circle cx="7.5" cy="17.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/>',
    shoppingCart: '<circle cx="8" cy="21" r="1"/><circle cx="19" cy="21" r="1"/><path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12"/>',
    creditCard: '<rect width="20" height="14" x="2" y="5" rx="2"/><line x1="2" x2="22" y1="10" y2="10"/>',
    fileText: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/>',
    box: '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
    activity: '<path d="M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"/>',
    database: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5V19A9 3 0 0 0 21 19V5"/><path d="M3 12A9 3 0 0 0 21 12"/>',
    layers: '<path d="m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z"/><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65"/><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"/>',
    user: '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>'
};

function svgIcon(name, className = 'w-4 h-4') {
    return `
      <svg class="${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:1em;height:1em;">
        ${ICONS[name] || ''}
      </svg>
    `;
}

function resolveRef(ref, components) {
    if (!ref) return null;
    const key = String(ref).split('/').pop();
    return components?.schemas?.[key] || null;
}

function resolveSchema(schema, components, seenRefs = new Set()) {
    if (!schema || typeof schema !== 'object') return schema;
    if (!schema.$ref) return schema;
    const ref = schema.$ref;
    if (seenRefs.has(ref)) return { __refCycle: ref, type: 'object', properties: {} };
    const resolved = resolveRef(ref, components);
    if (!resolved) return { __refMissing: ref };
    seenRefs.add(ref);
    const output = resolveSchema(resolved, components, seenRefs);
    seenRefs.delete(ref);
    return output;
}

function schemaTypeLabel(schema, components) {
    if (!schema) return 'any';
    const resolved = resolveSchema(schema, components, new Set());
    if (resolved?.type) return resolved.type;
    if (schema?.enum) return 'enum';
    if (schema?.$ref) return 'object';
    return 'any';
}

function contentSchema(content = {}) {
    if (!content || typeof content !== 'object') return null;
    if (content['application/json']?.schema) return content['application/json'].schema;
    const first = Object.values(content).find((item) => item?.schema);
    return first ? first.schema : null;
}

function generateExample(schema, components) {
    if (!schema) return '{}';

    const build = (node, depth = 0, seenRefs = new Set()) => {
        if (!node || depth > 3) return '...';
        const resolved = resolveSchema(node, components, seenRefs);
        if (!resolved || typeof resolved !== 'object') return 'string';
        if (resolved.__refMissing) return 'string';
        if (resolved.type === 'object') {
            const obj = {};
            Object.entries(resolved.properties || {}).forEach(([key, value]) => {
                obj[key] = build(value, depth + 1, seenRefs);
            });
            return obj;
        }
        if (resolved.type === 'array') return [build(resolved.items, depth + 1, seenRefs)];
        if (resolved.type === 'integer' || resolved.type === 'number') return 0;
        if (resolved.type === 'boolean') return true;
        if (resolved.type === 'null') return null;
        if (resolved.enum?.length) return resolved.enum[0];
        return 'string';
    };

    return JSON.stringify(build(schema), null, 2);
}

function syntaxHighlight(code, lang) {
    let source = String(code ?? '');
    if (lang === 'bash') {
        source = source.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        source = source.replace(/(curl|-H|Authorization:|Bearer)/g, '<span class="hl-keyword">$1</span>');
        source = source.replace(/(".*?")/g, '<span class="hl-string">$1</span>');
        return source;
    }

    if (typeof code !== 'string') {
        source = JSON.stringify(code, null, 2);
    }
    source = source.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return source.replace(/("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g, (match) => {
        let cls = 'hl-number';
        if (/^"/.test(match)) {
            cls = /:$/.test(match) ? 'hl-key' : 'hl-string';
        } else if (/true|false/.test(match)) {
            cls = 'hl-boolean';
        } else if (/null/.test(match)) {
            cls = 'hl-keyword';
        }
        return `<span class="${cls}">${match}</span>`;
    });
}

function buildSchemaRows(schema, components, depth = 0) {
    if (!schema) return [];

    const resolved = resolveSchema(schema, components, new Set());
    if (!resolved || typeof resolved !== 'object' || resolved.__refMissing || resolved.__refCycle) {
        return [];
    }

    if (resolved.type === 'array') {
        return buildSchemaRows(resolved.items, components, depth);
    }

    if (resolved.type !== 'object') {
        return [{
            name: 'body',
            type: resolved.type || 'any',
            description: resolved.description || '',
            required: true,
            depth
        }];
    }

    const required = resolved.required || [];

    return Object.entries(resolved.properties || {}).flatMap(([key, prop]) => {
        const propResolved = resolveSchema(prop, components, new Set());
        const typeStr = propResolved?.type || prop?.type || (prop?.$ref ? 'object' : 'any');
        const enumStr = Array.isArray(propResolved?.enum) && propResolved.enum.length
            ? `Enum: ${propResolved.enum.map((v) => String(v)).join(', ')}`
            : '';
        const descriptionParts = [propResolved?.description || prop?.description || '', enumStr].filter(Boolean);
        const row = {
            name: key,
            type: typeStr,
            description: descriptionParts.join(' '),
            required: required.includes(key),
            depth
        };

        if (propResolved?.type === 'object') {
            return [row, ...buildSchemaRows(propResolved, components, depth + 1)];
        }

        if (propResolved?.type === 'array' && propResolved.items) {
            const arrayRow = {
                ...row,
                type: `array<${schemaTypeLabel(propResolved.items, components)}>`
            };
            return [arrayRow, ...buildSchemaRows(propResolved.items, components, depth + 1)];
        }

        return [row];
    });
}

function getTagIconName(tag) {
    const t = String(tag || '').toLowerCase();
    if (t.includes('system')) return 'settings';
    if (t.includes('auth')) return 'shield';
    if (t.includes('product')) return 'package';
    if (t.includes('order')) return 'truck';
    if (t.includes('cart')) return 'shoppingCart';
    if (t.includes('checkout')) return 'creditCard';
    if (t.includes('log')) return 'fileText';
    if (t.includes('plugin')) return 'box';
    if (t.includes('observability')) return 'activity';
    if (t.includes('database')) return 'database';
    return 'layers';
}

function filterOperations(operations, filters) {
    const query = String(filters.query || '').toLowerCase();
    const activeTag = String(filters.tag || '');
    return operations.filter(({ path, method, operation }) => {
        const tags = operation.tags || [];
        const matchesSearch = path.toLowerCase().includes(query)
            || String(operation.summary || '').toLowerCase().includes(query)
            || tags.some((tag) => tag.toLowerCase().includes(query))
            || method.toLowerCase().includes(query);
        const matchesTag = !activeTag || tags.includes(activeTag);
        return matchesSearch && matchesTag;
    });
}

function renderCodeBlock(code, language, codeId) {
    const highlighted = syntaxHighlight(code, language);
    return `
        <div class="hightlight-code-block group">
            <div class="code-header">
                <span style="font-size:10px;text-transform:uppercase;font-weight:bold;letter-spacing:-0.05em;">${escapeHtml(language)}</span>
                <button class="copy-btn" data-copy-code-id="${escapeHtml(codeId)}" type="button">
                    ${svgIcon('copy', 'w-3.5 h-3.5')}
                </button>
            </div>
            <pre><code>${highlighted}</code></pre>
        </div>
    `;
}

function renderOpenApiTable(headers, rows, options = {}) {
    const wrapperStyle = options.wrapperStyle || '';

    return `
        <div class="schema-table-wrap" style="${wrapperStyle}">
            <table class="schema-table">
                <thead><tr>${headers.map((header) => `<th>${escapeHtml(header)}</th>`).join('')}</tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>
    `;
}

function renderRequestBodyTable(schema, components) {
    const rows = buildSchemaRows(schema, components).map((row) => {
        const reqBadge = row.required
            ? '<span style="color:#ef4444;font-size:8px;margin-left:8px;text-transform:uppercase;">Req</span>'
            : '';
        const indent = row.depth > 0 ? `padding-left:${1 + (row.depth * 1.25)}rem;` : '';

        return `
            <tr>
                <td class="font-mono text-primary" style="font-weight:bold;${indent}">${escapeHtml(row.name)} ${reqBadge}</td>
                <td class="text-outline" style="font-size:12px;font-style:italic;">body</td>
                <td class="font-mono text-on-surface-variant" style="font-size:12px;">${escapeHtml(row.type || 'any')}</td>
                <td class="text-on-surface-variant" style="font-size:12px;">${escapeHtml(row.description || '')}</td>
            </tr>
        `;
    }).join('');

    return renderOpenApiTable(['Name', 'In', 'Type', 'Description'], rows);
}

function renderEndpoint({ path, method, data, components, reqCodeId, resCodeId }) {
    const methodLower = method.toLowerCase();
    const methodColorClass = `bg-${methodLower}`;
    const requestBody = data.requestBody?.content?.['application/json']?.schema || contentSchema(data.requestBody?.content || {});
    const response200 = data.responses?.['200']?.content?.['application/json']?.schema || contentSchema(data.responses?.['200']?.content || {});
    const requestExample = generateExample(requestBody, components);
    const responseExample = generateExample(response200, components);

    let paramsHtml = '';
    if (Array.isArray(data.parameters) && data.parameters.length > 0) {
        const parameterRows = data.parameters.map((p) => `
            <tr>
                <td class="font-mono text-primary" style="font-weight:bold;">${escapeHtml(p.name || '')} ${p.required ? '<span style="color:#ef4444;font-size:8px;margin-left:8px;text-transform:uppercase;">Req</span>' : ''}</td>
                <td class="text-outline" style="font-size:12px;font-style:italic;">${escapeHtml(p.in || '')}</td>
                <td class="font-mono text-on-surface-variant" style="font-size:12px;">${escapeHtml(p.schema?.type || 'any')}</td>
                <td class="text-on-surface-variant" style="font-size:12px;">${escapeHtml(p.description || '')}</td>
            </tr>
        `).join('');

        paramsHtml = `
            <div>
                <h4 style="font-size:12px;font-weight:bold;text-transform:uppercase;letter-spacing:0.05em;color:var(--color-outline);margin-bottom:16px;">Parameters</h4>
                ${renderOpenApiTable(['Name', 'In', 'Type', 'Description'], parameterRows)}
            </div>
        `;
    }

    return `
        <article class="endpoint-card animate-pop-in">
            <div style="display:flex;flex-direction:column;gap:1.5rem;">
                <div style="display:flex;align-items:flex-start;gap:0.75rem;">
                    <span class="method-badge ${methodColorClass}">${escapeHtml(method)}</span>
                    <code class="font-mono text-primary" style="font-size:1.125rem;font-weight:500;word-break:break-all;">${escapeHtml(path)}</code>
                </div>
                <h3 class="font-headline" style="font-size:1.5rem;font-weight:500;">${escapeHtml(data.summary || 'No summary provided')}</h3>
                ${data.description ? `<p class="text-on-surface-variant">${escapeHtml(data.description)}</p>` : ''}
                ${paramsHtml}
                ${requestBody ? `
                    <div>
                        <h4 style="font-size:12px;font-weight:bold;text-transform:uppercase;letter-spacing:0.05em;color:var(--color-outline);margin-bottom:16px;">Request Body</h4>
                        ${renderRequestBodyTable(requestBody, components)}
                    </div>
                ` : ''}
            </div>
            <div style="display:flex;flex-direction:column;gap:2rem;">
                ${requestBody ? `
                    <div style="display:flex;flex-direction:column;gap:1rem;">
                        <div style="display:flex;align-items:center;justify-content:space-between;padding:0 8px;">
                            <span style="font-size:12px;font-weight:bold;color:var(--color-outline);text-transform:uppercase;">Example Request</span>
                            ${svgIcon('code2', 'text-outline')}
                        </div>
                        ${renderCodeBlock(requestExample, 'json', reqCodeId)}
                    </div>
                ` : ''}
                <div style="display:flex;flex-direction:column;gap:1rem;">
                    <div style="display:flex;align-items:center;justify-content:space-between;padding:0 8px;">
                        <span style="font-size:12px;font-weight:bold;color:var(--color-outline);text-transform:uppercase;">Example Response</span>
                        <span style="font-size:12px;padding:2px 8px;background:rgba(0,108,73,0.1);color:var(--color-secondary);border-radius:4px;font-weight:bold;">200 OK</span>
                    </div>
                    ${renderCodeBlock(responseExample, 'json', resCodeId)}
                </div>
            </div>
        </article>
    `;
}

export class PanelOpenApi extends KeelElement {
    hostStyles() {
        return 'height: 100%;';
    }

    template() {
        return `
            <style>
                :host {
                    --color-surface: #faf9f5;
                    --color-primary: #050c1b;
                    --color-secondary: #006c49;
                    --color-secondary-container: #6cf8bb;
                    --color-on-secondary-container: #00714d;
                    --color-surface-container: #eeeeea;
                    --color-surface-container-low: #f4f4f0;
                    --color-surface-container-highest: #e2e3df;
                    --color-outline: #75777c;
                    --color-on-surface-variant: #44474b;
                    --font-headline: "Newsreader", serif;
                    --font-body: "Inter", sans-serif;
                    --font-mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
                    overflow: hidden;
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                button { background: none; border: none; cursor: pointer; font-family: inherit; }
                a { text-decoration: none; color: inherit; }
                .app-container { display: flex; flex: 1; align-items: stretch; overflow: hidden; height: 100%; font-family: var(--font-body); color: var(--color-primary); line-height: 1.5; gap: 1.5rem; }
                .secondary-sidebar { width: 18rem; padding: 2rem; background: rgba(255, 255, 255, 0.7); border-radius: 1.5rem; backdrop-filter: blur(8px); overflow-y: auto; overflow-x: hidden; display: none; min-height: 0; max-height: 100%; flex: 0 0 18rem; }
                @media (min-width: 1280px) { .secondary-sidebar { display: block; } }
                .main-content { flex: 1; padding: 2rem; overflow-y: auto; overflow-x: hidden; background: rgba(255, 255, 255, 0.7); border-radius: 1.5rem; min-width: 0; min-height: 0; background-image: radial-gradient(circle, rgba(5, 12, 27, 0.08) 1px, transparent 1px); background-size: 24px 24px; background-origin: border-box; }
                @media (min-width: 1024px) { .main-content { padding: 2rem; } }
                .content-wrapper { max-width: 96rem; margin: 0 auto; }
                .font-headline { font-family: var(--font-headline); }
                .font-mono { font-family: var(--font-mono); }
                .text-primary { color: var(--color-primary); }
                .text-secondary { color: var(--color-secondary); }
                .text-outline { color: var(--color-outline); }
                .text-on-surface-variant { color: var(--color-on-surface-variant); }
                .hide-scrollbar::-webkit-scrollbar { display: none; }
                .hide-scrollbar { -ms-overflow-style: none; scrollbar-width: none; }
                .nav-group-title { font-size: 0.625rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.2em; color: var(--color-outline); margin-bottom: 1rem; }
                .nav-button { display: flex; align-items: center; gap: 0.75rem; width: 100%; padding: 1rem; border-radius: 4rem; transition: all 0.2s; font-size: 0.875rem; font-weight: 500; text-transform: capitalize; }
                .nav-button.active { background-color: #fcfcfc; color: #0f172a; box-shadow: rgba(15, 23, 42, 0.08) 0px 10px 28px; }
                .nav-button:not(.active) { color: #475569; }
                .nav-button:not(.active):hover { background-color: #f5f5f4; }
                .search-container { display: flex; gap: 1rem; margin-bottom: 4rem; flex-direction: column; }
                @media (min-width: 640px) { .search-container { flex-direction: row; } }
                .search-input-wrapper { position: relative; flex: 1; }
                .search-icon { position: absolute; left: 1rem; top: 50%; transform: translateY(-50%); color: var(--color-outline); width: 1.25rem; height: 1.25rem; pointer-events: none; }
                .search-input { width: 100%; background: #fff; border: 1px solid #e5e7eb; border-radius: 0.75rem; padding: 1rem 1rem 1rem 3rem; font-family: inherit; font-size: 1rem; color: var(--color-primary); outline: none; transition: box-shadow 0.2s; }
                .search-input:focus { box-shadow: 0 0 0 2px #e5e7eb; }
                .btn-download { padding: 1rem 1.5rem; background: #fff; border: 1px solid #e5e7eb; border-radius: 0.75rem; font-weight: 700; font-size: 0.875rem; display: flex; align-items: center; gap: 0.5rem; transition: background 0.2s; }
                .btn-download:hover { background: #f5f5f4; }
                .endpoint-card { display: grid; grid-template-columns: 1fr; gap: 3rem; padding: 4rem 0; border-bottom: 1px solid rgba(229, 231, 235, 0.6); }
                .endpoint-card:first-child { padding-top: 0; }
                .endpoint-card:last-child { border-bottom: none; }
                @media (min-width: 1024px) { .endpoint-card { grid-template-columns: 1fr 1fr; } }
                .method-badge { padding: 0.25rem 0.75rem; font-size: 0.6875rem; font-weight: 700; border-radius: 9999px; text-transform: uppercase; letter-spacing: 0.1em; display: inline-block; }
                .bg-get { background: var(--color-secondary-container); color: var(--color-on-secondary-container); }
                .bg-post { background: var(--color-primary); color: #fff; }
                .bg-put { background: #2563eb; color: #fff; }
                .bg-delete { background: #dc2626; color: #fff; }
                .bg-patch { background: #d97706; color: #fff; }
                .bg-options, .bg-head, .bg-trace { background: #64748b; color: #fff; }
                .schema-table-wrap { border: 1px solid #f5f5f4; background: var(--color-surface-container-low); border-radius: 12px; overflow: hidden; }
                .schema-table { width: 100%; text-align: left; font-size: 0.875rem; border-collapse: collapse; }
                .schema-table th,
                .schema-table td { padding: 0.75rem 1rem; vertical-align: top; border-bottom: 1px solid rgba(229, 231, 235, 0.5); }
                .schema-table th { font-weight: 600; font-size: 0.625rem; text-transform: uppercase; color: var(--color-on-surface-variant); background: rgba(229, 231, 235, 0.5); }
                .schema-table tbody tr:last-child td { border-bottom: none; }
                .schema-table tr:hover td { background: rgba(249, 250, 251, 0.5); }
                .hightlight-code-block { border-radius: 0.75rem; background-color: #0f172a; position: relative; overflow: visible; }
                .hightlight-code-block pre { margin: 0; padding: 1.5rem; overflow: visible; white-space: pre-wrap; word-break: break-word; font-family: var(--font-mono); font-size: 0.875rem; line-height: 1.5; color: #c5c8c6; }
                .code-header { position: absolute; right: 1rem; top: 1rem; display: flex; align-items: center; gap: 0.5rem; color: rgba(255, 255, 255, 0.4); z-index: 10; }
                .copy-btn { color: inherit; display: flex; align-items: center; }
                .copy-btn:hover { color: #fff; }
                .hl-key { color: #96CBFE; }
                .hl-string { color: #A8FF60; }
                .hl-number { color: #FF73FD; }
                .hl-boolean { color: #99CC99; }
                .hl-keyword { color: #C6C5FE; font-weight: bold; }
                @keyframes popIn { 0% { opacity: 0; transform: translateY(20px); } 100% { opacity: 1; transform: translateY(0); } }
                .animate-pop-in { animation: popIn 0.2s cubic-bezier(0.16, 1, 0.3, 1) forwards; }
                @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .5; } }
                .animate-pulse { animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite; }
                .empty-panel, .error-panel { background:#fff; padding:2rem; border-radius:1rem; border:1px solid #f5f5f4; margin-bottom:2rem; }
                .error-panel pre { margin-top: 1rem; white-space: pre-wrap; word-break: break-word; color: var(--color-on-surface-variant); }
                .retry-btn, .clear-btn { color: var(--color-secondary); font-weight: 700; font-size: 14px; margin-top: 1rem; }
                @media (max-width: 1279px) {
                    .app-container { padding: 1rem; }
                    .main-content { border-radius: 1.25rem; }
                }
            </style>
            <div class="app-container">
                <aside class="secondary-sidebar hide-scrollbar" data-ref="sidebar"></aside>
                <main class="main-content">
                    <div class="content-wrapper" data-ref="content"></div>
                </main>
            </div>
        `;
    }

    afterMount() {
        this._codeMap = new Map();
        this.refs.sidebar.addEventListener('click', (event) => {
            const tagButton = event.target.closest('[data-tag]');
            if (!tagButton) return;
            this.emit('keel:openapi-filter-change', {
                query: this._lastQuery || '',
                tag: tagButton.dataset.tag || ''
            });
        });

        this.refs.content.addEventListener('input', (event) => {
            const input = event.target.closest('[data-role="search-input"]');
            if (!input) return;
            this._shouldRefocusSearch = true;
            this._searchSelectionStart = input.selectionStart;
            this._searchSelectionEnd = input.selectionEnd;
            this.emit('keel:openapi-filter-change', {
                query: input.value,
                tag: this._lastTag || ''
            });
        });

        this.refs.content.addEventListener('click', async (event) => {
            const downloadBtn = event.target.closest('[data-role="download-spec"]');
            if (downloadBtn) {
                window.location.href = `${API_BASE}/_system/docs/openapi.json`;
                return;
            }

            const retryBtn = event.target.closest('[data-role="retry-openapi"]');
            if (retryBtn) {
                this.emit('keel:openapi-retry');
                return;
            }

            const clearBtn = event.target.closest('[data-role="clear-filters"]');
            if (clearBtn) {
                this.emit('keel:openapi-filter-change', { query: '', tag: '' });
                return;
            }

            const copyBtn = event.target.closest('[data-copy-code-id]');
            if (!copyBtn) return;
            const codeId = copyBtn.dataset.copyCodeId || '';
            const code = this._codeMap.get(codeId);
            if (!code) return;
            try {
                await navigator.clipboard.writeText(code);
                copyBtn.innerHTML = svgIcon('check', 'w-3.5 h-3.5');
                window.setTimeout(() => {
                    copyBtn.innerHTML = svgIcon('copy', 'w-3.5 h-3.5');
                }, 1500);
            } catch {
                // ignore clipboard errors
            }
        });
    }

    render(appState) {
        this.ensureInitialized();
        const spec = appState.openApiSpec;
        const loadState = appState.openApiLoadState || 'idle';
        const filters = appState.openApiFilters || { query: '', tag: '' };
        const allOps = openApiOperations(spec);
        const tags = Array.from(new Set(allOps.flatMap((item) => item.operation.tags || []))).sort((a, b) => a.localeCompare(b));
        const filteredPaths = filterOperations(allOps, filters);

        this._lastQuery = filters.query || '';
        this._lastTag = filters.tag || '';

        this.refs.sidebar.innerHTML = `
            <nav style="display:flex;flex-direction:column;gap:2rem;">
                <div>
                    <h4 class="nav-group-title">Resources</h4>
                    <div style="display:flex;flex-direction:column;gap:4px;">
                        <button type="button" data-tag="" class="nav-button ${!filters.tag ? 'active' : ''}">
                            ${svgIcon('layoutDashboard')} All Endpoints
                        </button>
                        ${tags.map((tag) => `
                            <button type="button" data-tag="${escapeHtml(tag)}" class="nav-button ${filters.tag === tag ? 'active' : ''}">
                                ${svgIcon(getTagIconName(tag))} ${escapeHtml(tag)}
                            </button>
                        `).join('')}
                    </div>
                </div>
                <div>
                    <h4 class="nav-group-title">Support</h4>
                    <div style="display:flex;flex-direction:column;gap:4px;">
                        <a href="${API_BASE}/_system/docs" target="_blank" rel="noreferrer" class="nav-button">
                            ${svgIcon('user')} Developer Portal
                        </a>
                    </div>
                </div>
            </nav>
        `;

        if (!spec && (loadState === 'idle' || loadState === 'loading')) {
            this.refs.content.innerHTML = '<div class="empty-panel">Loading OpenAPI specification...</div>';
            return;
        }

        if (loadState === 'error') {
            this.refs.content.innerHTML = `
                <div class="error-panel">
                    <h3>Unable to fetch OpenAPI specification</h3>
                    <pre>${escapeHtml(appState.openApiError || 'Unknown error')}</pre>
                    <button class="retry-btn" data-role="retry-openapi" type="button">Retry</button>
                </div>
            `;
            return;
        }

        const info = spec?.info || { title: 'OpenAPI', version: 'n/a', description: '' };
        this._codeMap = new Map();
        this._codeMap.set('auth-curl', 'curl -H "Authorization: Bearer YOUR_API_TOKEN" \\\n  https://api.keelprecision.com/api/_system/health');

        const endpointsHtml = filteredPaths.length
            ? filteredPaths.map(({ path, method, operation }, index) => {
                const requestSchema = contentSchema(operation.requestBody?.content || {});
                const responseSchema = contentSchema(operation.responses?.['200']?.content || {});
                const reqCodeId = `req-${index}`;
                const resCodeId = `res-${index}`;
                this._codeMap.set(reqCodeId, generateExample(requestSchema, spec.components || {}));
                this._codeMap.set(resCodeId, generateExample(responseSchema, spec.components || {}));
                return renderEndpoint({
                    path,
                    method: method.toUpperCase(),
                    data: operation,
                    components: spec.components || {},
                    reqCodeId,
                    resCodeId
                });
            }).join('')
            : `
                <div class="animate-pop-in" style="padding:5rem 0;text-align:center;display:flex;flex-direction:column;align-items:center;gap:1rem;">
                    <div style="width:4rem;height:4rem;background:#f5f5f4;border-radius:9999px;display:flex;align-items:center;justify-content:center;color:var(--color-outline);">
                        ${svgIcon('search', 'w-8 h-8')}
                    </div>
                    <p class="text-on-surface-variant" style="font-weight:500;">No endpoints match your search criteria.</p>
                    <button class="clear-btn" data-role="clear-filters" type="button">Clear all filters</button>
                </div>
            `;

        this.refs.content.innerHTML = `
            <header style="margin-bottom:4rem;">
                <nav style="display:flex;align-items:center;gap:8px;font-size:12px;font-weight:bold;color:var(--color-secondary);text-transform:uppercase;letter-spacing:0.1em;margin-bottom:16px;">
                    <span>Documentation</span> ${svgIcon('chevronRight', 'w-3 h-3')} <span style="color:rgba(68,71,75,0.4);">v${escapeHtml(info.version || 'n/a')}</span>
                </nav>
                <h1 class="font-headline text-primary" style="font-size:3.75rem;font-weight:500;line-height:1;letter-spacing:-0.025em;margin-bottom:16px;">${escapeHtml(info.title || 'Keel API')}</h1>
                <p class="text-on-surface-variant" style="font-size:1.125rem;max-width:42rem;">${escapeHtml(info.description || '')}</p>
            </header>

            <div class="search-container">
                <div class="search-input-wrapper">
                    ${svgIcon('search', 'search-icon')}
                    <input data-role="search-input" class="search-input" placeholder="Filter endpoints, methods, or tags..." type="text" value="${escapeHtml(filters.query || '')}">
                </div>
                <button class="btn-download" data-role="download-spec" type="button">
                    ${svgIcon('download')} Download Spec
                </button>
            </div>

            <section style="background:#fff;padding:2.5rem;border-radius:1rem;border:1px solid #f5f5f4;margin-bottom:6rem;">
                <div style="display:flex;align-items:center;gap:12px;margin-bottom:24px;">
                    <div style="width:2.5rem;height:2.5rem;border-radius:9999px;background:var(--color-secondary-container);color:var(--color-on-secondary-container);display:flex;align-items:center;justify-content:center;">
                        ${svgIcon('lock')}
                    </div>
                    <h2 class="font-headline" style="font-size:1.875rem;font-weight:500;">Authentication</h2>
                </div>
                <p class="text-on-surface-variant" style="margin-bottom:2rem;max-width:48rem;">
                    All API requests must be authenticated using a Bearer Token. You can generate tokens from the <span style="color:var(--color-secondary);font-weight:600;text-decoration:underline;text-underline-offset:4px;cursor:pointer;">Security Settings</span> in your developer console.
                </p>
                ${renderCodeBlock(this._codeMap.get('auth-curl'), 'bash', 'auth-curl')}
            </section>

            <div>
                <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:2rem;">
                    <h2 class="font-headline text-primary" style="font-size:2.25rem;font-weight:500;">
                        ${filters.tag ? `${escapeHtml(filters.tag)} Endpoints` : 'All Endpoints'}
                    </h2>
                    <span style="font-size:12px;font-weight:bold;color:var(--color-outline);text-transform:uppercase;letter-spacing:0.1em;background:#f5f5f4;padding:4px 12px;border-radius:9999px;">
                        ${filteredPaths.length} Results
                    </span>
                </div>

                <div style="display:flex;flex-direction:column;gap:1rem;">
                    ${endpointsHtml}
                </div>
            </div>
        `;

        if (this._shouldRefocusSearch) {
            const input = this.refs.content.querySelector('[data-role="search-input"]');
            if (input) {
                input.focus();
                const start = Number.isInteger(this._searchSelectionStart) ? this._searchSelectionStart : input.value.length;
                const end = Number.isInteger(this._searchSelectionEnd) ? this._searchSelectionEnd : input.value.length;
                input.setSelectionRange(start, end);
            }
            this._shouldRefocusSearch = false;
        }
    }
}

if (!customElements.get('keel-panel-openapi')) {
    customElements.define('keel-panel-openapi', PanelOpenApi);
}
