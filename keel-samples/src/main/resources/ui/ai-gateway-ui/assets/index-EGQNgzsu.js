(function(){const e=document.createElement("link").relList;if(e&&e.supports&&e.supports("modulepreload"))return;for(const a of document.querySelectorAll('link[rel="modulepreload"]'))r(a);new MutationObserver(a=>{for(const s of a)if(s.type==="childList")for(const i of s.addedNodes)i.tagName==="LINK"&&i.rel==="modulepreload"&&r(i)}).observe(document,{childList:!0,subtree:!0});function t(a){const s={};return a.integrity&&(s.integrity=a.integrity),a.referrerPolicy&&(s.referrerPolicy=a.referrerPolicy),a.crossOrigin==="use-credentials"?s.credentials="include":a.crossOrigin==="anonymous"?s.credentials="omit":s.credentials="same-origin",s}function r(a){if(a.ep)return;a.ep=!0;const s=t(a);fetch(a.href,s)}})();const q=`
    *, *::before, *::after { box-sizing: border-box; }
    button, input, select, textarea { font: inherit; color: inherit; }
    a { color: inherit; text-decoration: none; }
    svg, img { display: block; max-width: 100%; }
    [hidden] { display: none !important; }
`;class y extends HTMLElement{constructor(){super(),this.attachShadow({mode:"open"}),this.refs={},this._initialized=!1}connectedCallback(){this.ensureInitialized()}ensureInitialized(){this._initialized||(this.shadowRoot.innerHTML=`
            <style>
                :host {
                    display: block;
                    min-width: 0;
                    min-height: 0;
                    ${this.hostStyles()}
                }
                ${q}
            </style>
            ${this.template()}
        `,this.refs=G(this.shadowRoot),this.afterMount(),this._initialized=!0)}hostStyles(){return""}template(){return""}afterMount(){}emit(e,t={}){this.dispatchEvent(new CustomEvent(e,{detail:t,bubbles:!0,composed:!0}))}}function G(c){const e={};return c.querySelectorAll("[data-ref]").forEach(t=>{e[t.dataset.ref]=t}),e}const g={activeTab:"overview",loggedIn:!1,user:null,accessToken:null,refreshToken:null,autoRefreshMs:0,_refreshTimer:null},W={providers:"channels",pools:"groups"};function F(c){return W[c]||c}function V(c){const e=F(c);g.activeTab=e,window.location.hash=e}function B(){const c=window.location.hash.slice(1);c&&(g.activeTab=F(c))}const f={account:"/api/plugins/account",token:"/api/plugins/token",airelay:"/api/plugins/airelay",riskcontrol:"/api/plugins/riskcontrol"},D=[{id:"overview",label:"Overview",icon:"dashboard",hint:"Cost & usage overview",section:"OVERVIEW"},{id:"dashboard",label:"Dashboard",icon:"speed",hint:"Operations metrics",section:"OVERVIEW"},{id:"usage",label:"Usage",icon:"usage",hint:"Per-request detail log",section:"OVERVIEW"},{id:"availability",label:"Availability",icon:"dns",hint:"Channel reliability",section:"ROUTING"},{id:"channels",label:"Channels",icon:"dns",hint:"Upstream providers",section:"ROUTING"},{id:"groups",label:"Groups",icon:"hub",hint:"Routing pools",section:"ROUTING"},{id:"keys",label:"API Keys",icon:"key",hint:"Virtual tokens",section:"BILLING"},{id:"pricing",label:"Pricing",icon:"pricing",hint:"Model rate cards",section:"BILLING",badge:"customers"},{id:"customers",label:"Customers",icon:"group",hint:"End-customer directory",section:"BILLING",badge:"customers"},{id:"codes",label:"Redemption",icon:"gift",hint:"Credit top-up codes",section:"BILLING"},{id:"ratelimits",label:"Rate Limits",icon:"speed",hint:"Token bucket rules",section:"SYSTEM"},{id:"users",label:"Users",icon:"group",hint:"Account management",section:"SYSTEM"}],P="ai_gateway_token",N="ai_gateway_refresh";function Y(){const c=localStorage.getItem(P),e=localStorage.getItem(N);c&&(g.accessToken=c,g.refreshToken=e,g.loggedIn=!0)}async function J(c,e){const t=await fetch(`${f.account}/v1/auth/login`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email:c,password:e})});if(!t.ok){const a=await t.json().catch(()=>({}));throw new Error(a.error||`Login failed (${t.status})`)}const r=await t.json();return H(r),r}async function Q(c,e,t){const r=await fetch(`${f.account}/v1/auth/register`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email:c,password:e,displayName:t})});if(!r.ok){const s=await r.json().catch(()=>({}));throw new Error(s.error||`Register failed (${r.status})`)}const a=await r.json();return H(a),a}async function X(){if(!g.refreshToken)throw new Error("No refresh token");const c=await fetch(`${f.account}/v1/auth/refresh`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({refreshToken:g.refreshToken})});if(!c.ok)throw new Error("Refresh failed");const e=await c.json();return H(e),e}function j(){g.accessToken=null,g.refreshToken=null,g.loggedIn=!1,g.user=null,localStorage.removeItem(P),localStorage.removeItem(N)}function H(c){g.accessToken=c.accessToken,g.refreshToken=c.refreshToken,g.loggedIn=!0,g.user=c.user||null,localStorage.setItem(P,c.accessToken),c.refreshToken&&localStorage.setItem(N,c.refreshToken)}async function v(c,e={}){const t={...e.headers};g.accessToken&&(t.Authorization=`Bearer ${g.accessToken}`),e.body&&!t["Content-Type"]&&(t["Content-Type"]="application/json");const r=await fetch(c,{...e,headers:t});if(r.status===401)try{await X(),t.Authorization=`Bearer ${g.accessToken}`;const a=await fetch(c,{...e,headers:t});if(!a.ok)throw new Error(`HTTP ${a.status}`);return a.json()}catch{throw j(),new Error("Session expired")}if(!r.ok){const a=await r.json().catch(()=>({}));throw new Error(a.error||`HTTP ${r.status}`)}return r.status===204?null:r.json()}async function w(c,e){return v(c,{method:"POST",body:JSON.stringify(e)})}async function I(c,e){return v(c,{method:"PUT",body:JSON.stringify(e)})}async function S(c){return v(c,{method:"DELETE"})}class Z extends y{hostStyles(){return"display:block;"}template(){return`
            <style>
                :host { display: block; }
                .grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                    gap: 16px;
                    background: transparent;
                }
                .tile {
                    background: var(--paper);
                    padding: 22px 22px 20px;
                    border: 2px solid var(--ink);
                    position: relative;
                }
                .label {
                    font-family: var(--font-mono);
                    font-size: 10px; font-weight: 800; letter-spacing: 0.16em; text-transform: uppercase;
                    color: var(--ink);
                    display: flex; align-items: center; gap: 7px;
                }
                .label::before { content: ">"; color: var(--teal); font-weight: 800; }
                .value {
                    margin-top: 12px;
                    font-family: var(--font-headline);
                    font-size: clamp(28px, 3vw, 38px);
                    line-height: 0.9;
                    letter-spacing: -0.04em;
                    color: var(--ink);
                    font-feature-settings: 'tnum';
                    word-break: break-word;
                }
                .hint {
                    margin-top: 9px;
                    font-family: var(--font-mono);
                    font-size: 10px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase;
                    color: var(--muted);
                }
            </style>
            <div class="grid" data-ref="grid"></div>
        `}render({entries:e=[],silent:t=!1}={}){if(t&&this.refs.grid.children.length===e.length){e.forEach(([r,a,s],i)=>{const o=this.refs.grid.children[i],d=o.querySelector(".value"),h=o.querySelector(".hint"),n=String(a??"");d&&d.textContent!==n&&this._tweenValue(d,d.textContent,n),h&&(h.textContent=s?String(s):"")});return}this.refs.grid.innerHTML=e.map(([r,a,s])=>`
            <div class="tile">
                <div class="label">${this._escape(r)}</div>
                <div class="value">${this._escape(String(a??""))}</div>
                ${s?`<div class="hint">${this._escape(String(s))}</div>`:""}
            </div>
        `).join(""),this._animate()}_tweenValue(e,t,r){const a=p=>{const u=String(p).match(/^(\D*?)([\d.,]+)(.*)$/);return u?{prefix:u[1],num:parseFloat(u[2].replace(/,/g,""))||0,suffix:u[3],decimals:(u[2].split(".")[1]||"").length}:null},s=a(t),i=a(r);if(!s||!i){e.textContent=r;return}const o=performance.now(),d=600,h=p=>{const u=Math.min((p-o)/d,1),b=1-Math.pow(1-u,3),x=s.num+(i.num-s.num)*b;e.textContent=s.prefix+x.toLocaleString("en-US",{minimumFractionDigits:i.decimals,maximumFractionDigits:i.decimals})+i.suffix,u<1&&requestAnimationFrame(h)};requestAnimationFrame(h),e.style.transition="color 200ms ease";const n=e.style.color;e.style.color="var(--teal)",setTimeout(()=>{e.style.color=n},400)}_escape(e){return e.replace(/[&<>"']/g,t=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"})[t])}_animate(){const e=window.gsap,t=this.refs.grid.querySelectorAll(".tile");!e||t.length===0||e.fromTo(t,{autoAlpha:0,y:12},{autoAlpha:1,y:0,duration:.4,ease:"power2.out",stagger:.05})}}customElements.define("keel-stat-grid",Z);class ee extends y{hostStyles(){return"display:block;"}template(){return`
            <style>
                :host { display: block; min-width: 0; }
                .wrap {
                    overflow-x: auto;
                    background: var(--paper);
                    border-top: 2px solid var(--ink);
                    border-bottom: 2px solid var(--ink);
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    table-layout: auto;
                    font-family: var(--font-mono);
                    font-size: 11px;
                    letter-spacing: 0.02em;
                }
                thead th {
                    position: sticky;
                    top: 0;
                    z-index: 1;
                    text-align: left;
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    color: var(--on-accent);
                    background: var(--surface-accent);
                    padding: 11px 12px;
                    white-space: nowrap;
                    border-right: 1px solid rgba(244,244,240,0.28);
                }
                thead th::before { content: "// "; color: var(--teal); opacity: 0.6; }
                thead th:last-child { border-right: 0; }
                tbody td {
                    padding: 12px;
                    border-right: 1px solid var(--ink);
                    border-bottom: 1px solid var(--ink);
                    color: var(--ink);
                    vertical-align: middle;
                    font-family: var(--font-mono);
                    line-height: 1.35;
                }
                tbody td:last-child { border-right: 0; }
                tbody tr:last-child td { border-bottom: 0; }
                tbody tr:nth-child(even) td { background: var(--color-surface-container-low, #ebe9e3); }
                tbody tr:hover td { background: var(--surface-accent); color: var(--on-accent); }
                tbody tr:hover code,
                tbody tr:hover samp,
                tbody tr:hover data { background: var(--surface-strong); color: var(--ink); border-color: var(--surface-strong); }
                code,
                samp,
                data {
                    display: inline-block;
                    max-width: 360px;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    vertical-align: middle;
                    font-family: var(--font-mono);
                    font-size: 10.5px;
                    font-weight: 800;
                    letter-spacing: 0.04em;
                    text-transform: uppercase;
                    background: var(--surface-accent);
                    color: var(--on-accent);
                    border: 1px solid var(--surface-accent);
                    padding: 2px 6px;
                }
                .chip {
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                    padding: 5px 8px;
                    border: 1px solid var(--ink);
                    background: var(--paper);
                    color: var(--ink);
                    font-family: var(--font-mono);
                    font-size: 9.5px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                    white-space: nowrap;
                }
                .chip.is-healthy { background: var(--green-soft); }
                .chip.is-warn { background: var(--amber-soft); }
                .chip.is-alert { background: var(--red); color: var(--paper); }
                tbody td button {
                    appearance: none;
                    -webkit-appearance: none;
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    gap: 6px;
                    padding: 7px 11px;
                    border: 2px solid var(--ink);
                    border-radius: 0;
                    background: var(--paper);
                    color: var(--ink);
                    box-shadow: none;
                    cursor: pointer;
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.08em;
                    line-height: 1;
                    text-transform: uppercase;
                    white-space: nowrap;
                    vertical-align: middle;
                    transition: background 120ms var(--ease-smooth), color 120ms var(--ease-smooth), border-color 120ms var(--ease-smooth), transform 120ms var(--ease-smooth);
                }
                tbody td button + button { margin-left: 6px; }
                tbody td button:hover {
                    background: var(--surface-accent);
                    color: var(--on-accent);
                    transform: translate(-1px, -1px);
                }
                tbody td button.btn-danger,
                tbody td button.btn-danger-ghost,
                tbody td button.btn-action.danger,
                tbody td button.btn-sm {
                    border-color: var(--red);
                    color: var(--red);
                }
                tbody td button.btn-danger:hover,
                tbody td button.btn-danger-ghost:hover,
                tbody td button.btn-action.danger:hover,
                tbody td button.btn-sm:hover {
                    background: var(--red);
                    border-color: var(--red);
                    color: var(--paper);
                }
                tbody td button.btn-action.success {
                    border-color: var(--green);
                    color: var(--green);
                }
                tbody td button.btn-action.success:hover {
                    background: var(--green);
                    border-color: var(--green);
                    color: var(--paper);
                }
                .btn-reset {
                    padding: 7px 11px;
                    border: 2px solid var(--ink);
                    background: var(--paper);
                    color: var(--ink);
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.1em;
                    text-transform: uppercase;
                    cursor: pointer;
                    transition: background 120ms var(--ease-smooth), color 120ms var(--ease-smooth), transform 120ms var(--ease-smooth);
                }
                .btn-reset:hover { background: var(--red); color: var(--on-accent); transform: translate(-1px, -1px); }
                tbody tr:hover .chip { border-color: var(--paper); }
                tbody tr:hover .chip.is-alert,
                tbody tr:hover .btn-reset,
                tbody tr:hover button { border-color: var(--paper); }
                .km-empty {
                    text-align: center;
                    color: var(--muted);
                    padding: 36px 16px;
                    font-family: var(--font-mono);
                    font-size: 12px;
                    font-weight: 800;
                    letter-spacing: 0.1em;
                    text-transform: uppercase;
                }
            </style>
            <div class="wrap" data-ref="wrap"></div>
        `}render({headers:e=[],rows:t=[],emptyHtml:r="",silent:a=!1}={}){if(t.length===0){this.refs.wrap.innerHTML=r||'<div class="km-empty">// NO DATA</div>',this._lastRows=[];return}if(a&&this._lastRows&&this._lastHeaders&&this._lastHeaders.length===e.length){this._diffRows(t),this._lastRows=t,this._lastHeaders=e;return}const s=e.length?`<thead><tr>${e.map(o=>`<th>${o}</th>`).join("")}</tr></thead>`:"",i=`<tbody>${t.map(o=>`<tr>${o.map(d=>`<td>${d}</td>`).join("")}</tr>`).join("")}</tbody>`;this.refs.wrap.innerHTML=`<table>${s}${i}</table>`,this._lastRows=t,this._lastHeaders=e,this._animate()}_diffRows(e){const t=this.refs.wrap.querySelector("tbody");if(!t)return;const r=new Map;Array.from(t.querySelectorAll("tr")).forEach(s=>{var o,d;const i=s.dataset.k||(s.dataset.k=((d=(o=s.cells[0])==null?void 0:o.textContent)==null?void 0:d.trim())||"");r.set(i,s)});const a=new Set;e.forEach(s=>{const i=(s[0]||"").replace(/<[^>]+>/g,"").trim();a.add(i);const o=s.map(h=>`<td>${h}</td>`).join(""),d=r.get(i);if(d){const h=s.map(n=>n.replace(/<[^>]+>/g,"").trim());Array.from(d.cells).forEach((n,p)=>{const u=n.textContent.trim(),b=h[p]??"";u!==b&&(n.innerHTML=s[p]??"",this._flashCell(n))})}else{const h=document.createElement("tr");h.innerHTML=o,h.style.opacity="0",t.appendChild(h),requestAnimationFrame(()=>{h.style.transition="opacity 300ms",h.style.opacity="1"})}}),r.forEach((s,i)=>{a.has(i)||s.remove()})}_flashCell(e){const t=e.style.backgroundColor;e.style.transition="background-color 300ms ease",e.style.backgroundColor="var(--teal-soft)",setTimeout(()=>{e.style.backgroundColor=t},300)}_animate(){const e=window.gsap;if(!e)return;const t=this.refs.wrap.querySelectorAll("tbody tr");t.length!==0&&e.fromTo(t,{autoAlpha:0,x:-8},{autoAlpha:1,x:0,duration:.24,ease:"power2.out",stagger:.018})}}customElements.define("keel-data-table",ee);class te extends y{hostStyles(){return"display:block;"}template(){return`
            <style>
                .hero {
                    position: relative;
                    display: grid;
                    gap: 0;
                    border: 2px solid var(--ink);
                    margin-bottom: 24px;
                    min-height: 156px;
                    background:
                        linear-gradient(180deg, rgba(20, 184, 166, 0.05), transparent 54%),
                        repeating-linear-gradient(90deg, transparent 0, transparent 12px, rgba(255,255,255,0.025) 12px, rgba(255,255,255,0.025) 13px),
                        repeating-linear-gradient(0deg, transparent 0, transparent 36px, rgba(255,255,255,0.025) 36px, rgba(255,255,255,0.025) 37px),
                        var(--surface-muted);
                    color: var(--ink);
                    overflow: hidden;
                }
                .hero::before {
                    content: "";
                    position: absolute;
                    inset: 0 0 auto;
                    height: 42px;
                    pointer-events: none;
                    background: linear-gradient(90deg, var(--surface-accent), rgba(0, 0, 0, 0.18));
                    border-bottom: 1px solid rgba(235, 231, 223, 0.16);
                }
                .hero-shell {
                    position: relative;
                    z-index: 1;
                    display: grid;
                    grid-template-rows: auto 1fr;
                    min-height: inherit;
                }
                .hero-strip {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    min-width: 0;
                    min-height: 42px;
                    padding: 11px 18px;
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.18em;
                    text-transform: uppercase;
                    color: var(--on-accent);
                }
                .hero-label {
                    min-width: 0;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                }
                .hero-coord { color: var(--teal); white-space: nowrap; opacity: 0.9; }
                .hero-main {
                    display: grid;
                    grid-template-columns: minmax(0, 1fr) auto;
                    gap: 24px;
                    align-items: end;
                    padding: 22px 18px 18px;
                }
                .hero-copy {
                    display: grid;
                    gap: 10px;
                    align-content: end;
                    min-width: 0;
                }
                .hero-caption {
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.14em;
                    text-transform: uppercase;
                    color: var(--muted);
                }
                .hero-title {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: clamp(34px, 4.8vw, 64px);
                    line-height: 0.88;
                    letter-spacing: -0.05em;
                    text-transform: uppercase;
                    color: var(--ink);
                    max-width: 12ch;
                    overflow-wrap: anywhere;
                }
                .hero-side {
                    display: flex;
                    align-items: flex-end;
                    justify-content: flex-end;
                    min-width: 0;
                    max-width: 320px;
                }
                .hero-side:empty {
                    display: none;
                }
                .hero-side :is(div, span, strong, code) {
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.1em;
                    text-transform: uppercase;
                }
                @media (max-width: 900px) {
                    .hero {
                        min-height: 0;
                    }
                    .hero-main {
                        grid-template-columns: 1fr;
                        align-items: start;
                    }
                    .hero-side {
                        justify-content: flex-start;
                        max-width: none;
                    }
                    .hero-title { font-size: clamp(34px, 11vw, 56px); max-width: none; }
                }
            </style>
            <section class="hero" data-ref="root">
                <div class="hero-shell">
                    <div class="hero-strip">
                        <span class="hero-label" data-ref="label"></span>
                        <samp class="hero-coord">REV/03 + GRID/24</samp>
                    </div>
                    <div class="hero-main">
                        <div class="hero-copy">
                            <span class="hero-caption">System control surface</span>
                            <h1 class="hero-title" data-ref="title"></h1>
                        </div>
                        <aside class="hero-side" data-ref="side"></aside>
                    </div>
                </div>
            </section>
        `}render({label:e="",title:t="",metaHtml:r=""}={}){this.refs.label.textContent=`[ ${e} ]`,this.refs.title.textContent=t,this.refs.side.innerHTML=r}}customElements.define("keel-hero",te);class ae extends y{hostStyles(){return"display:block;"}template(){return`
            <style>
                :host { display: block; }
                dl {
                    margin: 0;
                    display: grid;
                    grid-template-columns: minmax(140px, 0.4fr) minmax(0, 1fr);
                    gap: 0;
                }
                dt {
                    padding: 14px 18px;
                    font-size: 10px;
                    font-weight: 800;
                    text-transform: uppercase;
                    letter-spacing: 0.14em;
                    color: var(--muted);
                    border-bottom: 1px solid rgba(17, 24, 39, 0.04);
                }
                dd {
                    margin: 0;
                    padding: 14px 18px;
                    font-size: 13px;
                    color: var(--ink);
                    border-bottom: 1px solid rgba(17, 24, 39, 0.04);
                    word-break: break-word;
                }
            </style>
            <dl data-ref="dl"></dl>
        `}render({items:e=[]}={}){this.refs.dl.innerHTML=e.map(([t,r])=>`
            <dt>${this._escape(t)}</dt>
            <dd>${r}</dd>
        `).join("")}_escape(e){return String(e).replace(/[&<>"']/g,t=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"})[t])}}customElements.define("keel-detail-list",ae);class re extends y{hostStyles(){return"display:block;width:100%;"}template(){return`
            <style>
                .chart-container { width: 100%; position: relative; }
                /* ── Bar chart ─────────────────────────────────── */
                .bar-chart {
                    display: flex;
                    align-items: flex-end;
                    gap: 3px;
                    height: 120px;
                    padding: 0;
                }
                .bar-chart .bar-col {
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: flex-end;
                    height: 100%;
                    min-width: 0;
                }
                .bar-chart .bar {
                    width: 100%;
                    min-height: 2px;
                    background: var(--teal, #0d9488);
                    transition: height 400ms cubic-bezier(0.2, 0, 0, 1);
                    position: relative;
                }
                .bar-chart .bar:hover { opacity: 0.8; }
                .bar-chart .bar-label {
                    font-family: var(--font-mono, monospace);
                    font-size: 9px;
                    color: var(--muted, #6b6b66);
                    text-transform: uppercase;
                    letter-spacing: 0.04em;
                    margin-top: 6px;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    max-width: 100%;
                    text-align: center;
                }
                .bar-chart .bar-value {
                    font-family: var(--font-mono, monospace);
                    font-size: 9px;
                    font-weight: 700;
                    color: var(--ink, #0b0b0b);
                    margin-bottom: 3px;
                    white-space: nowrap;
                }

                /* ── Donut chart ───────────────────────────────── */
                .donut-wrap {
                    display: flex;
                    align-items: center;
                    gap: 20px;
                }
                .donut-svg { flex-shrink: 0; }
                .donut-legend {
                    display: flex;
                    flex-direction: column;
                    gap: 6px;
                    min-width: 0;
                }
                .donut-legend-item {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    font-family: var(--font-mono, monospace);
                    font-size: 11px;
                    color: var(--ink, #0b0b0b);
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }
                .donut-legend-dot {
                    width: 10px;
                    height: 10px;
                    flex-shrink: 0;
                    border: 1px solid var(--ink, #0b0b0b);
                }
                .donut-legend-value {
                    color: var(--muted, #6b6b66);
                    margin-left: auto;
                    font-weight: 700;
                }

                /* ── Sparkline ─────────────────────────────────── */
                .sparkline-svg {
                    width: 100%;
                    height: 60px;
                    display: block;
                }
                .sparkline-svg .line {
                    fill: none;
                    stroke: var(--teal, #0d9488);
                    stroke-width: 2;
                    stroke-linecap: round;
                    stroke-linejoin: round;
                }
                .sparkline-svg .area {
                    fill: var(--teal, #0d9488);
                    opacity: 0.1;
                }

                .chart-empty {
                    text-align: center;
                    padding: 24px;
                    font-family: var(--font-mono, monospace);
                    font-size: 11px;
                    font-weight: 700;
                    color: var(--muted, #6b6b66);
                    text-transform: uppercase;
                    letter-spacing: 0.08em;
                }
            </style>
            <div class="chart-container" data-ref="container"></div>
        `}render(e){const{type:t,data:r=[],labels:a=[],colors:s,height:i=120,emptyText:o}=e;if(!r.length||r.every(d=>!d)){this.refs.container.innerHTML=`<div class="chart-empty">${o||"No data yet"}</div>`;return}switch(t){case"bar":this._renderBar(r,a,s,i);break;case"donut":this._renderDonut(r,a,s);break;case"sparkline":this._renderSparkline(r);break}}_renderBar(e,t,r,a){const s=Math.max(1,...e),i=r||this._defaultColors(),o=e.map((d,h)=>{const n=Math.max(2,Math.round((a-20)*d/s)),p=i[h%i.length],u=t[h]||"";return`
                <div class="bar-col">
                    <span class="bar-value">${this._fmtNum(d)}</span>
                    <div class="bar" style="height:${n}px;background:${p};" title="${u}: ${d}"></div>
                    <span class="bar-label">${this._escHtml(u)}</span>
                </div>
            `}).join("");this.refs.container.innerHTML=`<div class="bar-chart" style="height:${a}px;">${o}</div>`}_renderDonut(e,t,r){const a=e.reduce((x,m)=>x+m,0)||1,s=r||this._defaultColors(),i=120,o=45,d=i/2,h=i/2,n=2*Math.PI*o;let p=0;const u=e.map((x,m)=>{const C=x/a*n,T=`${C} ${n-C}`,E=s[m%s.length],$=`<circle cx="${d}" cy="${h}" r="${o}" fill="none" stroke="${E}" stroke-width="14"
                stroke-dasharray="${T}" stroke-dashoffset="${-p}"
                transform="rotate(-90 ${d} ${h})" />`;return p+=C,$}).join(""),b=e.map((x,m)=>{const _=Math.round(100*x/a),C=s[m%s.length],T=t[m]||`Item ${m+1}`;return`
                <div class="donut-legend-item">
                    <span class="donut-legend-dot" style="background:${C};"></span>
                    <span>${this._escHtml(T)}</span>
                    <span class="donut-legend-value">${_}%</span>
                </div>
            `}).join("");this.refs.container.innerHTML=`
            <div class="donut-wrap">
                <svg class="donut-svg" width="${i}" height="${i}" viewBox="0 0 ${i} ${i}">
                    ${u}
                    <text x="${d}" y="${h}" text-anchor="middle" dominant-baseline="central"
                        font-family="var(--font-headline, sans-serif)" font-size="18" fill="var(--ink, #0b0b0b)"
                        font-weight="900">${this._fmtNum(a)}</text>
                </svg>
                <div class="donut-legend">${b}</div>
            </div>
        `}_renderSparkline(e){const t=Math.max(1,...e),r=Math.min(0,...e),a=t-r||1,s=300,i=60,o=4,h=e.map((p,u)=>{const b=u/(e.length-1||1)*s,x=i-o-(p-r)/a*(i-o*2);return`${b},${x}`}).join(" "),n=`0,${i} ${h} ${s},${i}`;this.refs.container.innerHTML=`
            <svg class="sparkline-svg" viewBox="0 0 ${s} ${i}" preserveAspectRatio="none">
                <polygon class="area" points="${n}" />
                <polyline class="line" points="${h}" />
            </svg>
        `}_defaultColors(){return["#0d9488","#6366f1","#f59e0b","#ef4444","#8b5cf6","#10b981","#f97316","#3b82f6"]}_fmtNum(e){return e>=1e6?(e/1e6).toFixed(1)+"M":e>=1e3?(e/1e3).toFixed(1)+"K":String(Math.round(e))}_escHtml(e){const t=document.createElement("div");return t.textContent=e,t.innerHTML}}customElements.define("keel-chart",re);class se extends y{hostStyles(){return"height:100%;"}template(){return`
            <style>
                .dashboard { display: flex; flex-direction: column; gap: 32px; }
                .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
                .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 24px; }
                .section-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 28px;
                    box-shadow: var(--shadow-sm);
                    border: 2px solid var(--ink);
                }
                .hero-meta {
                    display: grid;
                    justify-items: end;
                    gap: 8px;
                    padding: 4px 0;
                }
                .hero-chip {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    padding: 7px 10px;
                    border: 1px solid rgba(235, 231, 223, 0.18);
                    background: rgba(11, 11, 11, 0.18);
                    color: var(--on-accent);
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.08em;
                    text-transform: uppercase;
                }
                .section-title {
                    font-family: var(--font-headline);
                    font-size: 18px;
                    font-weight: 500;
                    margin: 0 0 16px;
                    color: var(--ink);
                    text-transform: uppercase;
                    letter-spacing: -0.02em;
                }
                .onboarding-banner {
                    background: var(--surface-muted);
                    border-radius: var(--radius-lg);
                    padding: 24px 28px;
                    line-height: 1.7;
                    font-size: 13px;
                    color: var(--ink);
                    border: 2px solid var(--ink);
                }
                .onboarding-banner h3 {
                    font-family: var(--font-headline);
                    font-size: 22px;
                    margin: 0 0 12px;
                }
                .onboarding-banner ol {
                    margin: 12px 0 0;
                    padding-left: 20px;
                }
                .onboarding-banner li { margin-bottom: 6px; }
                .onboarding-banner code {
                    background: var(--surface-accent);
                    color: var(--on-accent);
                    padding: 2px 6px;
                    border-radius: 0;
                    font-family: var(--font-mono);
                    font-size: 11px;
                }
                .empty { text-align: center; color: var(--muted); padding: 40px; font-size: 13px; }
                .chart-header {
                    display: flex; align-items: center; justify-content: space-between;
                    margin-bottom: 12px;
                }
                .chart-header .section-title { margin: 0; }
                .chart-hint {
                    font-family: var(--font-mono); font-size: 10px; font-weight: 700;
                    color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em;
                }
                @media (max-width: 900px) {
                    .grid-2 { grid-template-columns: 1fr; }
                    .grid-3 { grid-template-columns: 1fr; }
                }
            </style>
            <div class="dashboard" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="onboarding-banner">
                    <h3>Welcome to AI Proxy</h3>
                    <p>Your unified AI Gateway for routing, rate limiting, and cost tracking.</p>
                    <ol>
                        <li><strong>Create an API Key</strong> in the <em>API Keys</em> panel &mdash; you'll get a <code>sk-keel-*</code> virtual key</li>
                        <li><strong>Send requests</strong> via OpenAI Chat, OpenAI Responses, or Anthropic Messages protocol in <em>Playground</em></li>
                        <li><strong>Monitor costs</strong> here on this Overview, and check <em>Dashboard</em> for ops metrics</li>
                    </ol>
                </div>
                <keel-stat-grid data-ref="stats"></keel-stat-grid>
                <div class="grid-3">
                    <div class="section-card">
                        <div class="chart-header">
                            <h3 class="section-title">Request Volume</h3>
                            <span class="chart-hint">7 Days</span>
                        </div>
                        <keel-chart data-ref="volumeChart"></keel-chart>
                    </div>
                    <div class="section-card">
                        <div class="chart-header">
                            <h3 class="section-title">Model Split</h3>
                            <span class="chart-hint">By Requests</span>
                        </div>
                        <keel-chart data-ref="modelChart"></keel-chart>
                    </div>
                    <div class="section-card">
                        <div class="chart-header">
                            <h3 class="section-title">Latency</h3>
                            <span class="chart-hint">Recent P50</span>
                        </div>
                        <keel-chart data-ref="latencyChart"></keel-chart>
                    </div>
                </div>
                <div class="grid-2">
                    <div class="section-card">
                        <h3 class="section-title">Top Models</h3>
                        <keel-data-table data-ref="modelsTable"></keel-data-table>
                    </div>
                    <div class="section-card">
                        <h3 class="section-title">Top Users</h3>
                        <keel-data-table data-ref="usersTable"></keel-data-table>
                    </div>
                </div>
                <div class="section-card">
                    <h3 class="section-title">Recent Requests</h3>
                    <keel-data-table data-ref="recentTable"></keel-data-table>
                </div>
            </div>
        `}afterMount(){this._liveMode=!0,this._sse=null,this._pollTimer=null,this.refs.hero.render({label:"Telemetry Overview",title:"Overview",metaHtml:""})}connectedCallback(){super.connectedCallback(),setTimeout(()=>this._startLive(),500)}disconnectedCallback(){this._stopLive()}setLiveMode(e){this._liveMode=e,e?this._startLive():this._stopLive()}_startLive(){if(this._stopLive(),!!this._liveMode){this.refresh();try{const e=f.airelay||f.token;this._sse=new EventSource(`${e}/usage/stream`),this._sse.onmessage=t=>{var r;try{const a=JSON.parse(t.data);(!a._records||a._records.length===0)&&((r=this._lastDetailedRecords)!=null&&r.length)&&(a._records=this._lastDetailedRecords),this._render(a)}catch{}},this._sse.onerror=()=>{this._sse.close(),this._sse=null,this._startPolling()}}catch{this._startPolling()}}}_startPolling(){this._stopPolling(),this._pollTimer=setInterval(()=>this.refresh(),15e3)}_stopPolling(){this._pollTimer&&(clearInterval(this._pollTimer),this._pollTimer=null)}_stopLive(){this._sse&&(this._sse.close(),this._sse=null),this._stopPolling()}async refresh(){try{const e=await v(`${f.token}/admin/usage/records?limit=200`),t=await v(`${f.token}/admin/usage/global`);this._lastDetailedRecords=e.records||e,this._render({...t,_records:this._lastDetailedRecords})}catch(e){this.refs.stats.render({entries:[["Status","Error",e.message]]})}}_render(e){var n;const t=!!this._hasRendered;this._hasRendered=!0;const r=e._records||e.recentRequests||[];(n=e._records)!=null&&n.length&&(this._lastDetailedRecords=e._records);const a=this._aggregate(r);this.refs.stats.render({silent:t,entries:[["Total Requests",String(e.totalRequests||0),"all-time"],["Total Cost",`$${(e.totalCostUsd||0).toFixed(4)}`,"USD"],["Input",(a.inputTokens||0).toLocaleString(),"tokens"],["Output",(a.outputTokens||0).toLocaleString(),"tokens"],["Cache Read",(a.cacheReadInputTokens||0).toLocaleString(),"tokens"],["Cache Write",(a.cacheCreationInputTokens||0).toLocaleString(),"tokens"],["Reasoning",(a.reasoningTokens||0).toLocaleString(),"tokens"],["Cache Hit",a.cacheHitRate!=null?`${(a.cacheHitRate*100).toFixed(1)}%`:"—","rate"]]}),this.refs.hero.render({label:"Telemetry Overview",title:"Overview",metaHtml:`
                <div class="hero-meta">
                    <span class="hero-chip">${e.totalRequests||0} requests</span>
                    <span class="hero-chip">$${(e.totalCostUsd||0).toFixed(4)} total cost</span>
                </div>
            `});const s=e.recentRequests||r.slice(0,20),i=this._bucketByDay(r,7);this.refs.volumeChart.render({type:"bar",data:i.map(p=>p.count),labels:i.map(p=>p.label),height:100,emptyText:"No request data yet"});const o=e.topModels||[];this.refs.modelChart.render({type:"donut",data:o.slice(0,6).map(p=>p.requests),labels:o.slice(0,6).map(p=>p.model),emptyText:"No model data yet"});const d=s.slice(0,30).map(p=>p.latencyMs||0).reverse();this.refs.latencyChart.render({type:"sparkline",data:d,emptyText:"No latency data yet"}),this.refs.modelsTable.render({silent:t,headers:["Model","Requests","Tokens","Cost"],rows:o.map(p=>[`<code>${p.model}</code>`,String(p.requests),p.totalTokens.toLocaleString(),`$${p.totalCostUsd.toFixed(4)}`]),emptyHtml:'<div class="empty">No model data yet.</div>'});const h=e.topUsers||[];this.refs.usersTable.render({silent:t,headers:["User ID","Requests","Tokens","Cost"],rows:h.map(p=>[`<code>${p.userId}</code>`,String(p.requests),p.totalTokens.toLocaleString(),`$${p.totalCostUsd.toFixed(4)}`]),emptyHtml:'<div class="empty">No user data yet.</div>'}),this.refs.recentTable.render({silent:t,headers:["Time","Model","In","Out","CR","CW","Reason","In$","Out$","CW$","CR$","Total$","Hit","Latency"],rows:s.slice(0,20).map(p=>{const u=p.usage||{},b=p.cost||{};return[`<span style="font-size:11px;">${(p.createdAt||"").slice(0,19)}</span>`,`<code>${p.model||""}</code>`,this._num(u.promptTokens),this._num(u.completionTokens),this._num(u.cacheReadInputTokens),this._num(u.cacheCreationInputTokens),this._num(u.reasoningTokens),`$${(b.inputCostUsd||0).toFixed(5)}`,`$${(b.outputCostUsd||0).toFixed(5)}`,`$${(b.cacheWriteCostUsd||0).toFixed(5)}`,`$${(b.cacheReadCostUsd||0).toFixed(5)}`,`$${(b.totalCostUsd||0).toFixed(5)}`,b.cacheHitRate!=null?`${(b.cacheHitRate*100).toFixed(0)}%`:"—",`${p.latencyMs||0}ms`]}),emptyHtml:'<div class="empty">No requests recorded yet. Create an API key and send a request in Playground.</div>'})}_num(e){return e==null?"0":e>=1e3?`${(e/1e3).toFixed(1)}K`:String(e)}_aggregate(e){return e.reduce((t,r)=>{var s;const a=r.usage||{};return t.inputTokens+=a.promptTokens||0,t.outputTokens+=a.completionTokens||0,t.cacheReadInputTokens+=a.cacheReadInputTokens||0,t.cacheCreationInputTokens+=a.cacheCreationInputTokens||0,t.reasoningTokens+=a.reasoningTokens||0,((s=r.cost)==null?void 0:s.cacheHitRate)!=null&&(t._hitSamples+=1,t._hitSum+=r.cost.cacheHitRate),t},{inputTokens:0,outputTokens:0,cacheReadInputTokens:0,cacheCreationInputTokens:0,reasoningTokens:0,_hitSamples:0,_hitSum:0,get cacheHitRate(){return this._hitSamples>0?this._hitSum/this._hitSamples:null}})}_bucketByDay(e,t){const r=new Date,a=[];for(let s=t-1;s>=0;s--){const i=new Date(r);i.setDate(i.getDate()-s);const o=i.toISOString().slice(0,10),d=i.toLocaleDateString("en",{weekday:"short"}),h=e.filter(n=>(n.createdAt||"").startsWith(o)).length;a.push({key:o,label:d,count:h})}return a}}customElements.define("ai-panel-overview",se);function l(c){return String(c).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function L(c){const e=Number(c||0);return new Intl.NumberFormat("en-US").format(e)}function R(c){if(!c)return"—";const e=new Date(c);return Number.isNaN(e.getTime())?String(c):e.toLocaleString(void 0,{month:"short",day:"2-digit",hour:"2-digit",minute:"2-digit"})}async function ie(c){if(!c)return!1;try{return await navigator.clipboard.writeText(c),!0}catch{try{const e=document.createElement("textarea");e.value=c,e.style.cssText="position:fixed;left:-9999px;top:-9999px;opacity:0;",document.body.appendChild(e),e.focus(),e.select();const t=document.execCommand("copy");return document.body.removeChild(e),t}catch{return!1}}}class oe extends y{hostStyles(){return"height:100%;"}template(){return`
            <style>
                .ops { display: flex; flex-direction: column; gap: 24px; }
                .toolbar { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
                .toolbar-label { font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: .14em; text-transform: uppercase; color: var(--muted); }
                .window-btn { padding: 8px 16px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); cursor: pointer; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
                .window-btn.active { background: var(--surface-accent); color: var(--on-accent); box-shadow: 3px 3px 0 var(--teal); }
                .auto-refresh { margin-left:auto; display:inline-flex; align-items:center; gap:8px; font-family:var(--font-mono); font-size:10px; font-weight:800; color:var(--muted); text-transform:uppercase; }
                .section-card { background: var(--panel-strong); border-radius: var(--radius-lg); padding: 24px; box-shadow: var(--shadow-sm); border: 2px solid var(--ink); min-width: 0; }
                .section-title { font-family: var(--font-headline); font-size: 16px; font-weight: 500; margin: 0 0 16px; color: var(--ink); text-transform: uppercase; letter-spacing: -0.02em; }
                .chart-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:12px; }
                .chart-header .section-title { margin:0; }
                .chart-hint { font-family: var(--font-mono); font-size: 10px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em; }
                .auto-grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 20px; }
                .health-grid { display:grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
                .health-tile { padding:16px; border:2px solid var(--ink); background:var(--paper); }
                .health-tile.ok { background: var(--green-soft); }
                .health-tile.warn { background: var(--amber-soft); }
                .health-tile.danger { background: var(--red-soft); }
                .health-label { font-family:var(--font-mono); font-size:10px; font-weight:800; color:var(--muted); text-transform:uppercase; letter-spacing:.1em; }
                .health-value { margin-top:6px; font-family:var(--font-headline); font-size:30px; line-height:1; }
                .empty { text-align:center; color:var(--muted); padding:32px; font-size:13px; }
            </style>
            <div class="ops" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>

                <div class="toolbar">
                    <span class="toolbar-label">Window</span>
                    ${["1h","24h","7d","30d"].map(e=>`<button class="window-btn" data-window="${e}">${e}</button>`).join("")}
                    <label class="auto-refresh"><input type="checkbox" data-ref="autoRefresh"> Auto refresh 30s</label>
                </div>

                <keel-stat-grid data-ref="overviewStats"></keel-stat-grid>
                <keel-stat-grid data-ref="latencyStats"></keel-stat-grid>

                <div class="auto-grid">
                    <div class="section-card"><div class="chart-header"><h3 class="section-title">Requests</h3><span class="chart-hint" data-ref="reqHint"></span></div><keel-chart data-ref="requestChart"></keel-chart></div>
                    <div class="section-card"><div class="chart-header"><h3 class="section-title">Tokens</h3><span class="chart-hint">in + out + cache</span></div><keel-chart data-ref="tokenChart"></keel-chart></div>
                    <div class="section-card"><div class="chart-header"><h3 class="section-title">Latency P95</h3><span class="chart-hint">trend</span></div><keel-chart data-ref="latencyChart"></keel-chart></div>
                </div>

                <div class="auto-grid">
                    <div class="section-card"><h3 class="section-title">Model Distribution</h3><keel-chart data-ref="modelChart"></keel-chart></div>
                    <div class="section-card"><h3 class="section-title">Channel Distribution</h3><keel-chart data-ref="channelChart"></keel-chart></div>
                    <div class="section-card"><h3 class="section-title">Group Distribution</h3><keel-chart data-ref="groupChart"></keel-chart></div>
                    <div class="section-card"><h3 class="section-title">Error Distribution</h3><keel-chart data-ref="errorChart"></keel-chart></div>
                </div>

                <div class="auto-grid">
                    <div class="section-card"><h3 class="section-title">Channel Health</h3><div class="health-grid" data-ref="healthGrid"></div></div>
                    <div class="section-card"><h3 class="section-title">Recent Request Stream</h3><keel-data-table data-ref="recentTable"></keel-data-table></div>
                </div>
            </div>
        `}afterMount(){this._window="24h",this._pollTimer=null,this._hasRendered=!1,this.refs.hero.render({label:"Operations",title:"Dashboard",metaHtml:""}),this.refs.root.addEventListener("click",e=>{const t=e.target.closest("[data-window]");t&&(this._window=t.dataset.window,this._paintWindowButtons(),this.refresh())}),this.refs.autoRefresh.addEventListener("change",()=>this._configurePolling()),this._paintWindowButtons()}disconnectedCallback(){var e;(e=super.disconnectedCallback)==null||e.call(this),this._stopPolling()}_configurePolling(){this._stopPolling(),this.refs.autoRefresh.checked&&(this._pollTimer=setInterval(()=>this.refresh(),3e4))}_stopPolling(){this._pollTimer&&clearInterval(this._pollTimer),this._pollTimer=null}_paintWindowButtons(){this.shadowRoot.querySelectorAll("[data-window]").forEach(e=>{e.classList.toggle("active",e.dataset.window===this._window)})}async refresh(){try{const[e,t]=await Promise.all([v(`${f.airelay}/admin/stats/dashboard?window=${encodeURIComponent(this._window)}`),v(`${f.token}/admin/usage/records?limit=200`).catch(()=>({records:[]}))]);this._render(e||{},t.records||[])}catch(e){this.refs.overviewStats.render({entries:[["Status","Error",e.message]]})}}_render(e,t){const r=this._hasRendered;this._hasRendered=!0;const a=e.overview||{},s=e.distributions||{},i=e.trends||{};this.refs.hero.render({label:"Operations",title:"Dashboard",metaHtml:`<div style="display:grid;justify-items:end;gap:8px;"><span style="display:inline-flex;padding:7px 10px;border:1px solid rgba(235,231,223,0.18);background:rgba(11,11,11,0.18);color:var(--on-accent);font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;">${(a.totalRequests||0).toLocaleString()} requests · ${this._window}</span></div>`}),this.refs.overviewStats.render({silent:r,entries:[["Requests",(a.totalRequests||0).toLocaleString(),this._window],["Success",a.successRate!=null?`${(a.successRate*100).toFixed(1)}%`:"—","rate"],["Cost",`$${(a.totalCostUsd||0).toFixed(4)}`,"USD"],["Tokens",this._fmt(a.totalTokens||0),"total"],["Cache Hit",a.cacheHitRate!=null?`${(a.cacheHitRate*100).toFixed(1)}%`:"—","rate"]]}),this.refs.latencyStats.render({silent:r,entries:[["Avg Latency",`${a.avgLatencyMs||0}ms`,"mean"],["P50",`${a.p50LatencyMs||0}ms`,"latency"],["P95",`${a.p95LatencyMs||0}ms`,"latency"],["P99",`${a.p99LatencyMs||0}ms`,"latency"]]}),this.refs.healthGrid.innerHTML=[this._healthTile("Healthy",a.healthyChannels||0,"ok"),this._healthTile("Cooldown",a.cooldownChannels||0,"warn"),this._healthTile("Disabled",a.disabledChannels||0,"danger")].join("");const o=i.requestsByHour||[];this.refs.reqHint.textContent=this._window,this.refs.requestChart.render({type:"bar",data:o.map(n=>n.requests),labels:o.map(n=>this._bucketLabel(n.timestamp)),height:110,emptyText:"No request data yet"});const d=i.tokensByHour||[];this.refs.tokenChart.render({type:"bar",data:d.map(n=>(n.promptTokens||0)+(n.completionTokens||0)+(n.cacheWriteTokens||0)+(n.cacheReadTokens||0)),labels:d.map(n=>this._bucketLabel(n.timestamp)),height:110,emptyText:"No token data yet"});const h=i.latencyByHour||[];this.refs.latencyChart.render({type:"sparkline",data:h.map(n=>n.p95||0),emptyText:"No latency data yet"}),this.refs.modelChart.render({type:"donut",data:(s.modelDistribution||[]).map(n=>n.requests),labels:(s.modelDistribution||[]).map(n=>n.model),emptyText:"No model data yet"}),this.refs.channelChart.render({type:"bar",data:(s.channelDistribution||[]).map(n=>n.requests),labels:(s.channelDistribution||[]).map(n=>n.channelName||n.channelId),height:110,emptyText:"No channel data yet"}),this.refs.groupChart.render({type:"bar",data:(s.groupDistribution||[]).map(n=>n.requests),labels:(s.groupDistribution||[]).map(n=>n.groupName||n.groupId),height:110,emptyText:"No group data yet"}),this.refs.errorChart.render({type:"donut",data:(s.errorDistribution||[]).map(n=>n.count),labels:(s.errorDistribution||[]).map(n=>n.errorType),emptyText:"No errors in this window"}),this.refs.recentTable.render({silent:r,headers:["Time","Model","Channel","Status","Latency","Tokens","Cost"],rows:t.slice(0,20).map(n=>{const p=n.usage||{},u=(p.promptTokens||0)+(p.completionTokens||0)+(p.cacheReadInputTokens||0)+(p.cacheCreationInputTokens||0);return[`<span style="font-size:11px;">${l((n.createdAt||"").replace("T"," ").slice(0,19))}</span>`,`<code>${l(n.model||"")}</code>`,`<code>${l(n.channelName||n.channelId||n.upstreamKeyId||"—")}</code>`,n.status>=400?`<span style="color:var(--red);font-weight:800;">${n.status}</span>`:`<span style="color:var(--green);font-weight:800;">${n.status||200}</span>`,`${n.latencyMs||0}ms`,this._fmt(u),`$${((n.cost||{}).totalCostUsd||n.totalCostUsd||0).toFixed(4)}`]}),emptyHtml:'<div class="empty">No requests recorded yet.</div>'})}_healthTile(e,t,r){return`<div class="health-tile ${r}"><div class="health-label">${e}</div><div class="health-value">${t}</div></div>`}_bucketLabel(e){const t=new Date(e);return this._window==="1h"||this._window==="24h"?t.toLocaleTimeString("en",{hour:"2-digit",minute:"2-digit"}):t.toLocaleDateString("en",{month:"short",day:"numeric"})}_fmt(e){const t=Number(e||0);return t>=1e6?`${(t/1e6).toFixed(1)}M`:t>=1e3?`${(t/1e3).toFixed(1)}K`:t.toLocaleString()}}customElements.define("ai-panel-dashboard",oe);class ne extends y{hostStyles(){return"height:100%;"}template(){return`
            <style>
                .layout { display: flex; flex-direction: column; gap: 24px; height: 100%; }
                .hero-meta { display: grid; justify-items: end; gap: 8px; padding: 4px 0; }
                .hero-chip {
                    display: inline-flex; align-items: center; padding: 7px 10px;
                    border: 1px solid rgba(235, 231, 223, 0.18); background: rgba(11, 11, 11, 0.18);
                    color: var(--on-accent); font-family: var(--font-mono); font-size: 10px; font-weight: 800;
                    letter-spacing: 0.08em; text-transform: uppercase;
                }
                .toolbar { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
                .toolbar .field { display: flex; flex-direction: column; gap: 4px; }
                .toolbar label {
                    font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted);
                }
                .toolbar select {
                    padding: 8px 10px; border: 2px solid var(--ink);
                    background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 11px; min-width: 180px;
                }
                .toolbar select:focus { outline: none; box-shadow: var(--shadow-sm); }
                .clear-btn {
                    padding: 8px 14px; border: 2px solid var(--red); background: transparent; color: var(--red);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: .08em;
                    text-transform: uppercase; cursor: pointer;
                }
                .summary {
                    display: grid; grid-template-columns: repeat(7, 1fr); gap: 2px;
                    background: var(--surface-accent); border: 2px solid var(--ink);
                }
                .summary .cell { background: var(--paper); padding: 14px 12px; font-family: var(--font-mono); }
                .summary .label { font-size: 9px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--muted); }
                .summary .val { margin-top: 4px; font-family: var(--font-headline); font-size: 18px; line-height: 1; letter-spacing: -0.03em; font-feature-settings: 'tnum'; }

                .table-card { background: var(--panel-strong); border: 2px solid var(--ink); flex: 1; min-height: 0; overflow: hidden; }
                .table-scroll { overflow: auto; max-height: 70vh; }
                table.usage { border-collapse: collapse; width: max-content; min-width: 100%; font-family: var(--font-mono); font-size: 11px; }
                table.usage thead th {
                    position: sticky; top: 0; z-index: 1; text-align: left; white-space: nowrap;
                    font-size: 10px; font-weight: 800; letter-spacing: .14em; text-transform: uppercase;
                    color: var(--on-accent); background: var(--surface-accent); padding: 11px 12px;
                    border-right: 1px solid rgba(244,244,240,0.28);
                }
                table.usage tbody td {
                    padding: 10px 12px; white-space: nowrap; border-right: 1px solid var(--ink);
                    border-bottom: 1px solid var(--ink); color: var(--ink); vertical-align: middle;
                }
                table.usage tbody td:last-child, table.usage thead th:last-child { border-right: 0; }
                table.usage tbody tr:nth-child(even) td { background: var(--color-surface-container-low, #ebe9e3); }
                table.usage code {
                    font-family: var(--font-mono); font-size: 10.5px; font-weight: 800; letter-spacing: .04em;
                    background: var(--surface-accent); color: var(--on-accent); border: 1px solid var(--surface-accent); padding: 2px 6px;
                }
                .detail-btn { border:1px solid var(--teal); color:var(--teal); background:transparent; padding:6px 10px; font-family:var(--font-mono); font-size:10px; font-weight:800; cursor:pointer; text-transform:uppercase; }
                .drawer-backdrop { position:fixed; inset:0; background:rgba(15,23,42,.34); z-index:950; display:none; justify-content:flex-end; }
                .drawer-backdrop.open { display:flex; }
                .drawer { width:min(560px, 96vw); height:100%; overflow:auto; background:var(--panel-strong); border-left:2px solid var(--ink); box-shadow:-18px 0 40px rgba(15,23,42,.2); padding:26px; }
                .drawer-head { display:flex; justify-content:space-between; align-items:start; gap:16px; margin-bottom:20px; }
                .drawer h3 { font-family:var(--font-headline); font-size:26px; margin:0; }
                .drawer section { margin:20px 0; display:grid; gap:8px; }
                .drawer section h4 { margin:0 0 4px; font-family:var(--font-mono); font-size:11px; letter-spacing:.12em; text-transform:uppercase; color:var(--muted); }
                .kv { display:grid; grid-template-columns:140px 1fr; gap:10px; font-family:var(--font-mono); font-size:12px; }
                .kv .k { color:var(--muted); text-transform:uppercase; font-weight:800; font-size:10px; }
                .kv .v { word-break:break-word; }
                .empty { text-align: center; color: var(--muted); padding: 30px; font-family: var(--font-mono); font-size: 12px; }
                @media (max-width: 1100px) { .summary { grid-template-columns: repeat(3, 1fr); } }
            </style>
            <div class="layout" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="toolbar">
                    <div class="field">
                        <label>Group</label>
                        <select data-ref="groupFilter"><option value="">All groups</option></select>
                    </div>
                    <div class="field">
                        <label>Channel</label>
                        <select data-ref="channelFilter"><option value="">All channels</option></select>
                    </div>
                    <div class="field">
                        <label>Model</label>
                        <select data-ref="modelFilter"><option value="">All models</option></select>
                    </div>
                    <div class="field">
                        <label>Status</label>
                        <select data-ref="statusFilter">
                            <option value="">All status</option>
                            <option value="success">Success</option>
                            <option value="error">Error</option>
                        </select>
                    </div>
                    <div class="field">
                        <label>Limit</label>
                        <select data-ref="limitFilter">
                            <option>50</option>
                            <option selected>200</option>
                        </select>
                    </div>
                    <button class="clear-btn" data-ref="clearBtn">Clear</button>
                </div>
                <div class="summary" data-ref="summary"></div>
                <div class="table-card">
                    <div class="table-scroll" data-ref="tableWrap"></div>
                </div>
                <div class="drawer-backdrop" data-ref="drawerBackdrop">
                    <aside class="drawer" data-ref="drawer"></aside>
                </div>
            </div>
        `}afterMount(){this._records=[],this._channelNameById={},this._hasRendered=!1,this._optionsLoaded=!1,this.refs.hero.render({label:"Request Ledger",title:"Usage",metaHtml:""}),this.refs.groupFilter.addEventListener("change",()=>this.refresh()),this.refs.channelFilter.addEventListener("change",()=>this.refresh()),this.refs.modelFilter.addEventListener("change",()=>this.refresh()),this.refs.statusFilter.addEventListener("change",()=>this.refresh()),this.refs.limitFilter.addEventListener("change",()=>this.refresh()),this.refs.clearBtn.addEventListener("click",()=>{this.refs.groupFilter.value="",this.refs.channelFilter.value="",this.refs.modelFilter.value="",this.refs.statusFilter.value="",this.refresh()}),this.refs.tableWrap.addEventListener("click",e=>{const t=e.target.closest("[data-detail]");if(!t)return;const r=this._records.find(a=>(a.requestId||a.recordId)===t.dataset.detail);r&&this._showDetail(r)}),this.refs.drawerBackdrop.addEventListener("click",e=>{e.target===this.refs.drawerBackdrop&&this._closeDetail()})}async _loadFilterOptions(){if(!this._optionsLoaded)try{const[e,t]=await Promise.all([v(`${f.airelay}/admin/groups`).catch(()=>({groups:[]})),v(`${f.airelay}/admin/channels`).catch(()=>({channels:[]}))]),r=e.groups||[],a=t.channels||[];this._channelNameById={},a.forEach(i=>{i.channelId&&(this._channelNameById[i.channelId]=i.name||i.channelId)});const s=Array.from(new Set(a.flatMap(i=>(i.models||[]).map(o=>o.publicModelName)).filter(Boolean))).sort();this._fillSelect(this.refs.groupFilter,r.map(i=>[i.groupId,i.name||i.groupId])),this._fillSelect(this.refs.channelFilter,a.map(i=>[i.channelId,i.name||i.channelId])),this._fillSelect(this.refs.modelFilter,s.map(i=>[i,i])),this._optionsLoaded=!0}catch{}}_fillSelect(e,t){const r=e.value,a=e.querySelector("option");e.innerHTML="",a&&e.appendChild(a),t.forEach(([s,i])=>{const o=document.createElement("option");o.value=s,o.textContent=i,e.appendChild(o)}),r&&(e.value=r)}async refresh(){await this._loadFilterOptions();try{const e=parseInt(this.refs.limitFilter.value)||200,t=new URLSearchParams({limit:String(e)}),r=this.refs.groupFilter.value,a=this.refs.channelFilter.value,s=this.refs.modelFilter.value,i=this.refs.statusFilter.value;r&&t.set("groupId",r),a&&t.set("channelId",a),s&&t.set("model",s),i&&t.set("statusFilter",i);const o=await v(`${f.token}/admin/usage/records?${t}`);this._records=o.records||[],this._renderTable(this._hasRendered),this._hasRendered=!0}catch(e){this.refs.tableWrap.innerHTML=`<div class="empty">Error: ${l(e.message)}</div>`}}_channelLabel(e){if(e.channelName)return e.channelName;const t=e.channelId||e.upstreamKeyId;return t?this._channelNameById[t]||t:"—"}_renderTable(e){const t=this._records,r=t.reduce((o,d)=>{const h=d.usage||{},n=d.cost||{};return o.input+=h.promptTokens||0,o.output+=h.completionTokens||0,o.cr+=h.cacheReadInputTokens||0,o.cw+=h.cacheCreationInputTokens||0,o.reason+=h.reasoningTokens||0,o.cost+=n.totalCostUsd||0,n.cacheHitRate!=null&&(o.hitN+=1,o.hitSum+=n.cacheHitRate),o},{input:0,output:0,cr:0,cw:0,reason:0,cost:0,hitN:0,hitSum:0}),a=r.hitN>0?(r.hitSum/r.hitN*100).toFixed(1)+"%":"—";if(this.refs.hero.render({label:"Request Ledger",title:"Usage",metaHtml:`<div class="hero-meta"><span class="hero-chip">${t.length} records</span><span class="hero-chip">${a} cache hit</span></div>`}),this.refs.summary.innerHTML=[this._sumCell("Records",t.length.toString()),this._sumCell("Input",r.input.toLocaleString()),this._sumCell("Output",r.output.toLocaleString()),this._sumCell("Cache Read",r.cr.toLocaleString()),this._sumCell("Cache Write",r.cw.toLocaleString()),this._sumCell("Reasoning",r.reason.toLocaleString()),this._sumCell("Total Cost","$"+r.cost.toFixed(4))].join(""),t.length===0){this.refs.tableWrap.innerHTML='<div class="empty">// NO RECORDS MATCH FILTERS</div>';return}const s=["Model","Time","Group","Channel","In","Out","CR","CW","CP","Reason","In$","Out$","CW$","CR$","Total$","Hit","Status","Latency","Detail"],i=t.slice(0,200).map(o=>{const d=o.usage||{},h=o.cost||{},n=o.requestId||o.recordId||"";return`<tr>
                <td>${this._modelCell(o.model)}</td>
                <td>${l((o.createdAt||"").replace("T"," ").slice(0,19))}</td>
                <td><code>${l(o.groupId||o.poolLevelId||"—")}</code></td>
                <td><code>${l(this._channelLabel(o))}</code></td>
                <td>${this._fmt(d.promptTokens)}</td>
                <td>${this._fmt(d.completionTokens)}</td>
                <td>${this._fmt(d.cacheReadInputTokens)}</td>
                <td>${this._fmt(d.cacheCreationInputTokens)}</td>
                <td>${this._fmt(d.cachedPromptTokens)}</td>
                <td>${this._fmt(d.reasoningTokens)}</td>
                <td>$${(h.inputCostUsd||0).toFixed(5)}</td>
                <td>$${(h.outputCostUsd||0).toFixed(5)}</td>
                <td>$${(h.cacheWriteCostUsd||0).toFixed(5)}</td>
                <td>$${(h.cacheReadCostUsd||0).toFixed(5)}</td>
                <td>$${(h.totalCostUsd||0).toFixed(5)}</td>
                <td>${h.cacheHitRate!=null?`${(h.cacheHitRate*100).toFixed(0)}%`:"—"}</td>
                <td>${o.status>=400?`<span style="color:var(--red);font-weight:800;">${o.status}</span>`:`<span style="color:var(--green);font-weight:800;">${o.status||200}</span>`}</td>
                <td>${o.latencyMs||0}ms</td>
                <td><button class="detail-btn" data-detail="${l(n)}">View</button></td>
            </tr>`}).join("");this.refs.tableWrap.innerHTML=`<table class="usage"><thead><tr>${s.map(o=>`<th>${o}</th>`).join("")}</tr></thead><tbody>${i}</tbody></table>`}_showDetail(e){const t=e.usage||{},r=e.cost||{},a=e.cacheHitRate!=null?e.cacheHitRate:r.cacheHitRate,s=(i,o)=>`<div class="kv"><span class="k">${i}</span><span class="v">${o==null||o===""?"—":l(String(o))}</span></div>`;this.refs.drawer.innerHTML=`
            <div class="drawer-head">
                <div>
                    <h3>Request detail</h3>
                    <div style="color:var(--muted);font-family:var(--font-mono);font-size:11px;">Full token, cost, routing and error context.</div>
                </div>
                <button class="detail-btn" data-ref="drawerClose">Close</button>
            </div>
            <section>
                <h4>Basic</h4>
                ${s("Request ID",e.requestId||e.recordId)}
                ${s("Timestamp",e.createdAt)}
                ${s("Model",e.model)}
                ${s("Group",e.groupId||e.poolLevelId)}
                ${s("Channel",this._channelLabel(e))}
                ${s("Status",`${e.status??"—"} (${e.outcome||"—"})`)}
            </section>
            <section>
                <h4>Tokens</h4>
                ${s("Input",(t.promptTokens||0).toLocaleString())}
                ${s("Output",(t.completionTokens||0).toLocaleString())}
                ${s("Cache Write",(t.cacheCreationInputTokens||0).toLocaleString())}
                ${s("Cache Read",(t.cacheReadInputTokens||0).toLocaleString())}
                ${s("Cache Hit Rate",a!=null?`${(a*100).toFixed(1)}%`:"—")}
            </section>
            <section>
                <h4>Cost</h4>
                ${s("Input Cost",`$${(r.inputCostUsd||0).toFixed(5)}`)}
                ${s("Output Cost",`$${(r.outputCostUsd||0).toFixed(5)}`)}
                ${s("Cache Write Cost",`$${(r.cacheWriteCostUsd||0).toFixed(5)}`)}
                ${s("Cache Read Cost",`$${(r.cacheReadCostUsd||0).toFixed(5)}`)}
                ${s("Total Cost",`$${(r.totalCostUsd||e.totalCostUsd||0).toFixed(5)}`)}
            </section>
            <section>
                <h4>Performance & Errors</h4>
                ${s("Latency",`${e.latencyMs||0}ms`)}
                ${s("Failover",e.failoverCount??0)}
                ${s("Streamed",e.streamed?"yes":"no")}
                ${s("Error Code",e.errorCode)}
                ${s("Error Detail",e.errorDetail)}
            </section>
        `,this.refs.drawer.querySelector('[data-ref="drawerClose"]').addEventListener("click",()=>this._closeDetail()),this.refs.drawerBackdrop.classList.add("open")}_closeDetail(){this.refs.drawerBackdrop.classList.remove("open")}_sumCell(e,t){return`<div class="cell"><div class="label">${e}</div><div class="val">${t}</div></div>`}_fmt(e){return e==null?"0":e>=1e3?`${(e/1e3).toFixed(1)}K`:String(e)}_modelCell(e){const t=String(e||""),r=t.split(" -> ");if(r.length<2)return`<code>${l(t)}</code>`;const a=r.shift(),s=r.join(" -> ");return`
            <span style="display:inline-flex;align-items:center;gap:6px;white-space:nowrap;">
                <code>${l(a)}</code>
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.65" aria-hidden="true" style="display:inline-block;vertical-align:middle;width:12px;height:12px;min-width:12px;color:var(--muted);max-width:none;">
                    <path d="M2 8h9"></path>
                    <path d="m8 4 4 4-4 4"></path>
                </svg>
                <code>${l(s)}</code>
            </span>
        `}}customElements.define("ai-panel-usage",ne);class le extends y{hostStyles(){return"height:100%;"}template(){return`
            <style>
                .wrap { display:flex; flex-direction:column; gap:24px; }
                .grid { display:grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap:20px; }
                .card {
                    background: var(--panel-strong);
                    border: 2px solid var(--ink);
                    border-radius: var(--radius-lg);
                    box-shadow: var(--shadow-sm);
                    display:flex;
                    flex-direction:column;
                    transition: box-shadow 180ms ease, transform 180ms ease;
                }
                .card:hover { box-shadow: var(--shadow-md); transform: translateY(-2px); }
                .card-head {
                    display:flex;
                    align-items:flex-start;
                    gap:14px;
                    padding:20px 22px 18px;
                    border-bottom:2px solid var(--ink);
                }
                .signal {
                    width:44px;
                    height:44px;
                    border:2px solid var(--ink);
                    background:var(--paper);
                    color:var(--ink);
                    display:flex;
                    align-items:center;
                    justify-content:center;
                    flex-shrink:0;
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:800;
                    letter-spacing:.08em;
                    text-transform:uppercase;
                }
                .signal.ok { background:var(--green-soft); color:var(--green); }
                .signal.err { background:var(--red-soft); color:var(--red); }
                .head-main { flex:1; min-width:0; }
                .name {
                    font-family:var(--font-headline);
                    font-size:20px;
                    font-weight:500;
                    line-height:.95;
                    letter-spacing:-.04em;
                    color:var(--ink);
                    margin:0;
                    overflow:hidden;
                    text-overflow:ellipsis;
                    white-space:nowrap;
                    text-transform:uppercase;
                }
                .sub { margin-top:10px; display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
                .proto-tag {
                    border:1px solid var(--teal);
                    color:var(--teal);
                    background:transparent;
                    padding:3px 7px;
                    font-family:var(--font-mono);
                    font-size:9px;
                    font-weight:800;
                    letter-spacing:.09em;
                    text-transform:uppercase;
                }
                .model-name {
                    font-family:var(--font-mono);
                    font-size:11px;
                    font-weight:700;
                    color:var(--muted);
                    overflow:hidden;
                    text-overflow:ellipsis;
                    white-space:nowrap;
                    text-transform:uppercase;
                }
                .status-badge {
                    border:2px solid currentColor;
                    padding:5px 10px;
                    flex-shrink:0;
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:800;
                    letter-spacing:.08em;
                    text-transform:uppercase;
                }
                .status-badge.ok { color:var(--green); background:var(--green-soft); }
                .status-badge.err { color:var(--red); background:var(--red-soft); }
                .card-body { display:grid; gap:16px; padding:18px 22px 22px; }
                .metrics { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
                .metric {
                    background:var(--paper);
                    border:2px solid var(--ink);
                    padding:14px 14px 13px;
                }
                .metric-label {
                    display:flex;
                    align-items:center;
                    gap:7px;
                    font-family:var(--font-mono);
                    font-size:9px;
                    font-weight:800;
                    color:var(--muted);
                    letter-spacing:.12em;
                    text-transform:uppercase;
                }
                .metric-label::before { content: ">"; color:var(--teal); }
                .metric-value {
                    margin-top:9px;
                    font-family:var(--font-headline);
                    font-size:30px;
                    font-weight:500;
                    line-height:.9;
                    letter-spacing:-.04em;
                    color:var(--ink);
                    font-feature-settings:'tnum';
                }
                .metric-value .unit {
                    font-family:var(--font-mono);
                    font-size:11px;
                    font-weight:800;
                    color:var(--muted);
                    margin-left:4px;
                    letter-spacing:.03em;
                }
                .avail-box {
                    display:grid;
                    grid-template-columns:minmax(0, 1fr) auto;
                    align-items:end;
                    gap:16px;
                    background:var(--paper);
                    border:2px solid var(--ink);
                    padding:16px;
                }
                .avail-label {
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:800;
                    color:var(--muted);
                    letter-spacing:.14em;
                    text-transform:uppercase;
                }
                .avail-extra {
                    margin-top:8px;
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:700;
                    color:var(--muted);
                    letter-spacing:.05em;
                    text-transform:uppercase;
                }
                .avail-value {
                    font-family:var(--font-headline);
                    font-size:clamp(36px, 5vw, 52px);
                    font-weight:500;
                    line-height:.82;
                    letter-spacing:-.06em;
                    font-feature-settings:'tnum';
                }
                .avail-value .pct {
                    font-family:var(--font-mono);
                    font-size:16px;
                    font-weight:800;
                    margin-left:4px;
                    letter-spacing:.02em;
                }
                .spark-card {
                    background:var(--surface-muted);
                    border:2px solid var(--ink);
                    padding:14px;
                }
                .spark-head {
                    display:flex;
                    align-items:center;
                    justify-content:space-between;
                    gap:12px;
                    font-family:var(--font-mono);
                    font-size:10px;
                    font-weight:800;
                    color:var(--muted);
                    margin-bottom:10px;
                    letter-spacing:.1em;
                    text-transform:uppercase;
                }
                .spark { display:flex; align-items:flex-end; gap:2px; height:30px; }
                .spark .bar {
                    flex:1;
                    min-width:2px;
                    max-width:7px;
                    height:22px;
                    border:1px solid transparent;
                }
                .spark .bar.ok { background:var(--green); }
                .spark .bar.fail { background:var(--red); }
                .spark .bar.idle {
                    background:var(--surface-strong);
                    border-color:var(--line);
                    height:7px;
                    align-self:center;
                }
                .spark-foot {
                    display:flex;
                    justify-content:space-between;
                    font-family:var(--font-mono);
                    font-size:9px;
                    font-weight:800;
                    color:var(--muted);
                    margin-top:8px;
                    text-transform:uppercase;
                    letter-spacing:.16em;
                }
                .empty {
                    background:var(--panel-strong);
                    border:2px solid var(--ink);
                    box-shadow:var(--shadow-sm);
                    text-align:center;
                    color:var(--muted);
                    padding:48px;
                    font-family:var(--font-mono);
                    font-size:12px;
                    font-weight:700;
                    letter-spacing:.06em;
                    text-transform:uppercase;
                }
                @media (max-width: 720px) {
                    .grid { grid-template-columns:1fr; }
                    .metrics, .avail-box { grid-template-columns:1fr; }
                    .avail-value { justify-self:start; }
                }
            </style>
            <div class="wrap">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="grid" data-ref="grid"></div>
            </div>
        `}afterMount(){this._cardEls=new Map,this._layoutKey="",this._heroCount=null,this.refs.hero.render({label:"Reliability",title:"Availability",metaHtml:""})}async refresh(){try{const t=(await v(`${f.airelay}/admin/channels`)).channels||[],r=t.map((a,s)=>({key:this._channelKey(a,s),channel:a}));if(this._renderHeroCount(t.length),t.length===0){this._layoutKey="",this._cardEls.clear(),this.refs.grid.innerHTML!=='<div class="empty">No channels configured yet.</div>'&&(this.refs.grid.innerHTML='<div class="empty">No channels configured yet.</div>');return}this._ensureGrid(r),r.forEach(({key:a,channel:s})=>this._patchCard(a,s,null)),await Promise.all(r.map(async({key:a,channel:s})=>{if(!s.channelId){this._patchCard(a,s,null);return}let i=null;try{i=await v(`${f.airelay}/admin/channels/${s.channelId}/stats?window=7d`)}catch{i=null}this._patchCard(a,s,i)}))}catch(e){this.refs.grid.innerHTML=`<div class="empty">Failed to load channels: ${l(e.message)}</div>`}}_ensureGrid(e){const t=e.map(({key:r})=>r).join("|");t===this._layoutKey&&this._cardEls.size===e.length||(this._layoutKey=t,this.refs.grid.innerHTML=e.map(({key:r})=>this._cardShellHtml(r)).join(""),this._cardEls=new Map(e.map(({key:r})=>[r,this.refs.grid.querySelector(`[data-card="${r}"]`)])))}_cardShellHtml(e){return`
            <div class="card" data-card="${l(e)}">
                <div class="card-head">
                    <div class="signal" data-field="signal"></div>
                    <div class="head-main">
                        <h3 class="name" data-field="name"></h3>
                        <div class="sub">
                            <span class="proto-tag" data-field="protocol"></span>
                            <span class="model-name" data-field="model"></span>
                        </div>
                    </div>
                    <span class="status-badge" data-field="status"></span>
                </div>

                <div class="card-body">
                    <div class="metrics">
                        <div class="metric">
                            <div class="metric-label">Conversation latency</div>
                            <div class="metric-value"><span data-field="convLatency"></span><span class="unit">ms</span></div>
                        </div>
                        <div class="metric">
                            <div class="metric-label">Endpoint ping</div>
                            <div class="metric-value"><span data-field="pingLatency"></span><span class="unit">ms</span></div>
                        </div>
                    </div>

                    <div class="avail-box">
                        <div>
                            <div class="avail-label">Availability · 7d</div>
                            <div class="avail-extra" data-field="extraModels" hidden></div>
                        </div>
                        <span class="avail-value" data-field="availabilityValue"><span data-field="availability"></span><span class="pct" data-field="availabilityPct"></span></span>
                    </div>

                    <div class="spark-card">
                        <div class="spark-head">
                            <span data-field="sparkCount"></span>
                            <span>Live refresh</span>
                        </div>
                        <div class="spark" data-field="spark"></div>
                        <div class="spark-foot"><span>Past</span><span>Now</span></div>
                    </div>
                </div>
            </div>
        `}_patchCard(e,t,r){const a=this._cardEls.get(e);if(!a)return;const s=this._cardState(t,r),i=o=>a.querySelector(`[data-field="${o}"]`);this._setText(i("signal"),s.signalText),this._setClass(i("signal"),"signal",s.signalClass),this._setText(i("name"),s.name),this._setText(i("protocol"),s.protocol),this._setText(i("model"),s.model),this._setText(i("status"),s.statusLabel),this._setClass(i("status"),"status-badge",s.statusClass),this._setText(i("convLatency"),s.convLatency),this._setText(i("pingLatency"),s.pingLatency),this._setText(i("availability"),s.availability),this._setText(i("availabilityPct"),s.availabilityPct),this._setStyle(i("availabilityValue"),"color",s.availabilityColor),this._setText(i("extraModels"),s.extraModels),this._setHidden(i("extraModels"),!s.extraModels),this._setText(i("sparkCount"),s.sparkCountLabel),this._setHtml(i("spark"),s.sparkHtml)}_cardState(e,t){const a=e.enabled!==!1&&e.status==="HEALTHY"&&!e.lastTestError,s=(e.models||[]).filter(m=>m.enabled).map(m=>m.publicModelName),i=s[0]||"—",o=s.length>1?`+${s.length-1} models`:"",d=(t==null?void 0:t.avgLatencyMs)??e.lastTestLatencyMs??0,h=e.lastTestLatencyMs??0,n=t?t.successRate7d*100:null,p=n==null?"var(--muted)":n>=95?"var(--green)":n>=50?"var(--amber)":"var(--red)",u=(t==null?void 0:t.recentTests)||[];return{signalClass:a?"ok":"err",signalText:a?"UP":"ERR",name:e.name||"—",protocol:this._protoLabel(e.protocol),model:i,statusClass:a?"ok":"err",statusLabel:this._statusLabel(e,a),convLatency:String(d),pingLatency:String(h),availability:n==null?"—":n.toFixed(2),availabilityPct:n==null?"":"%",availabilityColor:p,extraModels:o,sparkCountLabel:`Last ${u.length||60} checks`,sparkHtml:this._sparkHtml(u)}}_sparkHtml(e){const r=(e||[]).slice(0,60).reverse(),a=60-r.length,s=[];for(let i=0;i<a;i++)s.push('<span class="bar idle"></span>');return r.forEach(i=>s.push(`<span class="bar ${i.ok?"ok":"fail"}" title="${l(new Date(i.timestamp).toLocaleString())}: ${i.ok?"OK":"FAIL"}${i.latencyMs?` (${i.latencyMs}ms)`:""}"></span>`)),s.join("")}_protoLabel(e){const t=String(e||"").toUpperCase();return t.includes("ANTHROPIC")?"Anthropic":t.includes("OPENAI_RESPONSES")?"OpenAI Responses":t.includes("OPENAI")?"OpenAI":e||"—"}_statusLabel(e,t){return e.enabled?t?"Healthy":e.status==="DEGRADED"?"Degraded":"Error":"Disabled"}_renderHeroCount(e){this._heroCount!==e&&(this._heroCount=e,this.refs.hero.render({label:"Reliability",title:"Availability",metaHtml:`<span style="display:inline-flex;padding:7px 10px;border:1px solid rgba(235,231,223,0.18);background:rgba(11,11,11,0.18);color:var(--on-accent);font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;">${e} channels</span>`}))}_channelKey(e,t){return e.channelId||e.name||`channel-${t}`}_setText(e,t){if(!e)return;const r=String(t??"");e.textContent!==r&&(e.textContent=r)}_setHtml(e,t){e&&e.innerHTML!==t&&(e.innerHTML=t)}_setHidden(e,t){e&&e.hidden!==t&&(e.hidden=t)}_setClass(e,t,r){if(!e)return;const a=`${t} ${r}`.trim();e.className!==a&&(e.className=a)}_setStyle(e,t,r){e&&e.style[t]!==r&&(e.style[t]=r)}}customElements.define("ai-panel-availability",le);const de=[{value:"ANTHROPIC_MESSAGES",label:"Anthropic Messages"},{value:"OPENAI_CHAT",label:"OpenAI Chat Completions"},{value:"OPENAI_RESPONSES",label:"OpenAI Responses"}],O={HEALTHY:{bg:"var(--green-soft)",color:"var(--green)",label:"Healthy"},DEGRADED:{bg:"var(--amber-soft)",color:"var(--amber)",label:"Degraded"},DISABLED:{bg:"var(--red-soft)",color:"var(--red)",label:"Disabled"}};class ce extends y{constructor(){super(),this._editingId=null,this._groups=[],this._channels=[],this._hasRendered=!1}hostStyles(){return"height:100%;"}template(){return`
            <style>
                .panel-layout { display: flex; flex-direction: column; gap: 28px; }
                .toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
                .section-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 28px;
                    box-shadow: var(--shadow-sm);
                    border: 2px solid var(--ink);
                }
                .section-title {
                    font-family: var(--font-headline);
                    font-size: 20px; font-weight: 500; margin: 0 0 20px; color: var(--ink);
                }
                .btn-primary {
                    padding: 12px 24px; border: 2px solid var(--ink); border-radius: 0;
                    background: var(--surface-accent); color: var(--on-accent); font-size: 11px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; cursor: pointer; transition: background 200ms ease;
                }
                .btn-primary:hover { background: var(--teal); border-color: var(--teal); }
                .btn-ghost {
                    padding: 8px 16px; border: 1px solid var(--line-strong); border-radius: 0;
                    background: transparent; color: var(--ink); font-size: 10px; font-weight: 700;
                    letter-spacing: 0.08em; text-transform: uppercase; cursor: pointer; transition: all 150ms ease;
                }
                .btn-ghost:hover { background: var(--panel-strong); }
                .channel-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 20px; }
                .channel-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    border: 2px solid var(--ink);
                    box-shadow: var(--shadow-sm);
                    overflow: hidden;
                    display: flex; flex-direction: column;
                    transition: box-shadow 200ms ease, transform 200ms ease;
                }
                .channel-card:hover { box-shadow: var(--shadow-md); transform: translateY(-2px); }
                .cc-head { padding: 20px 22px 16px; border-bottom: 2px solid var(--ink); }
                .cc-name-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
                .cc-name { font-family: var(--font-headline); font-size: 19px; font-weight: 600; color: var(--ink); margin: 0; }
                .chip { display: inline-flex; align-items: center; gap: 5px; padding: 4px 10px; border-radius: 0; font-size: 9px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.07em; }
                .cc-meta { margin-top: 8px; font-size: 11px; color: var(--muted); font-family: var(--font-mono); word-break: break-all; }
                .cc-proto { margin-top: 10px; display: inline-block; font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; color: var(--teal); }
                .cc-body { padding: 16px 22px; flex: 1; }
                .cc-models { display: flex; flex-wrap: wrap; gap: 6px; }
                .model-tag { font-size: 10px; font-family: var(--font-mono); background: rgba(15,23,42,0.05); padding: 3px 8px; border-radius: 0; color: var(--ink); }
                .cc-latency { margin-top: 14px; font-size: 11px; color: var(--muted); font-weight: 700; }
                .cc-actions { padding: 14px 22px; background: var(--color-surface-container-low, #f3f1ed); display: flex; gap: 8px; flex-wrap: wrap; }
                .btn-mini { padding: 6px 12px; border-radius: 0; border: 1px solid var(--line-strong); background: transparent; color: var(--ink); font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; cursor: pointer; transition: all 150ms ease; }
                .btn-mini:hover { background: var(--panel-strong); }
                .btn-mini.test { border-color: var(--teal); color: var(--teal); }
                .btn-mini.test:hover { background: var(--teal-soft); }
                .btn-mini.danger { border-color: var(--red); color: var(--red); }
                .btn-mini.danger:hover { background: var(--red-soft); }
                .empty { text-align: center; color: var(--muted); padding: 48px; font-size: 13px; }
                /* Modal */
                .overlay { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.32); display: none; align-items: center; justify-content: center; z-index: 900; backdrop-filter: blur(2px); }
                .overlay.open { display: flex; }
                .modal { background: var(--panel-strong); border-radius: var(--radius-xl); width: min(620px, 92vw); max-height: 88vh; overflow-y: auto; padding: 36px; box-shadow: var(--shadow-lg); }
                .modal h3 { font-family: var(--font-headline); font-size: 26px; font-style: italic; font-weight: 700; margin: 0 0 24px; letter-spacing: -0.02em; }
                .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
                .field { display: flex; flex-direction: column; }
                .field.full { grid-column: 1 / -1; }
                .field label { font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px; }
                .field input, .field select, .field textarea {
                    width: 100%; padding: 11px 13px; border: 0; border-radius: var(--radius-sm); font-size: 13px;
                    background: var(--color-surface-container-high, #e4e2dc); color: var(--ink); transition: all 150ms ease;
                }
                .field input:focus, .field select:focus, .field textarea:focus { outline: none; box-shadow: var(--shadow-sm); background: var(--color-surface-container-lowest, #fff); }
                .field .hint { font-size: 10px; color: var(--muted); margin-top: 6px; }
                .modal-actions { display: flex; gap: 12px; margin-top: 8px; }
                .test-banner { margin: 0 0 18px; padding: 12px 16px; border-radius: var(--radius-sm); font-size: 12px; font-weight: 600; display: none; }
                .test-banner.ok { display: block; background: var(--green-soft); color: var(--green); }
                .test-banner.err { display: block; background: var(--red-soft); color: var(--red); }
                .auth-preview { padding: 10px 12px; background: var(--color-surface-container-lowest, #fff); border: 1px dashed var(--ink); font-family: var(--font-mono); font-size: 11px; }
                .model-toolbar { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:10px; }
                .model-rows { display:grid; gap:10px; }
                .model-row { display:grid; grid-template-columns: 1.2fr 1.2fr auto auto; gap:8px; align-items:end; }
                .model-row .field { margin:0; }
                .model-row .field label { font-size:9px; margin-bottom:6px; }
                .model-row .btn-ghost, .model-row .btn-mini { align-self:stretch; }
                .model-row .btn-mini {
                    padding: 8px 10px; border: 1px solid var(--line-strong); background: transparent; color: var(--ink); font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; cursor: pointer;
                }
                .model-row .btn-mini:hover { background: var(--panel-strong); }
                .membership-toolbar { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:10px; }
                .membership-rows { display:grid; gap:10px; }
                .membership-row { display:grid; grid-template-columns: 1.2fr 0.7fr 0.7fr auto; gap:8px; align-items:end; }
                .membership-row .field { margin:0; }
                .membership-row .field label { font-size:9px; margin-bottom:6px; }
            </style>
            <div class="panel-layout">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="toolbar">
                    <span style="font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.1em;color:var(--muted);text-transform:uppercase;" data-ref="meta"></span>
                    <span style="flex:1 1 auto;"></span>
                    <button class="btn-primary" data-ref="addBtn">Add Channel</button>
                </div>
                <div data-ref="grid"></div>
            </div>

            <div class="overlay" data-ref="overlay">
                <div class="modal" data-ref="modal">
                    <h3 data-ref="modalTitle">Add Channel</h3>
                    <div class="test-banner" data-ref="testBanner"></div>
                    <div class="form-grid">
                        <div class="field"><label>Name</label><input data-ref="fName" placeholder="My Anthropic backup"></div>
                        <div class="field"><label>Protocol</label>
                            <select data-ref="fProtocol">${de.map(e=>`<option value="${e.value}">${e.label}</option>`).join("")}</select>
                        </div>
                        <div class="field"><label>Status</label><div class="auth-preview" data-ref="membershipSummary">1 membership</div></div>
                        <div class="field full"><label>Base URL</label><input data-ref="fBaseUrl" placeholder="http://127.0.0.1:15721"></div>
                        <div class="field full"><label>API Key</label><input data-ref="fApiKey" type="password" placeholder="leave blank to keep existing"><div class="hint">Stored encrypted. For env-based keys, set "API Key Env" instead.</div></div>
                        <div class="field"><label>API Key Env</label><input data-ref="fApiKeyEnv" placeholder="ANTHROPIC_AUTH_TOKEN"></div>
                        <div class="field"><label>Auth Preview</label><div class="auth-preview" data-ref="authPreview">x-api-key: $KEY</div></div>
                        <div class="field"><label>Max Concurrency</label><input data-ref="fMaxConc" type="number" value="10"></div>
                        <div class="field full">
                            <div class="membership-toolbar">
                                <label>Memberships</label>
                                <button class="btn-ghost" data-ref="addMembershipBtn">+ Add Membership</button>
                            </div>
                            <div class="membership-rows" data-ref="membershipRows"></div>
                            <div class="hint">A channel can belong to multiple groups with different priority/weight values.</div>
                        </div>
                        <div class="field full">
                            <div class="model-toolbar">
                                <label>Models</label>
                                <div style="display:flex;gap:8px;">
                                    <button class="btn-ghost" data-ref="fetchModelsBtn">Fetch Models</button>
                                    <button class="btn-ghost" data-ref="addModelBtn">+ Add Model</button>
                                </div>
                            </div>
                            <div class="model-rows" data-ref="modelRows"></div>
                            <div class="hint">These are the model names clients can request through this channel. Fetch will add upstream ids as both public and upstream names.</div>
                        </div>
                    </div>
                    <div class="modal-actions">
                        <button class="btn-primary" data-ref="saveBtn">Save</button>
                        <button class="btn-ghost" data-ref="testInModalBtn">Test connection</button>
                        <button class="btn-ghost" data-ref="cancelBtn">Cancel</button>
                    </div>
                </div>
            </div>
        `}afterMount(){this.refs.hero.render({label:"Channel Management",title:"Channels",metaHtml:""}),this.refs.addBtn.addEventListener("click",()=>this._openModal(null)),this.refs.cancelBtn.addEventListener("click",()=>this._closeModal()),this.refs.overlay.addEventListener("click",e=>{e.target===this.refs.overlay&&this._closeModal()}),this.refs.saveBtn.addEventListener("click",()=>this._save()),this.refs.testInModalBtn.addEventListener("click",()=>this._testFromForm()),this.refs.addModelBtn.addEventListener("click",()=>this._addModelRow()),this.refs.addMembershipBtn.addEventListener("click",()=>this._addMembershipRow()),this.refs.fetchModelsBtn.addEventListener("click",()=>this._fetchModels()),this.refs.fProtocol.addEventListener("change",()=>this._applyProtocolHints()),this.refs.grid.addEventListener("click",e=>this._handleGridAction(e))}async refresh(){try{const[e,t]=await Promise.all([v(`${f.airelay}/admin/channels`),v(`${f.airelay}/admin/groups`).catch(()=>({groups:[]}))]);this._groups=t.groups||[],this._channels=e.channels||[],this._render(this._channels,{silent:this._hasRendered}),this._hasRendered=!0}catch(e){this.refs.grid.innerHTML=`<div class="empty">Failed to load channels: ${l(e.message)}</div>`}}_render(e,{silent:t=!1}={}){if(this.refs.hero.render({label:"Channel Management",title:"Channels",metaHtml:`<span style="display:inline-flex;align-items:center;padding:7px 10px;border:1px solid rgba(235,231,223,0.18);background:rgba(11,11,11,0.18);color:var(--on-accent);font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.08em;text-transform:uppercase;">${e.length} channel${e.length!==1?"s":""}</span>`}),this.refs.meta.textContent=`${e.length} upstream${e.length!==1?"s":""} configured`,e.length===0){this.refs.grid.innerHTML='<div class="section-card"><div class="empty">No channels configured yet.<br>Click <strong>Add Channel</strong> to point the gateway at an upstream (e.g. a local Anthropic endpoint).</div></div>';return}const r=this.refs.grid.querySelector(".channel-grid");if(!t||!r){this.refs.grid.innerHTML=`<div class="channel-grid">${e.map(a=>this._cardHtml(a)).join("")}</div>`,this._hasRendered||this._animateCards();return}this._patchCards(r,e)}_cardHtml(e){const t=O[e.enabled?e.status:"DISABLED"]||O.HEALTHY,r=(e.models||[]).filter(o=>o.enabled).map(o=>`<span class="model-tag">${l(o.publicModelName)}</span>`).join("")||'<span class="cc-meta">No models</span>',a=e.lastTestLatencyMs!=null?`Last test: ${e.lastTestError?'<span style="color:var(--red)">failed</span>':`${e.lastTestLatencyMs}ms OK`}`:"Not tested yet",i=(e.memberships&&e.memberships.length?e.memberships:[{groupId:e.groupId||"default",priority:e.priority??0,weight:e.weight??100,enabled:e.enabled}]).map(o=>`${o.groupId}:P${o.priority}/W${o.weight}`).join(" · ");return`
            <div class="channel-card" data-card="${e.channelId}">
                <div class="cc-head">
                    <div class="cc-name-row">
                        <h4 class="cc-name">${l(e.name)}</h4>
                        <span class="chip" style="background:${t.bg};color:${t.color};">${t.label}</span>
                    </div>
                    <div class="cc-meta">${l(e.baseUrl)}</div>
                    <span class="cc-proto">${l(e.protocol)} · ${l(i)}</span>
                </div>
                <div class="cc-body">
                    <div class="cc-models">${r}</div>
                    <div class="cc-latency">${a}</div>
                </div>
                <div class="cc-actions">
                    <button class="btn-mini test" data-test="${e.channelId}">Test</button>
                    <button class="btn-mini" data-edit="${e.channelId}">Edit</button>
                    <button class="btn-mini" data-toggle="${e.channelId}" data-enabled="${e.enabled}">${e.enabled?"Disable":"Enable"}</button>
                    <button class="btn-mini danger" data-delete="${e.channelId}">Delete</button>
                </div>
            </div>`}_handleGridAction(e){const t=e.target.closest("[data-test]");if(t){this._test(t.dataset.test,t);return}const r=e.target.closest("[data-edit]");if(r){const i=this._channels.find(o=>o.channelId===r.dataset.edit);i&&this._openModal(i);return}const a=e.target.closest("[data-toggle]");if(a){this._toggle(a.dataset.toggle,a.dataset.enabled!=="true");return}const s=e.target.closest("[data-delete]");s&&this._delete(s.dataset.delete)}_patchCards(e,t){const r=new Map(Array.from(e.querySelectorAll(".channel-card")).map(s=>[s.dataset.card,s]));t.map(s=>{const i=this._cardSnapshot(s);let o=r.get(s.channelId);if(!o)o=this._createCard(s);else if(o.dataset.snapshot!==i){const d=this._createCard(s);o.replaceWith(d),o=d}return o}).forEach((s,i)=>{e.children[i]!==s&&e.insertBefore(s,e.children[i]||null)}),r.forEach((s,i)=>{!t.some(o=>o.channelId===i)&&s.isConnected&&s.remove()})}_createCard(e){const t=document.createElement("div");t.innerHTML=this._cardHtml(e).trim();const r=t.firstElementChild;return r.dataset.snapshot=this._cardSnapshot(e),r}_cardSnapshot(e){return JSON.stringify({channelId:e.channelId,name:e.name,protocol:e.protocol,baseUrl:e.baseUrl,enabled:e.enabled,status:e.status,lastTestLatencyMs:e.lastTestLatencyMs,lastTestError:e.lastTestError,models:(e.models||[]).map(t=>({publicModelName:t.publicModelName,upstreamModelName:t.upstreamModelName,enabled:t.enabled})),memberships:(e.memberships||[]).map(t=>({groupId:t.groupId,priority:t.priority,weight:t.weight,enabled:t.enabled}))})}_animateCards(){const e=window.gsap,t=this.shadowRoot.querySelectorAll(".channel-card");!e||t.length===0||e.fromTo(t,{autoAlpha:0,y:18},{autoAlpha:1,y:0,duration:.45,ease:"power2.out",stagger:.06})}_openModal(e){var s,i;this._editingId=e?e.channelId:null,this.refs.modalTitle.textContent=e?"Edit Channel":"Add Channel",this.refs.testBanner.className="test-banner",this.refs.fName.value=(e==null?void 0:e.name)||"",this.refs.fProtocol.value=(e==null?void 0:e.protocol)||"ANTHROPIC_MESSAGES",this.refs.fBaseUrl.value=(e==null?void 0:e.baseUrl)||"",this.refs.fApiKey.value="",this.refs.fApiKey.placeholder=e?"leave blank to keep existing":"sk-...",this.refs.fApiKeyEnv.value=(e==null?void 0:e.apiKeyEnv)||"",this.refs.fMaxConc.value=(e==null?void 0:e.maxConcurrency)??10,this.refs.membershipRows.innerHTML="",((s=e==null?void 0:e.memberships)!=null&&s.length?e.memberships:[{groupId:(e==null?void 0:e.groupId)||"default",priority:(e==null?void 0:e.priority)??0,weight:(e==null?void 0:e.weight)??100,enabled:!0}]).forEach(o=>this._addMembershipRow(o)),this.refs.modelRows.innerHTML="",((i=e==null?void 0:e.models)!=null&&i.length?e.models:[{publicModelName:"",upstreamModelName:"",enabled:!0,creditMultiplier:""}]).forEach(o=>this._addModelRow(o)),this._applyProtocolHints(),this._renderMembershipSummary(),this.refs.overlay.classList.add("open");const a=window.gsap;a&&a.fromTo(this.refs.modal,{autoAlpha:0,y:20,scale:.98},{autoAlpha:1,y:0,scale:1,duration:.3,ease:"power2.out"})}_closeModal(){this.refs.overlay.classList.remove("open")}_applyProtocolHints(){this.refs.fProtocol.value==="ANTHROPIC_MESSAGES"?(this.refs.fApiKeyEnv.placeholder="ANTHROPIC_API_KEY",this.refs.authPreview.textContent="x-api-key: $KEY"):(this.refs.fApiKeyEnv.placeholder="OPENAI_API_KEY",this.refs.authPreview.textContent="Authorization: Bearer $KEY")}_addModelRow(e={publicModelName:"",upstreamModelName:"",enabled:!0,creditMultiplier:""}){const t=document.createElement("div");t.className="model-row",t.innerHTML=`
            <div class="field">
                <label>Public Name</label>
                <input data-role="public" value="${l(e.publicModelName||"")}" placeholder="claude-sonnet-4-20250514">
            </div>
            <div class="field">
                <label>Upstream Name</label>
                <input data-role="upstream" value="${l(e.upstreamModelName||"")}" placeholder="claude-sonnet-4-20250514">
            </div>
            <div class="field">
                <label>Credit ×</label>
                <input data-role="credit" type="number" step="0.1" min="0" value="${l(e.creditMultiplier??"")}" placeholder="1.0">
            </div>
            <div class="field" style="flex-direction:row;align-items:center;gap:6px;">
                <label style="margin:0;"><input type="checkbox" data-role="model-enabled" ${e.enabled!==!1?"checked":""} /> On</label>
            </div>
            <div style="display:flex;gap:6px;">
                <button class="btn-mini" data-role="test-model">Test</button>
                <button class="btn-mini" data-role="remove-model">Remove</button>
            </div>
            <div class="field full" data-role="test-result" style="display:none;margin-top:4px;">
                <div class="test-banner" style="margin:0;"></div>
            </div>
        `,t.querySelector('[data-role="remove-model"]').addEventListener("click",()=>{t.remove(),this.refs.modelRows.children.length||this._addModelRow()}),t.querySelector('[data-role="test-model"]').addEventListener("click",()=>this._testModelRow(t)),this.refs.modelRows.appendChild(t)}_addMembershipRow(e={groupId:"default",priority:0,weight:100,enabled:!0}){const t=(this._groups&&this._groups.length?this._groups:[{groupId:"default",name:"Default"}]).map(a=>`<option value="${l(a.groupId)}"${a.groupId===e.groupId?" selected":""}>${l(a.name||a.groupId)}</option>`).join(""),r=document.createElement("div");r.className="membership-row",r.innerHTML=`
            <div class="field">
                <label>Group</label>
                <select data-role="group">${t}</select>
            </div>
            <div class="field">
                <label>Priority</label>
                <input data-role="priority" type="number" value="${Number(e.priority??0)}">
            </div>
            <div class="field">
                <label>Weight</label>
                <input data-role="weight" type="number" value="${Number(e.weight??100)}">
            </div>
            <button class="btn-mini" data-role="remove-membership">Remove</button>
        `,r.querySelector('[data-role="remove-membership"]').addEventListener("click",()=>{r.remove(),this.refs.membershipRows.children.length||this._addMembershipRow(),this._renderMembershipSummary()}),r.querySelectorAll("select,input").forEach(a=>a.addEventListener("change",()=>this._renderMembershipSummary())),this.refs.membershipRows.appendChild(r),this._renderMembershipSummary()}_readMembershipRows(){return Array.from(this.refs.membershipRows.querySelectorAll(".membership-row")).map(e=>({groupId:e.querySelector('[data-role="group"]').value.trim()||"default",priority:parseInt(e.querySelector('[data-role="priority"]').value,10)||0,weight:parseInt(e.querySelector('[data-role="weight"]').value,10)||100,enabled:!0}))}_renderMembershipSummary(){const e=this._readMembershipRows();this.refs.membershipSummary.textContent=`${e.length} membership${e.length===1?"":"s"}`}_readModelRows(){return Array.from(this.refs.modelRows.querySelectorAll(".model-row")).map(e=>{var i;const t=e.querySelector('[data-role="public"]').value.trim(),r=e.querySelector('[data-role="upstream"]').value.trim(),a=e.querySelector('[data-role="credit"]').value.trim(),s=((i=e.querySelector('[data-role="model-enabled"]'))==null?void 0:i.checked)??!0;return t?{publicModelName:t,upstreamModelName:r,creditMultiplier:a?parseFloat(a):null,enabled:s}:null}).filter(Boolean)}_collectForm(){var t,r,a;const e=this._readModelRows();return{name:this.refs.fName.value.trim(),protocol:this.refs.fProtocol.value,baseUrl:this.refs.fBaseUrl.value.trim(),apiKey:this.refs.fApiKey.value,apiKeyEnv:this.refs.fApiKeyEnv.value.trim()||null,enabled:!0,priority:((t=this._readMembershipRows()[0])==null?void 0:t.priority)||0,weight:((r=this._readMembershipRows()[0])==null?void 0:r.weight)||100,maxConcurrency:parseInt(this.refs.fMaxConc.value)||10,timeoutMs:6e4,groupId:((a=this._readMembershipRows()[0])==null?void 0:a.groupId)||"default",memberships:this._readMembershipRows(),models:e}}async _fetchModels(){this.refs.fetchModelsBtn.disabled=!0;try{let e;if(this._editingId?e=await w(`${f.airelay}/admin/channels/${this._editingId}/discover-models`,{}):e=await w(`${f.airelay}/admin/channels/discover-models`,{protocol:this.refs.fProtocol.value,baseUrl:this.refs.fBaseUrl.value.trim(),apiKey:this.refs.fApiKey.value,apiKeyEnv:this.refs.fApiKeyEnv.value.trim()||null}),e.error){this._banner(!1,e.error);return}const t=new Set(Array.from(this.refs.modelRows.querySelectorAll(".model-row")).map(r=>r.querySelector('[data-role="public"]').value.trim().toLowerCase()).filter(Boolean));(e.models||[]).forEach(r=>{t.has(r.toLowerCase())||this._addModelRow({publicModelName:r,upstreamModelName:r,enabled:!0})}),this.refs.modelRows.children.length||this._addModelRow(),this._banner(!0,`Fetched ${(e.models||[]).length} model(s) in ${e.latencyMs}ms`)}catch(e){this._banner(!1,e.message)}finally{this.refs.fetchModelsBtn.disabled=!1}}async _testModelRow(e){const t=e.querySelector('[data-role="public"]').value.trim(),r=e.querySelector('[data-role="upstream"]').value.trim(),a=e.querySelector('[data-role="test-result"]'),s=a==null?void 0:a.querySelector(".test-banner");if(!t){s&&(s.className="test-banner err",s.textContent="Public model name is required."),a&&(a.style.display="block");return}if(!this._editingId){s&&(s.className="test-banner err",s.textContent="Save the channel first, then run model-level test."),a&&(a.style.display="block");return}try{const i=await w(`${f.airelay}/admin/channels/${this._editingId}/test-model`,{publicModelName:t,upstreamModelName:r||null});s&&(s.className=`test-banner ${i.ok?"ok":"err"}`,s.textContent=i.ok?`OK in ${i.latencyMs}ms`:i.error||"Failed"),a&&(a.style.display="block")}catch(i){s&&(s.className="test-banner err",s.textContent=i.message),a&&(a.style.display="block")}}async _save(){const e=this._collectForm();if(!e.name||!e.baseUrl){this._banner(!1,"Name and Base URL are required.");return}try{this._editingId?await I(`${f.airelay}/admin/channels/${this._editingId}`,e):await w(`${f.airelay}/admin/channels`,e),this._closeModal(),this.refresh()}catch(t){this._banner(!1,t.message)}}async _testFromForm(){if(!this._editingId){this._banner(!1,"Save the provider first, then Test.");return}await this._test(this._editingId,this.refs.testInModalBtn,!0)}async _test(e,t,r=!1){const a=t.textContent;t.textContent="Testing…",t.disabled=!0;try{const s=await w(`${f.airelay}/admin/channels/${e}/test`,{});r?this._banner(s.ok,s.ok?`Connected in ${s.latencyMs}ms`:s.error||"Test failed"):this.refresh()}catch(s){r&&this._banner(!1,s.message)}finally{t.textContent=a,t.disabled=!1}}async _toggle(e,t){try{await w(`${f.airelay}/admin/channels/${e}/enabled/${t}`,{}),this.refresh()}catch(r){alert(r.message)}}async _delete(e){if(confirm("Delete this provider? Clients using its models will lose access."))try{await S(`${f.airelay}/admin/channels/${e}`),this.refresh()}catch(t){alert(t.message)}}_banner(e,t){this.refs.testBanner.className=`test-banner ${e?"ok":"err"}`,this.refs.testBanner.textContent=t}}customElements.define("ai-panel-providers",ce);const pe={ALL_MODELS:"All Models",ALIASES_ONLY:"Aliases Only",ALIASES_AND_MODELS:"Aliases + Models",ALIAS_ONLY:"Aliases Only",ALIAS_AND_MODEL_NAMES:"Aliases + Models",MODEL_NAMES_ONLY:"All Models"};class he extends y{hostStyles(){return"height:100%;"}template(){return`
            <style>
                .layout { display: grid; gap: 22px; }
                .toolbar { display: flex; align-items: center; gap: 12px; }
                .toolbar .spacer { flex: 1; }
                .btn-primary, .btn-ghost, .btn-danger {
                    cursor: pointer; font-family: var(--font-mono); font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em;
                }
                .btn-primary { padding: 11px 18px; border: 2px solid var(--ink); background: var(--surface-accent); color: var(--on-accent); font-size: 11px; }
                .btn-primary:hover { background: var(--teal); border-color: var(--teal); }
                .btn-ghost { padding: 8px 12px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); font-size: 10px; }
                .btn-ghost:hover { background: var(--surface-accent); color: var(--on-accent); }
                .btn-danger { padding: 7px 11px; border: 2px solid var(--red); background: transparent; color: var(--red); font-size: 10px; }
                .btn-danger:hover { background: var(--red); color: var(--on-accent); }
                .grid { display: grid; gap: 18px; }
                .group-card { background: var(--panel-strong); border: 2px solid var(--ink); box-shadow: var(--shadow-sm); overflow: hidden; }
                .group-head { display: flex; justify-content: space-between; gap: 16px; flex-wrap: wrap; padding: 18px 18px 16px; background: var(--surface-accent); color: var(--on-accent); }
                .group-title { margin: 0; font-family: var(--font-headline); font-size: 22px; line-height: 1; letter-spacing: -0.04em; text-transform: uppercase; }
                .group-sub { display: block; margin-top: 6px; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.16em; text-transform: uppercase; opacity: 0.7; }
                .group-meta { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
                .tag { display: inline-flex; align-items: center; padding: 4px 9px; border: 1px solid rgba(235, 231, 223, 0.18); font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase; }
                .tag.is-enabled { background: var(--surface-strong); color: var(--ink); border-color: var(--ink); }
                .tag.is-disabled { background: var(--red); color: var(--on-accent); border-color: var(--red); }
                .group-body { padding: 16px 18px; display: grid; gap: 16px; }
                .group-overview { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(320px, 0.9fr); gap: 16px; }
                .info-stack { display: grid; gap: 14px; }
                .detail-card {
                    border: 1px solid var(--ink);
                    background: var(--surface-muted);
                    padding: 12px;
                }
                .detail-card.aliases {
                    background: linear-gradient(180deg, rgba(20, 184, 166, 0.06), transparent 44%), var(--surface-muted);
                }
                .section-label {
                    font-family: var(--font-mono);
                    font-size: 10px;
                    font-weight: 800;
                    letter-spacing: 0.12em;
                    text-transform: uppercase;
                    color: var(--muted);
                    margin-bottom: 8px;
                }
                .models { display: flex; flex-wrap: wrap; gap: 7px; }
                code { font-family: var(--font-mono); font-size: 10px; font-weight: 800; background: var(--surface-accent); color: var(--on-accent); padding: 2px 6px; text-transform: uppercase; letter-spacing: 0.04em; }
                .priority-block { border: 1px solid var(--ink); }
                .priority-head { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: var(--surface-muted); font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink); }
                .priority-head .badge { background: var(--red); color: var(--on-accent); padding: 2px 8px; }
                table { width: 100%; border-collapse: collapse; font-family: var(--font-mono); font-size: 11px; }
                th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--ink); vertical-align: middle; }
                th { font-size: 9px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase; color: var(--muted); background: var(--surface-soft); border-bottom: 1px solid var(--ink); }
                tr:last-child td { border-bottom: 0; }
                .status { font-weight: 800; text-transform: uppercase; letter-spacing: 0.06em; }
                .status.ok { color: var(--green); } .status.warn { color: var(--amber); } .status.bad { color: var(--red); }
                .empty { padding: 32px 20px; text-align: center; font-family: var(--font-mono); font-size: 12px; font-weight: 800; color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em; }
                .membership-tools, .alias-row { display: grid; grid-template-columns: 1.4fr .55fr .55fr auto; gap: 8px; align-items: end; }
                .membership-tools { padding: 12px; border: 1px dashed var(--ink); background: var(--surface-muted); }
                .inline-input, .inline-select { width: 100%; padding: 7px 8px; border: 1px solid var(--ink); background: var(--paper); color: var(--ink); font-family: var(--font-mono); font-size: 11px; }
                .row-actions { display: flex; gap: 6px; }
                .field-mini { display: grid; gap: 4px; }
                .field-mini span { font-family: var(--font-mono); font-size: 9px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--muted); }
                .alias-route-list { display: grid; gap: 8px; }
                .alias-route { padding: 10px 12px; border: 1px dashed var(--ink); background: var(--surface-soft); }
                .alias-route-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
                .alias-route-body { font-family: var(--font-mono); font-size: 10px; color: var(--muted); line-height: 1.6; }
                /* Modal */
                .overlay { position: fixed; inset: 0; background: rgba(11,11,11,0.55); display: none; align-items: center; justify-content: center; z-index: 900; }
                .overlay.open { display: flex; }
                .modal { width: min(680px, 94vw); max-height: 88vh; overflow-y: auto; background: var(--paper); border: 2px solid var(--ink); box-shadow: var(--shadow-lg); }
                .modal-head { display: flex; justify-content: space-between; padding: 14px 18px; background: var(--surface-accent); color: var(--on-accent); font-family: var(--font-headline); font-size: 18px; letter-spacing: -0.04em; text-transform: uppercase; }
                .modal-body { padding: 18px; display: grid; gap: 14px; }
                .field label { display: block; margin-bottom: 6px; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted); }
                .field input, .field textarea, .field select { width: 100%; padding: 10px 12px; border: 2px solid var(--ink); background: var(--paper); font-family: var(--font-mono); font-size: 12px; color: var(--ink); }
                .modal-actions { display: flex; gap: 10px; justify-content: flex-end; }
                .error-banner { display: none; padding: 10px 14px; background: var(--red); color: var(--on-accent); font-family: var(--font-mono); font-size: 11px; font-weight: 800; letter-spacing: 0.06em; }
                .error-banner.visible { display: block; }
                @media (max-width: 1080px) {
                    .group-overview { grid-template-columns: 1fr; }
                }
            </style>
            <div class="layout">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="toolbar">
                    <span style="font-family:var(--font-mono);font-size:11px;font-weight:800;letter-spacing:0.12em;text-transform:uppercase;color:var(--muted);" data-ref="metaLine"></span>
                    <span class="spacer"></span>
                    <button class="btn-primary" data-ref="newBtn">+ New Group</button>
                </div>
                <div class="grid" data-ref="grid"></div>
            </div>

            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span data-ref="modalTitle">New Group</span><button class="btn-ghost" data-ref="closeBtn">ESC</button></div>
                    <div class="error-banner" data-ref="error"></div>
                    <div class="modal-body">
                        <div class="field"><label>Group ID</label><input data-ref="fId" placeholder="premium" /></div>
                        <div class="field"><label>Display Name</label><input data-ref="fName" placeholder="Premium" /></div>
                        <div class="field"><label>Description</label><textarea data-ref="fDesc" rows="2" placeholder="Optional"></textarea></div>
                        <div class="field">
                            <label>Exposure Mode</label>
                            <select data-ref="fExposureMode">
                                <option value="ALL_MODELS">All Models</option>
                                <option value="ALIASES_ONLY">Aliases Only</option>
                                <option value="ALIASES_AND_MODELS">Aliases + Models</option>
                            </select>
                        </div>
                        <div class="field">
                            <label>Alias Routes</label>
                            <datalist id="aliasModelList" data-ref="aliasModelList"></datalist>
                            <div data-ref="aliasRows" style="display:grid;gap:8px;margin-bottom:8px;"></div>
                            <button class="btn-ghost" data-ref="addAliasBtn" style="font-size:10px;">+ Add Alias Target</button>
                            <div style="margin-top:6px;font-family:var(--font-mono);font-size:10px;color:var(--muted);">Each row is one fallback step: alias name → target model → optional channel constraint. Leave channel empty to match any attached channel that serves that model.</div>
                        </div>
                        <div class="field"><label><input type="checkbox" data-ref="fEnabled" checked /> Enabled</label></div>
                        <div class="modal-actions"><button class="btn-ghost" data-ref="cancelBtn">Cancel</button><button class="btn-primary" data-ref="saveBtn">Save</button></div>
                    </div>
                </div>
            </div>
        `}afterMount(){this.refs.hero.render({label:"Routing Pools",title:"Groups",metaHtml:""}),this._groups=[],this._channels=[],this._chains=[],this._editing=null,this.refs.newBtn.addEventListener("click",()=>this._openModal(null)),this.refs.cancelBtn.addEventListener("click",()=>this._closeModal()),this.refs.closeBtn.addEventListener("click",()=>this._closeModal()),this.refs.overlay.addEventListener("click",e=>{e.target===this.refs.overlay&&this._closeModal()}),this.refs.saveBtn.addEventListener("click",()=>this._save()),this.refs.addAliasBtn.addEventListener("click",()=>this._addAliasRow())}async refresh(){try{const[e,t,r]=await Promise.all([v(`${f.airelay}/admin/groups`),v(`${f.airelay}/admin/channels`),v(`${f.airelay}/admin/pools`)]);this._groups=e.groups||[],this._channels=t.channels||[],this._chains=r.chains||[],this._render()}catch(e){this.refs.grid.innerHTML=`<div class="empty">Failed to load groups: ${l(e.message)}</div>`}}_render(){const e=this._groups.length;if(this.refs.metaLine.textContent=`${e} group${e!==1?"s":""} / ${this._channels.length} channels`,this.refs.hero.render({label:"Routing Pools",title:"Groups",metaHtml:`<span style="display:inline-flex;align-items:center;padding:7px 10px;border:1px solid rgba(235,231,223,0.18);background:rgba(11,11,11,0.18);color:var(--on-accent);font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.08em;text-transform:uppercase;">${e} group${e!==1?"s":""}</span>`}),!e){this.refs.grid.innerHTML='<div class="empty">No routing groups configured.</div>';return}const t=this._channels.reduce((a,s)=>{var o;return((o=s.memberships)!=null&&o.length?s.memberships:[{groupId:s.groupId||"default",priority:s.priority??0,weight:s.weight??100,enabled:s.enabled}]).filter(d=>d.enabled).forEach(d=>{var h;return(a[h=d.groupId]||(a[h]=[])).push({...s,membershipPriority:d.priority,membershipWeight:d.weight,membershipEnabled:d.enabled})}),a},{}),r=Object.fromEntries(this._chains.map(a=>[a.chainId,a]));this.refs.grid.innerHTML=this._groups.map(a=>this._renderGroupCard(a,t[a.groupId]||[],r[a.groupId])).join(""),this._bindCardActions()}_renderGroupCard(e,t,r){const a=[...new Set(t.flatMap(m=>(m.models||[]).filter(_=>_.enabled).map(_=>_.publicModelName)))].sort(),s=t.reduce((m,_)=>{var C;return(m[C=String(_.membershipPriority??_.priority??0)]||(m[C]=[])).push(_),m},{}),i=Object.keys(s).sort((m,_)=>Number(_)-Number(m)),o=r?Object.fromEntries(r.levels.map(m=>[m.levelId,m])):{},d=(e.aliasRoutes||[]).filter(m=>m.enabled),h=Object.fromEntries(this._channels.map(m=>[m.channelId,m.name])),n=d.map(m=>m.aliasName),p=this._fallbackExposedModels(e.exposureMode,a,n.length?n:(r==null?void 0:r.modelAliases)||[]),u=new Set(t.map(m=>m.channelId)),b=this._channels.filter(m=>!u.has(m.channelId)).map(m=>`<option value="${l(m.channelId)}">${l(m.name)} · ${l(m.protocol)}</option>`).join(""),x=i.length===0?'<div class="empty">No channels assigned to this group.</div>':i.map((m,_)=>{const C=`${e.groupId}-p${m}`,T=o[C],E=s[m];return`
                <div class="priority-block">
                    <div class="priority-head"><span><span class="badge">P${m}</span>&nbsp; ${_===0?"Primary":`Backup #${_}`}</span><span>${E.length} channel${E.length!==1?"s":""}${T?` · ${T.healthyKeys} healthy / ${T.cooldownKeys+T.degradedKeys+T.disabledKeys} watch`:""}</span></div>
                    <table><thead><tr><th>Channel</th><th>Protocol</th><th>Priority</th><th>Weight</th><th>Models</th><th>Status</th><th>Actions</th></tr></thead><tbody>
                        ${E.map($=>`
                            <tr data-membership-row="${l(e.groupId)}:${l($.channelId)}">
                                <td>${l($.name)}</td>
                                <td>${l($.protocol)}</td>
                                <td><input class="inline-input" data-member-priority value="${$.membershipPriority??$.priority??0}" type="number"></td>
                                <td><input class="inline-input" data-member-weight value="${$.membershipWeight??$.weight??100}" type="number"></td>
                                <td>${($.models||[]).filter(A=>A.enabled).map(A=>`<code>${l(A.publicModelName)}</code>`).join(" ")||'<span style="color:var(--muted);">—</span>'}</td>
                                <td>${this._statusCell($)}</td>
                                <td><div class="row-actions"><button class="btn-ghost" data-save-membership="${l(e.groupId)}:${l($.channelId)}">Save</button><button class="btn-danger" data-detach-membership="${l(e.groupId)}:${l($.channelId)}">Detach</button></div></td>
                            </tr>`).join("")}
                    </tbody></table>
                </div>`}).join("");return`
            <article class="group-card">
                <header class="group-head">
                    <div><h3 class="group-title">${l(e.name||e.groupId)}</h3><span class="group-sub">${l(e.groupId)}${e.description?` · ${l(e.description)}`:""}</span></div>
                    <div class="group-meta">
                        <span class="tag ${e.enabled?"is-enabled":"is-disabled"}">${e.enabled?"ENABLED":"DISABLED"}</span>
                        <span class="tag is-enabled">${t.length} channels</span><span class="tag is-enabled">${a.length} models</span>
                        <button class="btn-ghost" data-edit-group="${l(e.groupId)}">Edit</button>
                        ${e.groupId==="default"?"":`<button class="btn-danger" data-delete-group="${l(e.groupId)}">Delete</button>`}
                    </div>
                </header>
                <div class="group-body">
                    <div class="group-overview">
                        <div class="info-stack">
                            <div class="detail-card">
                                <div class="section-label">Exposed To Clients</div>
                                <div class="models"><code>${l(pe[e.exposureMode]||e.exposureMode||"All Models")}</code>${p.map(m=>`<code>${l(m)}</code>`).join("")||'<span class="empty">Nothing exposed</span>'}</div>
                            </div>
                            <div class="detail-card">
                                <div class="section-label">Channel Models</div>
                                <div class="models">${a.map(m=>`<code>${l(m)}</code>`).join("")||'<span class="empty">No channel models</span>'}</div>
                            </div>
                        </div>
                        <div class="detail-card aliases">
                            <div class="section-label">Alias Routes</div>
                            ${this._renderAliasRoutes(d,h)}
                        </div>
                    </div>
                    <div class="membership-tools">
                        <select class="inline-select" data-attach-channel="${l(e.groupId)}">${b||'<option value="">No unattached channels</option>'}</select>
                        <input class="inline-input" data-attach-priority="${l(e.groupId)}" value="0" type="number" placeholder="Priority">
                        <input class="inline-input" data-attach-weight="${l(e.groupId)}" value="100" type="number" placeholder="Weight">
                        <button class="btn-primary" data-attach-membership="${l(e.groupId)}" ${b?"":"disabled"}>Attach</button>
                    </div>
                    ${x}
                </div>
            </article>`}_bindCardActions(){this.shadowRoot.querySelectorAll("[data-edit-group]").forEach(e=>e.addEventListener("click",()=>this._openModal(this._groups.find(t=>t.groupId===e.dataset.editGroup)))),this.shadowRoot.querySelectorAll("[data-delete-group]").forEach(e=>e.addEventListener("click",()=>this._delete(e.dataset.deleteGroup))),this.shadowRoot.querySelectorAll("[data-attach-membership]").forEach(e=>e.addEventListener("click",()=>this._attachMembership(e.dataset.attachMembership))),this.shadowRoot.querySelectorAll("[data-save-membership]").forEach(e=>e.addEventListener("click",()=>this._saveMembership(e.dataset.saveMembership))),this.shadowRoot.querySelectorAll("[data-detach-membership]").forEach(e=>e.addEventListener("click",()=>this._detachMembership(e.dataset.detachMembership)))}_statusCell(e){return e.enabled?e.status==="HEALTHY"?'<span class="status ok">Healthy</span>':e.status==="DEGRADED"?'<span class="status warn">Degraded</span>':`<span class="status bad">${l(e.status||"Unknown")}</span>`:'<span class="status bad">Disabled</span>'}_fallbackExposedModels(e,t,r){const a=[...new Set((r||[]).filter(Boolean))],s=this._normalizeExposure(e);return s==="ALIASES_ONLY"?a:s==="ALIASES_AND_MODELS"?[...new Set([...a,...t])]:t}_renderAliasRoutes(e,t){return e.length?`<div class="alias-route-list">${e.map(r=>{var s;const a=((s=r.targets)!=null&&s.length?r.targets:(r.targetModels||[]).map(i=>({model:i,channelId:null}))).map(i=>{const o=i.channelId?t[i.channelId]?`${t[i.channelId]} (${i.channelId})`:i.channelId:"Any attached channel";return`${i.model} -> ${o}`});return`
                <div class="alias-route">
                    <div class="alias-route-head"><code>${l(r.aliasName)}</code><span class="tag is-enabled">${a.length} target${a.length===1?"":"s"}</span>${r.creditMultiplier!=null?`<span class="tag">×${l(String(r.creditMultiplier))} credit</span>`:""}</div>
                    <div class="alias-route-body">${a.map(i=>l(i)).join("<br>")}</div>
                </div>`}).join("")}</div>`:'<div class="empty">No aliases configured.</div>'}_openModal(e){this._editing=e||null,this.refs.modalTitle.textContent=e?`Edit Group · ${e.groupId}`:"New Group",this.refs.fId.value=(e==null?void 0:e.groupId)||"",this.refs.fId.disabled=!!e,this.refs.fName.value=(e==null?void 0:e.name)||"",this.refs.fDesc.value=(e==null?void 0:e.description)||"",this.refs.fExposureMode.value=this._normalizeExposure((e==null?void 0:e.exposureMode)||"ALL_MODELS"),this.refs.aliasRows.innerHTML="",this._syncAliasModelSuggestions(e),((e==null?void 0:e.aliasRoutes)||[]).forEach(t=>{var a;((a=t.targets)!=null&&a.length?t.targets:(t.targetModels||[]).map(s=>({model:s,channelId:""}))).forEach(s=>this._addAliasRow({aliasName:t.aliasName,model:s.model,channelId:s.channelId||"",enabled:t.enabled,creditMultiplier:t.creditMultiplier}))}),this.refs.aliasRows.children.length||this._addAliasRow(),this.refs.fEnabled.checked=(e==null?void 0:e.enabled)??!0,this.refs.error.classList.remove("visible"),this.refs.error.textContent="",this.refs.overlay.classList.add("open")}_syncAliasModelSuggestions(e){const t=new Set(this._channels.flatMap(s=>(s.memberships||[]).filter(i=>i.groupId===((e==null?void 0:e.groupId)||"")).map(()=>s.channelId))),r=e?this._channels.filter(s=>t.has(s.channelId)):this._channels,a=[...new Set(r.flatMap(s=>(s.models||[]).flatMap(i=>{const o=[i.publicModelName];return i.upstreamModelName&&i.upstreamModelName!==i.publicModelName&&o.push(i.upstreamModelName),o}).filter(Boolean)))].sort();this.refs.aliasModelList.innerHTML=a.map(s=>`<option value="${l(s)}"></option>`).join("")}_addAliasRow(e={aliasName:"",model:"",channelId:"",enabled:!0,creditMultiplier:""}){const t=document.createElement("div");t.className="alias-row";const r=['<option value="">Any attached channel</option>'].concat(this._channels.map(a=>`<option value="${l(a.channelId)}" ${a.channelId===e.channelId?"selected":""}>${l(a.name)} (${l(a.channelId)})</option>`)).join("");t.innerHTML=`
            <label class="field-mini"><span>Custom Name</span><input class="inline-input" data-alias-name value="${l(e.aliasName||"")}" placeholder="smart-claude"></label>
            <label class="field-mini"><span>Target Model</span><input class="inline-input" data-alias-model list="aliasModelList" value="${l(e.model||"")}" placeholder="public or upstream model"></label>
            <label class="field-mini"><span>Channel Constraint</span><select class="inline-select" data-alias-channel>${r}</select></label>
            <label class="field-mini"><span>Credit ×</span><input class="inline-input" data-alias-credit type="number" step="0.1" min="0" value="${l(e.creditMultiplier??"")}" placeholder="1.0"></label>
            <button class="btn-danger" data-remove-alias>Remove</button>`,t.querySelector("[data-remove-alias]").addEventListener("click",()=>{t.remove(),this.refs.aliasRows.children.length||this._addAliasRow()}),this.refs.aliasRows.appendChild(t)}_readAliasRows(){const e=new Map;return Array.from(this.refs.aliasRows.querySelectorAll(".alias-row")).forEach(t=>{const r=t.querySelector("[data-alias-name]").value.trim(),a=t.querySelector("[data-alias-model]").value.trim(),s=t.querySelector("[data-alias-channel]").value.trim(),i=t.querySelector("[data-alias-credit]").value.trim();!r||!a||(e.has(r)||e.set(r,[]),e.get(r).push({model:a,channelId:s||null,creditMultiplier:i?parseFloat(i):null}))}),Array.from(e.entries()).map(([t,r])=>{var a;return{aliasName:t,targetModels:r.map(s=>s.model),targets:r.map(({model:s,channelId:i})=>({model:s,channelId:i})),creditMultiplier:((a=r.find(s=>s.creditMultiplier!=null))==null?void 0:a.creditMultiplier)??null,enabled:!0}})}_normalizeExposure(e){return e==="ALIAS_ONLY"?"ALIASES_ONLY":e==="ALIAS_AND_MODEL_NAMES"?"ALIASES_AND_MODELS":e==="MODEL_NAMES_ONLY"?"ALL_MODELS":e||"ALL_MODELS"}_closeModal(){this.refs.overlay.classList.remove("open")}async _save(){const e={groupId:this.refs.fId.value.trim()||null,name:this.refs.fName.value.trim(),description:this.refs.fDesc.value.trim()||null,enabled:this.refs.fEnabled.checked,exposureMode:this.refs.fExposureMode.value||"ALL_MODELS",aliasRoutes:this._readAliasRows()};if(!e.name){this._showError("Display name is required.");return}try{if(this._editing)await I(`${f.airelay}/admin/groups/${encodeURIComponent(this._editing.groupId)}`,e);else{if(!e.groupId){this._showError("Group ID is required.");return}await w(`${f.airelay}/admin/groups`,e)}this._closeModal(),this.refresh()}catch(t){this._showError(t.message)}}async _attachMembership(e){var s,i,o;const t=(s=this.shadowRoot.querySelector(`[data-attach-channel="${CSS.escape(e)}"]`))==null?void 0:s.value;if(!t)return;const r=parseInt((i=this.shadowRoot.querySelector(`[data-attach-priority="${CSS.escape(e)}"]`))==null?void 0:i.value,10)||0,a=parseInt((o=this.shadowRoot.querySelector(`[data-attach-weight="${CSS.escape(e)}"]`))==null?void 0:o.value,10)||100;try{await w(`${f.airelay}/admin/groups/${encodeURIComponent(e)}/memberships`,{channelId:t,priority:r,weight:a,enabled:!0}),await this.refresh()}catch(d){alert(d.message)}}async _saveMembership(e){var o,d;const[t,r]=e.split(":"),a=this.shadowRoot.querySelector(`[data-membership-row="${CSS.escape(e)}"]`),s=parseInt((o=a==null?void 0:a.querySelector("[data-member-priority]"))==null?void 0:o.value,10)||0,i=parseInt((d=a==null?void 0:a.querySelector("[data-member-weight]"))==null?void 0:d.value,10)||100;try{await I(`${f.airelay}/admin/groups/${encodeURIComponent(t)}/memberships/${encodeURIComponent(r)}`,{priority:s,weight:i,enabled:!0}),await this.refresh()}catch(h){alert(h.message)}}async _detachMembership(e){const[t,r]=e.split(":");if(confirm(`Detach channel ${r} from group ${t}?`))try{await S(`${f.airelay}/admin/groups/${encodeURIComponent(t)}/memberships/${encodeURIComponent(r)}`),await this.refresh()}catch(a){alert(a.message)}}async _delete(e){if(confirm(`Delete group ${e}? Group must have no channels.`))try{await S(`${f.airelay}/admin/groups/${encodeURIComponent(e)}`),this.refresh()}catch(t){alert(t.message)}}_showError(e){this.refs.error.textContent=e,this.refs.error.classList.add("visible")}}customElements.define("ai-panel-groups",he);async function z(c){if(!await ie(c))try{alert("Copy failed — text is selectable above.")}catch{}}const U=[{id:"code",name:"Claude Code",icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>',note:"Shell env — run before launching Claude Code",snippet:(c,e)=>`export ANTHROPIC_BASE_URL="${c}"
export ANTHROPIC_API_KEY="${e}"`},{id:"desktop",name:"Claude Desktop",icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/></svg>',note:"Add to claude_desktop_config.json → env block",snippet:(c,e)=>`{
  "env": {
    "ANTHROPIC_BASE_URL": "${c}",
    "ANTHROPIC_API_KEY": "${e}"
  }
}`},{id:"codex",name:"Codex",icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="16"/><line x1="8" x2="16" y1="12" y2="12"/></svg>',note:"Shell env — run before launching Codex CLI",snippet:(c,e)=>`export OPENAI_API_KEY="${e}"
export OPENAI_BASE_URL="${c}"
export OPENAI_API_BASE="${c}"`},{id:"opencode",name:"OpenCode",icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>',note:"Shell env — compatible agents using Anthropic API",snippet:(c,e)=>`ANTHROPIC_BASE_URL="${c}"
ANTHROPIC_API_KEY="${e}"`},{id:"openclaw",name:"OpenClaw",icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>',note:"Shell env — OpenAI-compatible client",snippet:(c,e)=>`OPENAI_API_KEY="${e}"
OPENAI_BASE_URL="${c}"`},{id:"hermes",name:"Hermes",icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 15a4 4 0 0 0 4 4h9a5 5 0 1 0-.1-9.999 5.002 5.002 0 1 0-9.78 2.096A4.001 4.001 0 0 0 3 15z"/></svg>',note:"Shell env — Hermes-compatible SDK",snippet:(c,e)=>`export ANTHROPIC_BASE_URL="${c}"
export ANTHROPIC_API_KEY="${e}"`}];class fe extends y{hostStyles(){return"height:100%"}template(){return`
            <style>
                .panel-layout { display: grid; gap: 22px; }
                .section-card {
                    position: relative;
                    background: var(--panel-strong);
                    border: 2px solid var(--ink);
                    box-shadow: var(--shadow-sm);
                    overflow: hidden;
                }
                .section-title-row {
                    display: flex; align-items: center; justify-content: space-between; gap: 16px;
                    padding: 16px 18px;
                    background: var(--surface-accent);
                    color: var(--on-accent);
                }
                .section-kicker {
                    display: block; margin-bottom: 4px;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.18em; text-transform: uppercase; color: var(--red);
                }
                .section-title {
                    margin: 0; font-family: var(--font-headline); font-size: 20px; line-height: 1; letter-spacing: -0.04em; text-transform: uppercase;
                }
                .section-body { padding: 20px 18px; }
                /* Form */
                .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-bottom: 20px; }
                .field label {
                    display: block; font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px;
                }
                .field input {
                    width: 100%; padding: 11px 12px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em;
                    transition: all 120ms var(--ease-smooth);
                }
                .field input:focus { outline: none; box-shadow: var(--shadow-sm); }
                .btn-primary {
                    padding: 12px 22px; border: 2px solid var(--ink); background: var(--surface-accent); color: var(--on-accent); cursor: pointer;
                    font-family: var(--font-mono); font-size: 11px; font-weight: 800; letter-spacing: 0.14em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .btn-primary:hover { background: var(--red); border-color: var(--red); }
                .btn-ghost {
                    padding: 11px 18px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); cursor: pointer;
                    font-family: var(--font-mono); font-size: 11px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .btn-ghost:hover { background: var(--surface-accent); color: var(--on-accent); }
                .btn-danger {
                    padding: 7px 12px; border: 2px solid var(--red); background: transparent; color: var(--red); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .btn-danger:hover { background: var(--red); color: var(--on-accent); }
                .form-actions { display: flex; gap: 10px; }
                /* Key reveal (inline) */
                .key-reveal {
                    display: none; margin-top: 16px; padding: 14px 16px;
                    background: var(--red-soft); border: 2px solid var(--red);
                }
                .key-reveal.is-visible { display: block; }
                .key-reveal .kicker { font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.18em; text-transform: uppercase; color: var(--red); margin-bottom: 8px; }
                .key-reveal .key-row {
                    display: flex; align-items: center; gap: 10px;
                    padding: 10px 12px; background: var(--paper); border: 2px solid var(--ink);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 800; letter-spacing: 0.04em; color: var(--ink);
                    word-break: break-all; user-select: all;
                }
                .key-reveal .copy-btn {
                    flex-shrink: 0; padding: 7px 12px; border: 2px solid var(--ink); background: var(--surface-accent); color: var(--on-accent); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                }
                .key-reveal .copy-btn:hover { background: var(--red); border-color: var(--red); }
                /* Modal overlay */
                .connect-overlay {
                    display: none; position: fixed; inset: 0; z-index: 900;
                    background: rgba(11,11,11,0.55);
                    backdrop-filter: blur(2px);
                    align-items: center; justify-content: center;
                }
                .connect-overlay.is-open { display: flex; }
                .connect-modal {
                    position: relative;
                    width: min(820px, 92vw);
                    max-height: 88vh;
                    overflow: hidden;
                    background: var(--paper);
                    border: 2px solid var(--ink);
                    box-shadow: var(--shadow-lg);
                    display: grid;
                    grid-template-rows: auto auto 1fr;
                }
                .modal-bar {
                    display: flex; align-items: center; justify-content: space-between; gap: 12px;
                    padding: 14px 18px;
                    background: var(--surface-accent); color: var(--on-accent);
                }
                .modal-bar-title {
                    font-family: var(--font-headline); font-size: 18px; line-height: 1; letter-spacing: -0.04em; text-transform: uppercase;
                }
                .modal-close {
                    padding: 6px 12px; border: 1px solid var(--on-accent); background: transparent; color: var(--on-accent); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .modal-close:hover { background: var(--red); border-color: var(--red); }
                /* Key display in modal */
                .modal-key {
                    display: flex; align-items: center; gap: 10px;
                    padding: 14px 18px;
                    border-bottom: 2px solid var(--ink);
                    background: var(--red-soft);
                }
                .modal-key-val {
                    flex: 1; min-width: 0;
                    padding: 10px 12px; background: var(--paper); border: 2px solid var(--ink);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 800; letter-spacing: 0.04em; color: var(--ink);
                    word-break: break-all; overflow: hidden; text-overflow: ellipsis;
                    user-select: all;
                }
                .modal-key-copy {
                    flex-shrink: 0; padding: 9px 14px; border: 2px solid var(--ink); background: var(--surface-accent); color: var(--on-accent); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .modal-key-copy:hover { background: var(--red); border-color: var(--red); }
                /* Client tabs in modal */
                .modal-clients {
                    display: grid;
                    grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
                    gap: 1px;
                    background: var(--surface-accent);
                    border-bottom: 2px solid var(--ink);
                }
                .modal-client-tab {
                    display: flex; align-items: center; justify-content: center; gap: 7px;
                    padding: 12px 8px;
                    background: var(--surface-soft); color: var(--muted); cursor: pointer;
                    font-family: var(--font-mono); font-size: 9.5px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    border-right: 1px solid var(--ink);
                    transition: background 120ms, color 120ms;
                }
                .modal-client-tab:last-child { border-right: 0; }
                .modal-client-tab svg { width: 15px; height: 15px; flex-shrink: 0; }
                .modal-client-tab.is-active { background: var(--surface-accent); color: var(--on-accent); }
                /* Client content */
                .modal-content {
                    overflow-y: auto;
                    padding: 18px;
                    background: var(--surface-muted);
                }
                .client-snippet-wrap { display: none; }
                .client-snippet-wrap.is-active { display: block; }
                .snippet-note {
                    margin-bottom: 10px;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--muted);
                }
                .snippet-code {
                    position: relative;
                    padding: 16px; background: var(--surface-accent);
                    font-family: var(--font-mono); font-size: 11px; line-height: 1.7; color: var(--on-accent);
                    white-space: pre-wrap; word-break: break-all; letter-spacing: 0.02em;
                    border: 2px solid var(--ink); min-height: 70px;
                }
                .snippet-copy {
                    display: block; margin-top: 10px; width: 100%; padding: 10px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); cursor: pointer;
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .snippet-copy:hover { background: var(--surface-accent); color: var(--on-accent); }
            </style>
            <div class="panel-layout" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>

                <div class="section-card">
                    <div class="section-title-row">
                        <div>
                            <span class="section-kicker">[ Key Generator ]</span>
                            <h3 class="section-title">Create API Key</h3>
                        </div>
                        <button class="btn-primary" data-ref="toggleForm">+ New Key</button>
                    </div>
                    <div class="section-body" data-ref="formBody" hidden>
                        <div class="form-grid">
                            <div class="field"><label>Display Name</label><input data-ref="fName" placeholder="My API Key"></div>
                            <div class="field"><label>Routing Group</label><select data-ref="fGroup"></select></div>
                            <div class="field"><label>Max Budget (USD)</label><input data-ref="fBudget" type="number" value="10"></div>
                            <div class="field"><label>RPM Limit</label><input data-ref="fRpm" type="number" placeholder="120"></div>
                            <div class="field"><label>TPM Limit</label><input data-ref="fTpm" type="number" placeholder="120000"></div>
                        </div>
                        <div class="form-actions">
                            <button class="btn-primary" data-ref="submitBtn">Generate</button>
                            <button class="btn-ghost" data-ref="cancelBtn">Cancel</button>
                        </div>
                        <div class="key-reveal" data-ref="keyReveal">
                            <div class="kicker">/// Key Generated — Copy Now (will not be shown again)</div>
                            <div class="key-row">
                                <span data-ref="keyValue"></span>
                                <button class="copy-btn" data-ref="copyKeyBtn">Copy</button>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="section-card">
                    <div class="section-title-row">
                        <div>
                            <span class="section-kicker">[ Active Registry ]</span>
                            <h3 class="section-title">Your API Keys</h3>
                        </div>
                        <span style="font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.12em;text-transform:uppercase;" data-ref="keyCount"></span>
                    </div>
                    <div class="section-body">
                        <keel-data-table data-ref="table"></keel-data-table>
                    </div>
                </div>

                <!-- Global connect modal -->
                <div class="connect-overlay" data-ref="connectOverlay">
                    <div class="connect-modal">
                        <div class="modal-bar">
                            <span class="section-kicker" style="color:var(--red);margin-bottom:2px;">/// Quick Connect</span>
                            <button class="modal-close" data-ref="modalClose">ESC</button>
                        </div>
                        <div class="modal-key">
                            <span class="modal-key-val" data-ref="modalKeyVal"></span>
                            <button class="modal-key-copy" data-ref="modalKeyCopy">Copy Key</button>
                        </div>
                        <div class="modal-clients" data-ref="modalClients"></div>
                        <div class="modal-content" data-ref="modalContent"></div>
                    </div>
                </div>
            </div>
        `}afterMount(){this._relayBase=`${location.origin}/api/plugins/airelay/v1`,this._currentModalKey=null,this._groups=[],this._generatedSecrets=new Map,this.refs.toggleForm.addEventListener("click",()=>{this.refs.formBody.hidden=!this.refs.formBody.hidden,this.refs.keyReveal.classList.remove("is-visible"),this._populateGroupSelect()}),this.refs.cancelBtn.addEventListener("click",()=>{this.refs.formBody.hidden=!0}),this.refs.copyKeyBtn.addEventListener("click",()=>{z(this.refs.keyValue.textContent),this.refs.copyKeyBtn.textContent="Copied",setTimeout(()=>{this.refs.copyKeyBtn.textContent="Copy"},1500)}),this.refs.submitBtn.addEventListener("click",async()=>{var e;try{const t=await w(`${f.token}/v1/keys`,{displayName:this.refs.fName.value||"API Key",groupId:this.refs.fGroup.value||"default",maxBudgetUsd:parseFloat(this.refs.fBudget.value)||10,rpmLimit:parseInt(this.refs.fRpm.value)||null,tpmLimit:parseInt(this.refs.fTpm.value)||null});this.refs.keyValue.textContent=t.rawKey,this.refs.keyReveal.classList.add("is-visible"),(e=t.key)!=null&&e.keyId&&this._generatedSecrets.set(t.key.keyId,t.rawKey),this.refresh()}catch(t){alert(t.message)}}),this.refs.modalClose.addEventListener("click",()=>this._closeModal()),this.refs.connectOverlay.addEventListener("click",e=>{e.target===this.refs.connectOverlay&&this._closeModal()}),document.addEventListener("keydown",e=>{e.key==="Escape"&&this.refs.connectOverlay.classList.contains("is-open")&&this._closeModal()}),this.refs.modalKeyCopy.addEventListener("click",()=>{this._currentModalKey&&(z(this._currentModalKey),this.refs.modalKeyCopy.textContent="Copied",setTimeout(()=>{this.refs.modalKeyCopy.textContent="Copy Key"},1500))}),this.refs.modalClients.addEventListener("click",e=>{const t=e.target.closest("[data-client]");t&&this._setActiveClient(t.dataset.client)}),this.refs.modalContent.addEventListener("click",e=>{const t=e.target.closest(".snippet-copy");if(!t)return;const r=t.previousElementSibling;r&&(z(r.textContent),t.textContent="Copied",setTimeout(()=>{t.textContent="Copy to Clipboard"},1500))})}_populateGroupSelect(){const e=(this._groups.length?this._groups:[{groupId:"default",name:"Default"}]).map(t=>`<option value="${l(t.groupId)}">${l(t.name||t.groupId)}</option>`).join("");this.refs.fGroup.innerHTML=e}async refresh(){try{const[e,t]=await Promise.all([v(`${f.token}/admin/keys`),v(`${f.airelay}/admin/groups`).catch(()=>({groups:[]}))]);this._groups=t.groups||[],this._populateGroupSelect(),this._render(e.keys||[])}catch(e){this.refs.table.render({headers:[],rows:[],emptyHtml:`<div class="km-empty">// Failed to load: ${l(e.message)}</div>`})}}_render(e){this.refs.hero.render({label:"Token Management",title:"API Keys",metaHtml:`<div style="display:flex;flex-direction:column;align-items:flex-end;gap:4px;">
                <span style="font-family:var(--font-headline);font-size:clamp(32px,4vw,56px);line-height:0.82;letter-spacing:-0.06em;color:var(--paper);">${e.length}</span>
                <span style="font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.16em;text-transform:uppercase;color:var(--paper);">Keys / Active</span>
            </div>`}),this.refs.keyCount&&(this.refs.keyCount.textContent=`${e.length} key${e.length!==1?"s":""}`),this.refs.table.render({silent:!!this._hasRendered,headers:["Key ID","Name","Group","User","Status","Budget","Spent","Remaining","Action"],rows:e.map(t=>[`<code>${l(t.keyId)}</code>`,l(t.displayName||""),`<code>${l(t.groupId||"default")}</code>`,`<samp>${l(t.userId||"")}</samp>`,t.status==="active"?'<span class="chip is-healthy"><span>●</span>Active</span>':'<span class="chip is-alert"><span>■</span>Revoked</span>',`<data value="${t.maxBudgetUsd||0}">$${(t.maxBudgetUsd||0).toFixed(2)}</data>`,`<data value="${t.currentSpendUsd||0}">$${(t.currentSpendUsd||0).toFixed(4)}</data>`,`<data value="${t.remainingBudgetUsd||0}">$${(t.remainingBudgetUsd||0).toFixed(4)}</data>`,t.status==="active"?`<button class="btn-ghost btn-connect" data-connect="${l(t.keyId)}" style="margin-right:6px;padding:6px 10px;font-size:9px;">Connect</button><button class="btn-danger" data-delete-key="${l(t.keyId)}">Delete</button>`:""]),emptyHtml:'<div class="km-empty">// No API keys. Generate one above to get started.</div>'}),this._hasRendered=!0,this.refs.table.shadowRoot.querySelectorAll("[data-connect]").forEach(t=>{t.addEventListener("click",()=>this._openModal(t.dataset.connect))}),this.refs.table.shadowRoot.querySelectorAll("[data-delete-key]").forEach(t=>{t.addEventListener("click",async()=>{if(confirm("Delete this key? This cannot be undone."))try{await S(`${f.token}/v1/keys/${t.dataset.deleteKey}`),this.refresh()}catch(r){alert(r.message)}})})}_openModal(e){const t=this._generatedSecrets.get(e)||"";this._currentModalKey=t,t?(this.refs.modalKeyVal.textContent=t,this.refs.modalKeyCopy.disabled=!1,this.refs.modalKeyCopy.textContent="Copy Key"):(this.refs.modalKeyVal.textContent=`${e} · secret shown once at creation — paste the value you saved.`,this.refs.modalKeyCopy.disabled=!0,this.refs.modalKeyCopy.textContent="No Secret");const r=this._relayBase,a=t||"PASTE_YOUR_SAVED_SK_KEEL_KEY_HERE";this.refs.modalClients.innerHTML=U.map((s,i)=>`<div class="modal-client-tab ${i===0?"is-active":""}" data-client="${s.id}">
                ${s.icon}<span>${s.name}</span>
            </div>`).join(""),this.refs.modalContent.innerHTML=U.map((s,i)=>`<div class="client-snippet-wrap ${i===0?"is-active":""}" data-client-panel="${s.id}">
                <div class="snippet-note">${s.note}</div>
                <pre class="snippet-code">${s.snippet(r,a)}</pre>
                <button class="snippet-copy">Copy to Clipboard</button>
            </div>`).join(""),this.refs.connectOverlay.classList.add("is-open")}_setActiveClient(e){this.refs.modalClients.querySelectorAll(".modal-client-tab").forEach(t=>t.classList.toggle("is-active",t.dataset.client===e)),this.refs.modalContent.querySelectorAll(".client-snippet-wrap").forEach(t=>t.classList.toggle("is-active",t.dataset.clientPanel===e))}_closeModal(){this.refs.connectOverlay.classList.remove("is-open"),this._currentModalKey=null}}customElements.define("ai-panel-keys",fe);class me extends y{hostStyles(){return"height:100%;"}template(){return`
            <style>
                .layout { display: flex; flex-direction: column; gap: 24px; height: 100%; }
                .toolbar { display: flex; align-items: center; gap: 12px; }
                .toolbar .spacer { flex: 1; }
                .btn { padding: 11px 18px; border: 2px solid var(--ink); background: var(--ink); color: var(--paper); font-family: var(--font-mono); font-size: 11px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.12em; cursor: pointer; }
                .btn:hover { background: var(--teal); border-color: var(--teal); }
                .btn-ghost { padding: 8px 12px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; cursor: pointer; }
                .btn-ghost:hover { background: var(--ink); color: var(--paper); }
                .btn-danger { padding: 7px 11px; border: 2px solid var(--red); background: transparent; color: var(--red); font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em; cursor: pointer; }
                .btn-danger:hover { background: var(--red); color: var(--paper); }
                .table-card { background: var(--panel-strong); border: 2px solid var(--ink); overflow: hidden; flex: 1; }
                .table-wrap { max-height: 70vh; overflow: auto; }
                .empty { text-align: center; color: var(--muted); padding: 40px; font-family: var(--font-mono); font-size: 12px; }
                .tier-list { display:grid; gap:4px; max-width:540px; }
                .tier-line { font-family:var(--font-mono); font-size:10px; line-height:1.45; }
                .overlay { position: fixed; inset: 0; background: rgba(11,11,11,0.55); display: none; align-items: center; justify-content: center; z-index: 900; }
                .overlay.open { display: flex; }
                .modal { width: min(640px, 92vw); max-height: 90vh; overflow-y: auto; border: 2px solid var(--ink); background: var(--paper); box-shadow: var(--shadow-lg); }
                .modal-head { display: flex; justify-content: space-between; padding: 14px 18px; background: var(--ink); color: var(--paper); font-family: var(--font-headline); font-size: 16px; text-transform: uppercase; letter-spacing: -0.04em; }
                .modal-body { padding: 18px; display: grid; gap: 14px; }
                .field label { display: block; margin-bottom: 6px; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.14em; text-transform: uppercase; color: var(--muted); }
                .field input, .field textarea { width: 100%; padding: 10px 12px; border: 2px solid var(--ink); background: var(--paper); color: var(--ink); font-family: var(--font-mono); font-size: 13px; }
                .field input:focus, .field textarea:focus { outline: none; box-shadow: var(--shadow-sm); }
                .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
                .grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; }
                .switch-row { display:flex; gap:16px; flex-wrap:wrap; font-family:var(--font-mono); font-size:11px; font-weight:800; text-transform:uppercase; }
                .error { display: none; padding: 8px 14px; background: var(--red); color: var(--paper); font-family: var(--font-mono); font-size: 10px; font-weight: 800; }
                .error.show { display: block; }
            </style>
            <div class="layout" data-ref="root">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="toolbar">
                    <span class="spacer" style="font-family:var(--font-mono);font-size:10px;font-weight:800;letter-spacing:0.1em;color:var(--muted);text-transform:uppercase;" data-ref="meta"></span>
                    <button class="btn" data-ref="addBtn">+ Add Pricing</button>
                </div>
                <div class="table-card"><div class="table-wrap" data-ref="tableWrap"></div></div>
            </div>
            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span data-ref="modalTitle">Add Pricing</span><button class="btn-ghost" style="color:var(--paper);border-color:var(--paper);" data-ref="closeBtn">&#10005;</button></div>
                    <div class="error" data-ref="error"></div>
                    <div class="modal-body">
                        <div class="field"><label>Model Name</label><input data-ref="fModel" placeholder="claude-sonnet-4-20250514" /></div>
                        <div class="grid-3">
                            <div class="field"><label>Variant Key</label><input data-ref="fVariant" placeholder="claude-std or claude-1m" /></div>
                            <div class="field"><label>Label (opt)</label><input data-ref="fLabel" placeholder="Claude Std" /></div>
                            <div class="field"><label>Billing Unit</label><input data-ref="fUnit" type="number" step="1000" placeholder="1000000" value="1000000" /></div>
                        </div>
                        <div class="switch-row">
                            <label><input type="checkbox" data-ref="fUse1M" checked /> Use 1M token unit</label>
                            <label><input type="checkbox" data-ref="fTiered" /> Enable tiered pricing</label>
                        </div>
                        <div class="grid-2">
                            <div class="field"><label>Input $/unit</label><input data-ref="fInput" type="number" step="0.0001" placeholder="3.00" /></div>
                            <div class="field"><label>Output $/unit</label><input data-ref="fOutput" type="number" step="0.0001" placeholder="15.00" /></div>
                        </div>
                        <div class="grid-2">
                            <div class="field"><label>Cache Creation $/unit (opt)</label><input data-ref="fCacheW" type="number" step="0.0001" placeholder="3.75" /></div>
                            <div class="field"><label>Cache Read $/unit (opt)</label><input data-ref="fCacheR" type="number" step="0.0001" placeholder="0.30" /></div>
                        </div>
                        <div class="grid-2">
                            <div class="field"><label>Reasoning $/unit (opt)</label><input data-ref="fReason" type="number" step="0.0001" placeholder="" /></div>
                            <div class="field"><label>Credit Multiplier (opt)</label><input data-ref="fCredit" type="number" step="0.1" min="0" placeholder="1.0" /></div>
                        </div>
                        <div class="field"><label>Notes (opt)</label><input data-ref="fNotes" placeholder="Public Anthropic pricing" /></div>
                        <div class="field" data-ref="tiersField">
                            <label>Tiers</label>
                            <textarea data-ref="fTiers" rows="5" placeholder="Format: start-end|unit|input|output|cacheWrite|cacheRead|reasoning&#10;0-200000|200000|0.80|4.00|1.00|0.08|4.00&#10;200000-*|1000000|3.00|15.00|3.75|0.30|15.00"></textarea>
                        </div>
                        <button class="btn" data-ref="saveBtn">Save</button>
                    </div>
                </div>
            </div>
        `}afterMount(){this._pricings=[],this._hasRendered=!1,this.refs.hero.render({label:"Model Pricing",title:"Pricing",metaHtml:""}),this.refs.addBtn.addEventListener("click",()=>this._openModal(null)),this.refs.closeBtn.addEventListener("click",()=>this._closeModal()),this.refs.overlay.addEventListener("click",e=>{e.target===this.refs.overlay&&this._closeModal()}),this.refs.saveBtn.addEventListener("click",()=>this._save()),this.refs.fUse1M.addEventListener("change",()=>{this.refs.fUse1M.checked&&(this.refs.fUnit.value=1e6)}),this.refs.fTiered.addEventListener("change",()=>this._syncTierVisibility())}async refresh(){try{const e=await v(`${f.airelay}/admin/pricing`);this._pricings=e.pricings||[],this.refs.hero.render({label:"Model Pricing",title:"Pricing",metaHtml:`<div style="padding:16px 22px;font-family:var(--font-headline);font-size:48px;line-height:0.8;letter-spacing:-0.05em;color:var(--paper);">${this._pricings.length}</div>`}),this._renderTable(this._hasRendered),this._hasRendered=!0,this.refs.meta.textContent=`${this._pricings.length} RATE CARD${this._pricings.length===1?"":"S"} CONFIGURED`}catch(e){this.refs.tableWrap.innerHTML=`<div class="empty">Error: ${l(e.message)}</div>`}}_renderTable(e){if(this._pricings.length===0){this.refs.tableWrap.innerHTML='<div class="empty">// NO PRICING CONFIGURED — click "Add Pricing"</div>';return}const t=["Model","Variant","Unit","Credit ×","Rates / Tiers","Notes","Actions"],r=this._pricings.map(i=>[`<code>${l(i.model)}</code>`,i.variantKey?`<code>${l(i.variantKey)}</code>`:"—",`${L(i.billingUnitTokens||1e6)}`,i.creditMultiplier!=null?`×${l(String(i.creditMultiplier))}`:"—",this._rateHtml(i),i.notes?l(i.notes):"",`<button class="btn-ghost" data-edit="${l(i.pricingId)}">Edit</button> <button class="btn-danger" data-del="${l(i.pricingId)}">Del</button>`]);let a=this.refs.tableWrap.querySelector("keel-data-table");a||(a=document.createElement("keel-data-table"),this.refs.tableWrap.innerHTML="",this.refs.tableWrap.appendChild(a)),a.render({silent:e,headers:t,rows:r,emptyHtml:'<div class="empty">// NO PRICING</div>'});const s=a.shadowRoot||a;s.querySelectorAll("[data-edit]").forEach(i=>i.addEventListener("click",()=>this._openModal(i.dataset.edit))),s.querySelectorAll("[data-del]").forEach(i=>i.addEventListener("click",()=>this._delete(i.dataset.del)))}_rateHtml(e){var t;return(t=e.tiers)!=null&&t.length?`<div class="tier-list">${e.tiers.map(r=>{const a=r.endTokensExclusive==null?"∞":L(r.endTokensExclusive),s=r.cacheCreationCostPerUnit!=null||r.cacheReadCostPerUnit!=null?` · cache W/R $${this._fmt(r.cacheCreationCostPerUnit)}/$${this._fmt(r.cacheReadCostPerUnit)}`:"",i=r.reasoningOutputCostPerUnit!=null?` · reason $${this._fmt(r.reasoningOutputCostPerUnit)}`:"";return`<div class="tier-line"><code>${L(r.startTokensInclusive)}-${a}</code> unit ${L(r.billingUnitTokens)} · in/out $${this._fmt(r.inputCostPerUnit)}/$${this._fmt(r.outputCostPerUnit)}${s}${i}</div>`}).join("")}</div>`:`<div class="tier-line">flat · in/out $${this._fmt(e.inputCostPerMTok)}/$${this._fmt(e.outputCostPerMTok)}${e.cacheCreationCostPerMTok!=null||e.cacheReadCostPerMTok!=null?` · cache W/R $${this._fmt(e.cacheCreationCostPerMTok)}/$${this._fmt(e.cacheReadCostPerMTok)}`:""}${e.reasoningOutputCostPerMTok!=null?` · reason $${this._fmt(e.reasoningOutputCostPerMTok)}`:""}</div>`}_fmt(e){return Number(e??0).toFixed(4)}_openModal(e){var r;const t=e?this._pricings.find(a=>a.pricingId===e):null;this.refs.modalTitle.textContent=t?"Edit Pricing":"Add Pricing",this.refs.fModel.value=(t==null?void 0:t.model)||"",this.refs.fModel.disabled=!!t,this.refs.fVariant.value=(t==null?void 0:t.variantKey)||"",this.refs.fLabel.value=(t==null?void 0:t.label)||"",this.refs.fUnit.value=(t==null?void 0:t.billingUnitTokens)||1e6,this.refs.fUse1M.checked=Number(this.refs.fUnit.value)===1e6,this.refs.fInput.value=(t==null?void 0:t.inputCostPerMTok)??"",this.refs.fOutput.value=(t==null?void 0:t.outputCostPerMTok)??"",this.refs.fCacheW.value=(t==null?void 0:t.cacheCreationCostPerMTok)??"",this.refs.fCacheR.value=(t==null?void 0:t.cacheReadCostPerMTok)??"",this.refs.fReason.value=(t==null?void 0:t.reasoningOutputCostPerMTok)??"",this.refs.fCredit.value=(t==null?void 0:t.creditMultiplier)??"",this.refs.fNotes.value=(t==null?void 0:t.notes)??"",this.refs.fTiered.checked=!!((r=t==null?void 0:t.tiers)!=null&&r.length),this.refs.fTiers.value=((t==null?void 0:t.tiers)||[]).map(a=>`${a.startTokensInclusive}-${a.endTokensExclusive==null?"*":a.endTokensExclusive}|${a.billingUnitTokens}|${a.inputCostPerUnit}|${a.outputCostPerUnit}|${a.cacheCreationCostPerUnit??""}|${a.cacheReadCostPerUnit??""}|${a.reasoningOutputCostPerUnit??""}`).join(`
`),this._syncTierVisibility(),this.refs.error.classList.remove("show"),this.refs.overlay.classList.add("open")}_syncTierVisibility(){this.refs.tiersField.style.display=this.refs.fTiered.checked?"block":"none"}_closeModal(){this.refs.overlay.classList.remove("open")}async _save(){const e=this.refs.fModel.value.trim();if(!e){this._showError("Model name is required");return}const t=parseInt(this.refs.fUnit.value,10)||1e6,r=this.refs.fTiered.checked?this.refs.fTiers.value.split(`
`).map(s=>s.trim()).filter(Boolean).map(s=>{const[i,o,d,h,n,p,u]=s.split("|").map(m=>m.trim()),[b,x]=i.split("-").map(m=>m.trim());return{startTokensInclusive:parseInt(b,10)||0,endTokensExclusive:!x||x==="*"?null:parseInt(x,10),billingUnitTokens:parseInt(o,10)||t,inputCostPerUnit:parseFloat(d)||0,outputCostPerUnit:parseFloat(h)||0,cacheCreationCostPerUnit:n?parseFloat(n):null,cacheReadCostPerUnit:p?parseFloat(p):null,reasoningOutputCostPerUnit:u?parseFloat(u):null}}):[],a={model:e,variantKey:this.refs.fVariant.value.trim()||null,label:this.refs.fLabel.value.trim()||null,billingUnitTokens:t,tiers:r,inputCostPerMTok:parseFloat(this.refs.fInput.value)||0,outputCostPerMTok:parseFloat(this.refs.fOutput.value)||0,cacheCreationCostPerMTok:this.refs.fCacheW.value?parseFloat(this.refs.fCacheW.value):null,cacheReadCostPerMTok:this.refs.fCacheR.value?parseFloat(this.refs.fCacheR.value):null,reasoningOutputCostPerMTok:this.refs.fReason.value?parseFloat(this.refs.fReason.value):null,creditMultiplier:this.refs.fCredit.value?parseFloat(this.refs.fCredit.value):null,notes:this.refs.fNotes.value||null};this.refs.saveBtn.disabled=!0;try{await I(`${f.airelay}/admin/pricing`,a),this._closeModal(),this.refresh()}catch(s){this._showError(s.message)}this.refs.saveBtn.disabled=!1}async _delete(e){const t=this._pricings.find(r=>r.pricingId===e);if(confirm(`Delete pricing for "${(t==null?void 0:t.model)||e}"${t!=null&&t.variantKey?` (${t.variantKey})`:""}?`))try{const r=t!=null&&t.variantKey?`?variantKey=${encodeURIComponent(t.variantKey)}`:"";await S(`${f.airelay}/admin/pricing/${encodeURIComponent(e)}${r}`),this.refresh()}catch(r){alert("Error: "+r.message)}}_showError(e){this.refs.error.textContent=e,this.refs.error.classList.add("show")}}customElements.define("ai-panel-pricing",me);function k(c){return l(String(c??""))}function M(c,e=""){return`<span class="tag ${e}">${k(c)}</span>`}const K={HEALTHY:{cls:"st-ok",label:"Healthy"},COOLDOWN:{cls:"st-warn",label:"Cooldown"},DEGRADED:{cls:"st-warn",label:"Degraded"},DISABLED:{cls:"st-bad",label:"Disabled"}};class ue extends y{hostStyles(){return"height:100%"}template(){return`
        <style>
            :host { font-family: var(--font-body); color: var(--ink); }
            .layout { display: grid; gap: 24px; }
            /* hero right side */
            .hero-stat {
                display: grid; align-items: center; justify-content: center;
                padding: 12px 24px; min-width: 160px;
                font-family: var(--font-headline); font-size: 48px; line-height: 1;
                letter-spacing: -0.04em; color: var(--red);
            }
            /* card */
            .card {
                background: var(--paper); border: 2px solid var(--ink);
                overflow: hidden;
            }
            .card-head {
                display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px;
                padding: 16px 20px;
                background: var(--ink); color: var(--paper);
            }
            .card-title {
                font-family: var(--font-headline); font-size: 24px; font-weight: 900;
                line-height: 1; letter-spacing: -0.03em; text-transform: uppercase; margin: 0;
            }
            .card-head-right {
                display: flex; gap: 8px; flex-wrap: wrap;
            }
            .tag {
                display: inline-block; padding: 3px 8px;
                font-family: var(--font-mono); font-size: 10px; font-weight: 800;
                letter-spacing: 0.06em; text-transform: uppercase;
                border: 1px solid var(--ink); background: var(--paper); color: var(--ink);
            }
            .card-body { padding: 0; }
            /* level */
            .level { border-top: 1px solid var(--ink); }
            .level-top {
                display: grid; grid-template-columns: 52px 1fr auto; align-items: center;
            }
            .lvl-idx {
                display: flex; align-items: center; justify-content: center;
                height: 100%; min-height: 56px;
                background: var(--bg); border-right: 1px solid var(--ink);
                font-family: var(--font-headline); font-size: 18px; font-weight: 900; color: var(--red);
            }
            .lvl-main { padding: 14px 16px; min-width: 0; }
            .lvl-name {
                font-family: var(--font-headline); font-size: 16px; font-weight: 900;
                text-transform: uppercase; letter-spacing: -0.02em; margin: 0; line-height: 1;
            }
            .lvl-meta {
                display: flex; flex-wrap: wrap; gap: 6px 14px; margin-top: 6px;
                font-family: var(--font-mono); font-size: 11px; font-weight: 700;
                color: var(--muted); text-transform: uppercase; letter-spacing: 0.04em;
            }
            .lvl-meta code {
                background: var(--bg); border: 1px solid var(--ink); padding: 1px 6px;
                font-family: var(--font-mono); font-size: 11px; font-weight: 800; color: var(--ink);
            }
            .lvl-chips {
                display: flex; gap: 6px; padding: 0 16px; flex-shrink: 0;
            }
            .st {
                display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px;
                border: 1px solid var(--ink); background: var(--paper); color: var(--ink);
                font-family: var(--font-mono); font-size: 10px; font-weight: 800;
                text-transform: uppercase; letter-spacing: 0.06em; white-space: nowrap;
            }
            .st-ok { background: #d8ecdd; }
            .st-warn { background: #f7e6cf; }
            .st-bad { background: var(--red); color: var(--paper); }
            /* keys table inside level */
            .keys-table {
                border-top: 1px solid var(--ink);
            }
            .keys-table table {
                width: 100%; border-collapse: collapse;
                font-family: var(--font-mono); font-size: 11px;
            }
            .keys-table th {
                text-align: left; padding: 8px 12px;
                font-size: 9px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                background: var(--paper); border-bottom: 1px solid var(--ink); color: var(--muted);
            }
            .keys-table td {
                padding: 9px 12px; border-bottom: 1px solid var(--ink); vertical-align: middle;
            }
            .keys-table tr:last-child td { border-bottom: 0; }
            .keys-table code {
                font-family: var(--font-mono); font-size: 10.5px; font-weight: 800;
                background: var(--ink); color: var(--paper); padding: 2px 6px;
                text-transform: uppercase; letter-spacing: 0.04em;
            }
            .keys-table samp {
                font-family: var(--font-mono); font-size: 10px; color: var(--muted);
                text-transform: uppercase; letter-spacing: 0.04em;
            }
            .btn-reset {
                padding: 5px 10px; border: 1px solid var(--ink); background: var(--paper); color: var(--ink);
                font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                text-transform: uppercase; letter-spacing: 0.08em; cursor: pointer;
            }
            .btn-reset:hover { background: var(--red); color: var(--paper); border-color: var(--red); }
            /* config section */
            .config-section { border-top: 2px solid var(--ink); }
            .config-head {
                display: flex; align-items: center; justify-content: space-between;
                padding: 12px 20px; background: #ebe9e3;
                font-family: var(--font-mono); font-size: 11px; font-weight: 800;
                text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted);
            }
            .config-table table {
                width: 100%; border-collapse: collapse;
                font-family: var(--font-mono); font-size: 11px;
            }
            .config-table th {
                text-align: left; padding: 8px 12px;
                font-size: 9px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                background: var(--paper); border-bottom: 1px solid var(--ink); color: var(--muted);
            }
            .config-table td {
                padding: 8px 12px; border-bottom: 1px solid var(--ink); vertical-align: middle;
            }
            .config-table tr:last-child td { border-bottom: 0; }
            .config-table code {
                font-family: var(--font-mono); font-size: 10.5px; font-weight: 800;
                background: var(--ink); color: var(--paper); padding: 2px 6px;
                text-transform: uppercase; letter-spacing: 0.04em;
            }
            .empty {
                padding: 48px 20px; text-align: center;
                font-family: var(--font-mono); font-size: 12px; font-weight: 700;
                color: var(--muted); text-transform: uppercase; letter-spacing: 0.08em;
            }
        </style>
        <div class="layout" data-ref="root">
            <keel-hero data-ref="hero"></keel-hero>
            <div data-ref="chains"></div>
        </div>`}afterMount(){this.refs.hero.render({label:"Upstream Management",title:"Pool Chains",metaHtml:""})}async refresh(){try{const[e,t]=await Promise.all([v(`${f.airelay}/admin/pools`),v(`${f.airelay}/admin/config`)]);this._render(e.chains||[],t)}catch(e){this.refs.chains.innerHTML=`<div class="empty">Failed to load: ${k(e.message)}</div>`}}_render(e,t){var a,s,i;if(this.refs.hero.render({label:"Upstream Management",title:"Pool Chains",metaHtml:`<div class="hero-stat">${e.length}</div>`}),!e.length&&!((a=t==null?void 0:t.chains)!=null&&a.length)){this.refs.chains.innerHTML='<div class="empty">No pool chains configured.</div>';return}let r="";if((s=t==null?void 0:t.chains)!=null&&s.length){const o=t.chains.flatMap(d=>(d.levels||[]).flatMap(h=>h.keys||[]));r+=`<div class="card">
                <header class="card-head">
                    <h3 class="card-title">Provider Configuration</h3>
                    <div class="card-head-right">
                        ${M(`${t.chains.length} chains`)}
                        ${M(`${o.length} keys`)}
                    </div>
                </header>`,t.chains.forEach(d=>{r+=`<div class="config-section">
                    <div class="config-head">
                        <span>${k(d.chainId)}</span>
                        <span>Models: ${(d.modelAliases||[]).join(", ")||"—"}</span>
                    </div>
                    <div class="config-table" data-cfg="${k(d.chainId)}"></div>
                </div>`}),r+="</div>"}e.forEach(o=>{const d=o.levels||[],h=d.flatMap(n=>n.keys||[]);h.filter(n=>n.status==="HEALTHY").length,r+=`<div class="card">
                <header class="card-head">
                    <h3 class="card-title">${k(o.chainId)}</h3>
                    <div class="card-head-right">
                        ${(o.modelAliases||[]).map(n=>M(n)).join("")||M("no aliases")}
                        ${M(`${d.length} levels`)}
                        ${M(`${h.length} keys`)}
                    </div>
                </header>
                <div class="card-body">`,d.length||(r+='<div class="empty">No levels configured.</div>'),d.forEach(n=>{const p=[];n.healthyKeys>0&&p.push(`<span class="st st-ok">${n.healthyKeys} healthy</span>`),n.cooldownKeys>0&&p.push(`<span class="st st-warn">${n.cooldownKeys} cooldown</span>`),n.degradedKeys>0&&p.push(`<span class="st st-warn">${n.degradedKeys} degraded</span>`),n.disabledKeys>0&&p.push(`<span class="st st-bad">${n.disabledKeys} disabled</span>`),r+=`<div class="level">
                    <div class="level-top">
                        <div class="lvl-idx">L${n.levelIndex}</div>
                        <div class="lvl-main">
                            <div class="lvl-name">${k(n.levelId)}</div>
                            <div class="lvl-meta">
                                <span>Provider: <code>${k(n.providerId)}</code></span>
                                <span>Protocol: <code>${k(n.protocol)}</code></span>
                            </div>
                        </div>
                        <div class="lvl-chips">${p.join("")}</div>
                    </div>
                    <div class="keys-table" data-hl="${k(o.chainId)}-${k(n.levelId)}"></div>
                </div>`}),r+="</div></div>"}),this.refs.chains.innerHTML=r,(i=t==null?void 0:t.chains)!=null&&i.length&&t.chains.forEach(o=>{const d=this.shadowRoot.querySelector(`[data-cfg="${CSS.escape(o.chainId)}"]`);if(!d)return;const h=(o.levels||[]).flatMap(n=>(n.keys||[]).map(p=>{var u,b,x;return[M(`L${n.levelIndex}`),`<code>${k(((u=n.provider)==null?void 0:u.providerId)||"")}</code>`,`<code>${k(((b=n.provider)==null?void 0:b.protocol)||"")}</code>`,`<samp>${k(((x=n.provider)==null?void 0:x.baseUrl)||"")}</samp>`,`<code>${k(p.keyId)}</code>`,`<span>${p.maxConcurrency||10}</span>`,`<span>${p.weight||100}</span>`]}));d.innerHTML=h.length?`<table>
                        <thead><tr><th>Level</th><th>Provider</th><th>Protocol</th><th>Base URL</th><th>Key ID</th><th>Max Concurrency</th><th>Weight</th></tr></thead>
                        <tbody>${h.map(n=>`<tr>${n.map(p=>`<td>${p}</td>`).join("")}</tr>`).join("")}</tbody>
                    </table>`:'<div class="empty">No keys configured.</div>'}),e.forEach(o=>{(o.levels||[]).forEach(d=>{const h=this.shadowRoot.querySelector(`[data-hl="${CSS.escape(o.chainId)}-${CSS.escape(d.levelId)}"]`);if(!h)return;const n=d.keys||[];if(!n.length){h.innerHTML='<div class="empty">No keys.</div>';return}h.innerHTML=`<table>
                    <thead><tr><th>Key ID</th><th>Status</th><th>Requests</th><th>Failures</th><th>Concurrency</th><th>Last Error</th><th></th></tr></thead>
                    <tbody>${n.map(p=>{const u=K[p.status]||K.HEALTHY;return`<tr>
                            <td><code>${k(p.keyId)}</code></td>
                            <td><span class="st ${u.cls}">${u.label}</span></td>
                            <td><span>${p.totalRequests||0}</span></td>
                            <td><span>${p.totalFailures||0}</span></td>
                            <td><span>${p.currentConcurrency||0}</span></td>
                            <td><samp>${k(p.lastError||"none")}</samp></td>
                            <td>${p.status!=="HEALTHY"?`<button class="btn-reset" data-rc="${k(o.chainId)}" data-rk="${k(p.keyId)}">Reset</button>`:""}</td>
                        </tr>`}).join("")}</tbody>
                </table>`})}),this.shadowRoot.querySelectorAll("[data-rk]").forEach(o=>{o.addEventListener("click",async()=>{try{await w(`${f.airelay}/admin/pools/${o.dataset.rc}/keys/${o.dataset.rk}/reset`,{}),this.refresh()}catch(d){alert(d.message)}})})}}customElements.define("ai-panel-pools",ue);class ve extends y{hostStyles(){return"height:100%;"}template(){return`
            <style>
                .panel-layout { display: flex; flex-direction: column; gap: 28px; }
                .section-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 28px;
                    box-shadow: var(--shadow-sm);
                    border: 2px solid var(--ink);
                }
                .section-title {
                    font-family: var(--font-headline);
                    font-size: 20px;
                    font-weight: 500;
                    margin: 0 0 20px;
                    color: var(--ink);
                }
                .toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
                .btn-primary {
                    padding: 10px 20px; border: 0; border-radius: 0;
                    background: var(--navy); color: #f8fafc; font-size: 11px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; cursor: pointer; transition: background 200ms ease;
                }
                .btn-primary:hover { background: var(--navy-2); }
                .btn-ghost {
                    padding: 8px 16px; border: 1px solid var(--line-strong); border-radius: 0;
                    background: transparent; color: var(--ink); font-size: 10px; font-weight: 700;
                    letter-spacing: 0.08em; text-transform: uppercase; cursor: pointer; transition: all 150ms ease;
                }
                .btn-ghost:hover { background: var(--panel-strong); }
                .btn-danger-ghost {
                    padding: 8px 16px; border: 1px solid var(--red); border-radius: 0;
                    background: transparent; color: var(--red); font-size: 10px; font-weight: 700;
                    letter-spacing: 0.08em; text-transform: uppercase; cursor: pointer;
                }
                .btn-danger-ghost:hover { background: var(--red-soft); }
                .btn-sm {
                    padding: 5px 12px; border: 1px solid var(--red); border-radius: 0;
                    background: transparent; color: var(--red); font-size: 9px; font-weight: 700;
                    text-transform: uppercase; letter-spacing: 0.06em; cursor: pointer;
                }
                .form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 20px; }
                .field label {
                    display: block; font-size: 10px; font-weight: 800; text-transform: uppercase;
                    letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px;
                }
                .field input, .field select {
                    width: 100%; padding: 10px 12px; border: 0; border-radius: var(--radius-sm);
                    font-size: 13px; background: var(--color-surface-container-high, #e4e2dc); color: var(--ink);
                    transition: all 150ms ease;
                }
                .field input:focus, .field select:focus {
                    outline: none; box-shadow: var(--shadow-sm); background: var(--color-surface-container-lowest, #fff);
                }
                .form-actions { display: flex; gap: 12px; }
                .summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
                .summary-item {
                    background: var(--color-surface-container-lowest, #fff);
                    border-radius: var(--radius-sm);
                    padding: 18px 20px;
                    border: 2px solid var(--ink);
                }
                .summary-label {
                    font-size: 10px; font-weight: 800; text-transform: uppercase;
                    letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px;
                }
                .summary-val {
                    font-family: var(--font-headline); font-size: 28px; line-height: 0.95;
                    letter-spacing: -0.04em; color: var(--ink);
                }
                .empty { text-align: center; color: var(--muted); padding: 40px; font-size: 13px; }
            </style>
            <div class="panel-layout">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="summary-grid" data-ref="summary"></div>
                <div class="section-card">
                    <div class="toolbar">
                        <h3 class="section-title" style="margin:0;">Rules</h3>
                        <div style="display:flex;gap:10px;">
                            <button class="btn-primary" data-ref="addRuleBtn">Add Rule</button>
                            <button class="btn-danger-ghost" data-ref="resetAllBtn">Reset All Buckets</button>
                        </div>
                    </div>
                    <div class="section-card" data-ref="ruleForm" hidden style="background:var(--color-surface-container-lowest,#fff);margin-bottom:20px;">
                        <h3 class="section-title">New Rule</h3>
                        <div class="form-grid">
                            <div class="field"><label>Name</label><input data-ref="fName" placeholder="Rule name"></div>
                            <div class="field"><label>Dimension</label>
                                <select data-ref="fDimension"><option>IP</option><option>USER</option><option>API_KEY</option><option>MODEL</option><option>GLOBAL</option></select>
                            </div>
                            <div class="field"><label>Path Pattern</label><input data-ref="fPath" value="/v1/*"></div>
                            <div class="field"><label>Capacity</label><input data-ref="fCapacity" type="number" value="120"></div>
                            <div class="field"><label>Refill Rate / sec</label><input data-ref="fRate" type="number" step="0.1" value="2"></div>
                            <div class="field"><label>Priority</label><input data-ref="fPriority" type="number" value="0"></div>
                        </div>
                        <div class="form-actions">
                            <button class="btn-primary" data-ref="submitRuleBtn">Create Rule</button>
                            <button class="btn-ghost" data-ref="cancelRuleBtn">Cancel</button>
                        </div>
                    </div>
                    <keel-data-table data-ref="rulesTable"></keel-data-table>
                </div>
                <div class="section-card">
                    <h3 class="section-title">Active Buckets (Top 20)</h3>
                    <keel-data-table data-ref="bucketsTable"></keel-data-table>
                </div>
            </div>
        `}afterMount(){this.refs.hero.render({label:"Traffic Control",title:"Rate Limits",metaHtml:""}),this.refs.addRuleBtn.addEventListener("click",()=>{this.refs.ruleForm.hidden=!1}),this.refs.cancelRuleBtn.addEventListener("click",()=>{this.refs.ruleForm.hidden=!0}),this.refs.submitRuleBtn.addEventListener("click",async()=>{try{await w(`${f.riskcontrol}/v1/rules`,{name:this.refs.fName.value||"Rule",dimension:this.refs.fDimension.value,pathPattern:this.refs.fPath.value||"/v1/*",capacity:parseInt(this.refs.fCapacity.value)||120,refillRatePerSec:parseFloat(this.refs.fRate.value)||2,priority:parseInt(this.refs.fPriority.value)||0}),this.refs.ruleForm.hidden=!0,this.refresh()}catch(e){alert(e.message)}}),this.refs.resetAllBtn.addEventListener("click",async()=>{if(confirm("Reset all rate limit buckets?"))try{await w(`${f.riskcontrol}/v1/reset`,{}),this.refresh()}catch(e){alert(e.message)}})}async refresh(){try{const[e,t]=await Promise.all([v(`${f.riskcontrol}/v1/rules`),v(`${f.riskcontrol}/v1/snapshot`)]);this._render(e.rules||[],t)}catch(e){this.refs.rulesTable.render({headers:[],rows:[],emptyHtml:`<div class="empty">Failed: ${e.message}</div>`})}}_render(e,t){this.refs.summary.innerHTML=[["Rules",t.ruleCount||0],["Active Buckets",t.bucketCount||0],["Total Allowed",(t.totalAllowed||0).toLocaleString()],["Total Rejected",(t.totalRejected||0).toLocaleString()]].map(([a,s])=>`
            <div class="summary-item">
                <div class="summary-label">${a}</div>
                <div class="summary-val">${s}</div>
            </div>
        `).join(""),this.refs.rulesTable.render({silent:!!this._hasRendered,headers:["Name","Dimension","Path","Capacity","Refill/s","Priority","Enabled","Action"],rows:e.map(a=>[l(a.name||a.ruleId),`<code>${a.dimension}</code>`,`<code>${a.pathPattern}</code>`,String(a.capacity),String(a.refillRatePerSec),String(a.priority),a.enabled?'<span style="color:var(--green);font-weight:700;font-size:10px;text-transform:uppercase;">ON</span>':'<span style="color:var(--red);font-weight:700;font-size:10px;text-transform:uppercase;">OFF</span>',`<button class="btn-sm" data-del-rule="${a.ruleId}">Delete</button>`]),emptyHtml:'<div class="empty">No rules configured.</div>'}),this.refs.rulesTable.shadowRoot.querySelectorAll("[data-del-rule]").forEach(a=>{a.addEventListener("click",async()=>{if(confirm("Delete this rule?"))try{await S(`${f.riskcontrol}/v1/rules/${a.dataset.delRule}`),this.refresh()}catch(s){alert(s.message)}})});const r=t.topBuckets||[];this.refs.bucketsTable.render({silent:!!this._hasRendered,headers:["Rule","Dimension","Value","Allowed","Rejected","Remaining","Capacity"],rows:r.map(a=>[`<code>${a.ruleId}</code>`,a.dimension,`<code>${a.value}</code>`,String(a.totalAllowed||0),String(a.totalRejected||0),String(a.remainingTokens??"-"),String(a.capacity??"-")]),emptyHtml:'<div class="empty">No active buckets.</div>'}),this._hasRendered=!0}}customElements.define("ai-panel-rate-limits",ve);class ge extends y{hostStyles(){return"height:100%;"}template(){return`
            <style>
                .panel-layout { display: flex; flex-direction: column; gap: 28px; }
                .section-card {
                    background: var(--panel-strong);
                    border-radius: var(--radius-lg);
                    padding: 28px;
                    box-shadow: var(--shadow-sm);
                    border: 2px solid var(--ink);
                }
                .section-title {
                    font-family: var(--font-headline);
                    font-size: 20px;
                    font-weight: 500;
                    margin: 0 0 20px;
                    color: var(--ink);
                }
                .toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
                .btn-primary {
                    padding: 10px 20px; border: 0; border-radius: 0;
                    background: var(--navy); color: #f8fafc; font-size: 11px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase; cursor: pointer;
                }
                .btn-primary:hover { background: var(--navy-2); }
                .btn-action {
                    padding: 5px 12px; border: 1px solid var(--line-strong); border-radius: 0;
                    background: transparent; color: var(--ink); font-size: 9px; font-weight: 700;
                    text-transform: uppercase; letter-spacing: 0.06em; cursor: pointer;
                }
                .btn-action:hover { background: var(--panel-strong); }
                .btn-action.danger { border-color: var(--red); color: var(--red); }
                .btn-action.danger:hover { background: var(--red-soft); }
                .btn-action.success { border-color: var(--green); color: var(--green); }
                .btn-action.success:hover { background: var(--green-soft); }
                .btn-ghost {
                    padding: 10px 18px; border: 1px solid var(--line-strong); border-radius: 0;
                    background: transparent; color: var(--ink); font-size: 10px; font-weight: 700;
                    letter-spacing: 0.08em; text-transform: uppercase; cursor: pointer;
                }
                .btn-ghost:hover { background: var(--panel-strong); }
                .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; }
                .field label {
                    display: block; font-size: 10px; font-weight: 800; text-transform: uppercase;
                    letter-spacing: 0.14em; color: var(--muted); margin-bottom: 8px;
                }
                .field input {
                    width: 100%; padding: 10px 12px; border: 0; border-radius: var(--radius-sm);
                    font-size: 13px; background: var(--color-surface-container-high, #e4e2dc); color: var(--ink);
                    transition: all 150ms ease;
                }
                .field input:focus {
                    outline: none; box-shadow: var(--shadow-sm); background: var(--color-surface-container-lowest, #fff);
                }
                .form-actions { display: flex; gap: 12px; }
                .empty { text-align: center; color: var(--muted); padding: 40px; font-size: 13px; }
            </style>
            <div class="panel-layout">
                <keel-hero data-ref="hero"></keel-hero>
                <div class="section-card">
                    <h3 class="section-title">Users</h3>
                    <keel-data-table data-ref="usersTable"></keel-data-table>
                </div>
                <div class="section-card">
                    <div class="toolbar">
                        <h3 class="section-title" style="margin:0;">User Groups</h3>
                        <button class="btn-primary" data-ref="addGroupBtn">Create Group</button>
                    </div>
                    <div class="section-card" data-ref="groupForm" hidden style="background:var(--color-surface-container-lowest,#fff);margin-bottom:20px;">
                        <h3 class="section-title">New Group</h3>
                        <div class="form-grid">
                            <div class="field"><label>Group ID</label><input data-ref="gId" placeholder="group-id"></div>
                            <div class="field"><label>Name</label><input data-ref="gName" placeholder="Group name"></div>
                            <div class="field"><label>Cost Multiplier</label><input data-ref="gMultiplier" type="number" step="0.1" value="1.0"></div>
                            <div class="field"><label>Default Budget (USD)</label><input data-ref="gBudget" type="number" value="10"></div>
                        </div>
                        <div class="form-actions">
                            <button class="btn-primary" data-ref="submitGroupBtn">Create</button>
                            <button class="btn-ghost" data-ref="cancelGroupBtn">Cancel</button>
                        </div>
                    </div>
                    <keel-data-table data-ref="groupsTable"></keel-data-table>
                </div>
            </div>
        `}afterMount(){this.refs.hero.render({label:"Access Control",title:"Users & Groups",metaHtml:""}),this.refs.addGroupBtn.addEventListener("click",()=>{this.refs.groupForm.hidden=!1}),this.refs.cancelGroupBtn.addEventListener("click",()=>{this.refs.groupForm.hidden=!0}),this.refs.submitGroupBtn.addEventListener("click",async()=>{try{await w(`${f.account}/admin/groups`,{groupId:this.refs.gId.value,name:this.refs.gName.value,costMultiplier:parseFloat(this.refs.gMultiplier.value)||1,defaultBudgetUsd:parseFloat(this.refs.gBudget.value)||10}),this.refs.groupForm.hidden=!0,this.refresh()}catch(e){alert(e.message)}})}async refresh(){try{const[e,t]=await Promise.all([v(`${f.account}/admin/users`),v(`${f.account}/admin/groups`)]);this._renderUsers(e.users||[]),this._renderGroups(t.groups||[])}catch(e){this.refs.usersTable.render({headers:[],rows:[],emptyHtml:`<div class="empty">Failed: ${e.message}</div>`})}}_renderUsers(e){this.refs.usersTable.render({silent:!!this._hasUsersRendered,headers:["User ID","Email","Display Name","Role","Group","Status","Action"],rows:e.map(t=>[`<code style="font-size:11px;">${t.userId}</code>`,l(t.email),l(t.displayName),t.role==="admin"?'<span style="color:var(--navy);font-weight:700;font-size:10px;text-transform:uppercase;">Admin</span>':'<span style="color:var(--muted);font-weight:700;font-size:10px;text-transform:uppercase;">User</span>',t.groupId,t.status==="active"?'<span style="color:var(--green);font-weight:700;font-size:10px;text-transform:uppercase;">Active</span>':'<span style="color:var(--red);font-weight:700;font-size:10px;text-transform:uppercase;">Suspended</span>',t.status==="active"?`<button class="btn-action danger" data-suspend="${t.userId}">Suspend</button>`:`<button class="btn-action success" data-activate="${t.userId}">Activate</button>`]),emptyHtml:'<div class="empty">No users found.</div>'}),this._hasUsersRendered=!0,this.refs.usersTable.shadowRoot.querySelectorAll("[data-suspend]").forEach(t=>{t.addEventListener("click",async()=>{try{await w(`${f.account}/admin/users/${t.dataset.suspend}/suspend`,{}),this.refresh()}catch(r){alert(r.message)}})}),this.refs.usersTable.shadowRoot.querySelectorAll("[data-activate]").forEach(t=>{t.addEventListener("click",async()=>{try{await w(`${f.account}/admin/users/${t.dataset.activate}/activate`,{}),this.refresh()}catch(r){alert(r.message)}})})}_renderGroups(e){this.refs.groupsTable.render({silent:!!this._hasGroupsRendered,headers:["Group ID","Name","Cost Multiplier","Default RPM","Default TPM","Budget"],rows:e.map(t=>[`<code>${t.groupId}</code>`,l(t.name),String(t.costMultiplier),t.defaultRpm??"unlimited",t.defaultTpm??"unlimited",`$${(t.defaultBudgetUsd||0).toFixed(2)}`]),emptyHtml:'<div class="empty">No groups configured.</div>'})}}customElements.define("ai-panel-users",ge);class be extends y{hostStyles(){return""}template(){return`
            <style>
                table { width:100%; border-collapse:collapse; font-family:var(--font-mono); font-size:11px; border:2px solid var(--ink); }
                th,td { padding:10px 14px; text-align:left; border-bottom:1px solid var(--color-surface-container-low, #e5e0d8); }
                th { font-size:9px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; color:var(--muted); background:var(--color-surface-container-low, #ebe9e3); }
                .status { font-weight:800; text-transform:uppercase; letter-spacing:0.06em; }
                .status.active { color:var(--green); }
                .status.locked { color:var(--amber); }
                .status.deleted { color:var(--red); }
                .balance { font-family:var(--font-display); font-size:18px; }
                .empty { padding:30px; text-align:center; font-family:var(--font-mono); font-size:12px; font-weight:800; color:var(--muted); text-transform:uppercase; }
                .toolbar { display:flex; align-items:center; gap:12px; margin-bottom:18px; }
                .toolbar .spacer { flex:1; }
                .btn { padding:7px 11px; border:2px solid var(--ink); background:var(--paper); color:var(--ink); cursor:pointer; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.08em; text-transform:uppercase; }
                .btn:hover { background:var(--ink); color:var(--paper); }
                .overlay { position:fixed; inset:0; background:rgba(11,11,11,0.55); display:none; align-items:center; justify-content:center; z-index:900; }
                .overlay.open { display:flex; }
                .modal { width:min(620px, 94vw); border:2px solid var(--ink); background:var(--paper); box-shadow:var(--shadow-lg); }
                .modal-head { display:flex; justify-content:space-between; align-items:center; padding:14px 18px; background:var(--ink); color:var(--paper); font-family:var(--font-display); font-size:16px; text-transform:uppercase; letter-spacing:-0.04em; }
                .modal-body { padding:18px; display:grid; gap:14px; }
                .field label { display:block; margin-bottom:6px; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.12em; text-transform:uppercase; color:var(--muted); }
                .field input { width:100%; padding:10px 12px; border:2px solid var(--ink); background:var(--paper); color:var(--ink); font-family:var(--font-mono); font-size:12px; }
                .field input[disabled] { opacity:0.9; background:var(--color-surface-container-low, #ebe9e3); }
                .modal-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
                .keys-list { display:grid; gap:8px; }
                .key-item { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:8px 10px; border:1px solid var(--ink); font-family:var(--font-mono); font-size:10px; }
                .key-item code { background:var(--ink); color:var(--paper); padding:2px 6px; }
                .hint { font-family:var(--font-mono); font-size:11px; color:var(--muted); line-height:1.6; }
            </style>
            <keel-hero data-ref="hero"></keel-hero>
            <div class="toolbar">
                <span style="font-family:var(--font-mono);font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:0.1em;color:var(--muted);" data-ref="meta"></span>
                <span class="spacer"></span>
            </div>
            <div data-ref="tableWrap"><div class="empty">Loading...</div></div>
            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span data-ref="modalTitle">Customer Detail</span><button class="btn" data-ref="closeBtn" style="border-color:var(--paper);color:var(--paper);background:transparent;">Close</button></div>
                    <div class="modal-body">
                        <div class="modal-grid">
                            <div class="field"><label>Email</label><input data-ref="email" disabled /></div>
                            <div class="field"><label>Status</label><input data-ref="status" disabled /></div>
                        </div>
                        <div class="modal-grid">
                            <div class="field"><label>Display Name</label><input data-ref="displayName" disabled /></div>
                            <div class="field"><label>Balance Credits</label><input data-ref="balance" disabled /></div>
                        </div>
                        <div class="modal-grid">
                            <div class="field"><label>Total Keys</label><input data-ref="totalKeys" disabled /></div>
                            <div class="field"><label>Created</label><input data-ref="createdAt" disabled /></div>
                        </div>
                        <div class="field"><label>Customer Keys</label><div class="keys-list" data-ref="keysList"><div class="empty">No keys</div></div></div>
                        <div class="hint">This page is intentionally read-only. Management actions like credit adjustment, key revocation, status edits, and deletion are hidden from the primary UI.</div>
                    </div>
                </div>
            </div>
        `}afterMount(){this.refs.hero.render({label:"Customer Directory",title:"Customers",metaHtml:""}),this._selected=null,this.refs.closeBtn.addEventListener("click",()=>this._closeModal()),this.refs.overlay.addEventListener("click",e=>{e.target===this.refs.overlay&&this._closeModal()})}async refresh(){try{const e=await v("/api/plugins/customer-portal/admin/customers");if(this._customers=e.customers||[],this.refs.meta.textContent=`${this._customers.length} customer${this._customers.length!==1?"s":""}`,this.refs.hero.render({label:"Customer Directory",title:"Customers",metaHtml:`<div style="padding:16px 22px;font-family:var(--font-headline);font-size:48px;line-height:0.8;letter-spacing:-0.05em;color:var(--paper);">${this._customers.length}</div>`}),!this._customers.length){this.refs.tableWrap.innerHTML='<div class="empty">No customers yet.</div>';return}this.refs.tableWrap.innerHTML=`
                <table>
                    <thead><tr><th>ID</th><th>Email</th><th>Display Name</th><th>Status</th><th>Balance</th><th>Keys</th><th>Created</th><th>View</th></tr></thead>
                    <tbody>${this._customers.map(t=>`
                        <tr>
                            <td>${l(t.customerId)}</td>
                            <td>${l(t.email)}</td>
                            <td>${l(t.displayName)}</td>
                            <td class="status ${t.status}">${l(t.status)}</td>
                            <td class="balance">${L(t.balanceCredits)}</td>
                            <td>${L(t.totalKeys)}</td>
                            <td>${R(t.createdAt)}</td>
                            <td><button class="btn" data-view="${l(t.customerId)}">View</button></td>
                        </tr>`).join("")}</tbody>
                </table>`,this.shadowRoot.querySelectorAll("[data-view]").forEach(t=>t.addEventListener("click",()=>this._openModal(t.dataset.view)))}catch(e){this.refs.tableWrap.innerHTML=`<div class="empty">${l(e.message)}</div>`}}async _openModal(e){try{const t=await v(`/api/plugins/customer-portal/admin/customers/${encodeURIComponent(e)}`);this._selected=t,this.refs.modalTitle.textContent=`Customer · ${t.customerId}`,this.refs.email.value=t.email||"",this.refs.displayName.value=t.displayName||"",this.refs.status.value=t.status||"",this.refs.balance.value=L(t.balanceCredits||0),this.refs.totalKeys.value=L(t.totalKeys||0),this.refs.createdAt.value=R(t.createdAt||""),this.refs.keysList.innerHTML=(t.keys||[]).length?t.keys.map(r=>`
                <div class="key-item"><div><strong>${l(r.name)}</strong> <code>${l(r.prefix)}</code></div><span>${l(r.status)}</span></div>`).join(""):'<div class="empty">No keys</div>',this.refs.overlay.classList.add("open")}catch(t){alert(t.message)}}_closeModal(){this.refs.overlay.classList.remove("open"),this._selected=null}}customElements.define("ai-panel-customers",be);class xe extends y{hostStyles(){return""}template(){return`
            <style>
                table { width:100%; border-collapse:collapse; font-family:var(--font-mono); font-size:11px; border:2px solid var(--ink); }
                th,td { padding:10px 14px; text-align:left; border-bottom:1px solid var(--color-surface-container-low, #e5e0d8); }
                th { font-size:9px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; color:var(--muted); background:var(--color-surface-container-low, #ebe9e3); }
                .toolbar { display:flex; align-items:center; gap:12px; margin-bottom:18px; }
                .toolbar .spacer { flex:1; }
                .btn {
                    padding:11px 18px; border:2px solid var(--ink); background:var(--ink); color:var(--paper);
                    font-family:var(--font-mono); font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:0.12em; cursor:pointer;
                }
                .btn:hover { background:var(--red); border-color:var(--red); }
                .btn-danger {
                    padding:6px 10px; border:1px solid var(--red); color:var(--red); background:transparent;
                    font-family:var(--font-mono); font-size:9px; font-weight:800; text-transform:uppercase; cursor:pointer;
                }
                .btn-danger:hover { background:var(--red); color:var(--paper); }
                .status { font-weight:800; text-transform:uppercase; letter-spacing:0.06em; }
                .status.active { color:var(--green); }
                .status.redeemed { color:var(--amber); }
                .status.revoked { color:var(--red); }
                .empty { padding:30px; text-align:center; font-family:var(--font-mono); font-size:12px; font-weight:800; color:var(--muted); text-transform:uppercase; }
                .overlay { position:fixed; inset:0; background:rgba(11,11,11,0.55); display:none; align-items:center; justify-content:center; z-index:900; }
                .overlay.open { display:flex; }
                .modal { width:min(420px,92vw); border:2px solid var(--ink); background:var(--paper); box-shadow:var(--shadow-lg); }
                .modal-head { display:flex; justify-content:space-between; padding:14px 18px; background:var(--ink); color:var(--paper); font-family:var(--font-display); font-size:16px; text-transform:uppercase; letter-spacing:-0.04em; }
                .modal-body { padding:18px; display:grid; gap:14px; }
                .field label { display:block; margin-bottom:6px; font-family:var(--font-mono); font-size:10px; font-weight:800; letter-spacing:0.14em; text-transform:uppercase; color:var(--muted); }
                .field input { width:100%; padding:10px 12px; border:2px solid var(--ink); font-family:var(--font-mono); font-size:13px; }
                .error { display:none; padding:10px 14px; background:var(--red); color:var(--paper); font-family:var(--font-mono); font-size:11px; font-weight:800; }
                .error.show { display:block; }
            </style>
            <keel-hero data-ref="hero"></keel-hero>
            <div class="toolbar">
                <span style="font-family:var(--font-mono);font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:0.1em;color:var(--muted);" data-ref="meta"></span>
                <span class="spacer"></span>
                <button class="btn" data-ref="newBtn">+ New Code</button>
            </div>
            <div data-ref="tableWrap"><div class="empty">Loading...</div></div>
            <div class="overlay" data-ref="overlay">
                <div class="modal">
                    <div class="modal-head"><span>Mint Code</span><button class="btn" style="border-color:var(--paper);color:var(--paper);padding:4px 10px;font-size:10px;" data-ref="closeBtn">&#10005;</button></div>
                    <div class="error" data-ref="error"></div>
                    <div class="modal-body">
                        <div class="field"><label>Face Value (credits)</label><input data-ref="fValue" type="number" placeholder="1000" /></div>
                        <div class="field"><label>Custom Code (optional)</label><input data-ref="fCode" placeholder="Auto-generated" /></div>
                        <div class="field"><label>Expires In Days (optional)</label><input data-ref="fExpiry" type="number" placeholder="Never" /></div>
                        <button class="btn" data-ref="saveBtn">Mint Code</button>
                    </div>
                </div>
            </div>
        `}afterMount(){this.refs.hero.render({label:"Redemption Codes",title:"Codes",metaHtml:""}),this.refs.newBtn.addEventListener("click",()=>this.refs.overlay.classList.add("open")),this.refs.closeBtn.addEventListener("click",()=>this.refs.overlay.classList.remove("open")),this.refs.overlay.addEventListener("click",e=>{e.target===this.refs.overlay&&this.refs.overlay.classList.remove("open")}),this.refs.saveBtn.addEventListener("click",()=>this._save())}async refresh(){try{const e=await v("/api/plugins/customer-portal/admin/codes");if(this._codes=e.codes||[],this.refs.meta.textContent=`${this._codes.length} code${this._codes.length!==1?"s":""}`,!this._codes.length){this.refs.tableWrap.innerHTML='<div class="empty">No redemption codes yet.</div>';return}this.refs.tableWrap.innerHTML=`
                <table>
                    <thead><tr><th>Code</th><th>Value</th><th>Status</th><th>Redeemed By</th><th>Expires</th><th>Created</th><th></th></tr></thead>
                    <tbody>${this._codes.map(t=>`
                        <tr>
                            <td><strong>${l(t.code)}</strong></td>
                            <td>${L(t.faceValueCredits)}</td>
                            <td class="status ${t.status}">${t.status}</td>
                            <td>${t.redeemedByCustomerId||"—"}</td>
                            <td>${t.expiresAt?R(t.expiresAt):"—"}</td>
                            <td>${R(t.createdAt)}</td>
                            <td>${t.status==="active"?`<button class="btn-danger" data-revoke="${l(t.code)}">Revoke</button>`:""}</td>
                        </tr>
                    `).join("")}</tbody>
                </table>`,this.shadowRoot.querySelectorAll("[data-revoke]").forEach(t=>{t.addEventListener("click",()=>this._revoke(t.dataset.revoke))})}catch(e){this.refs.tableWrap.innerHTML=`<div class="empty">${l(e.message)}</div>`}}async _save(){const e=parseInt(this.refs.fValue.value);if(!e||e<=0){this._error("Face value must be positive.");return}const t=this.refs.fCode.value.trim()||null,r=this.refs.fExpiry.value?parseInt(this.refs.fExpiry.value):null;this.refs.saveBtn.disabled=!0;try{await w("/api/plugins/customer-portal/admin/codes",{faceValueCredits:e,code:t,expiresInDays:r}),this.refs.overlay.classList.remove("open"),this.refresh()}catch(a){this._error(a.message)}this.refs.saveBtn.disabled=!1}async _revoke(e){if(confirm(`Revoke redemption code ${e}?`))try{await S(`/api/plugins/customer-portal/admin/codes/${encodeURIComponent(e)}`),this.refresh()}catch(t){alert(t.message)}}_error(e){this.refs.error.textContent=e,this.refs.error.classList.add("show")}}customElements.define("ai-panel-redemption-codes",xe);const ye={dashboard:'<path d="M3 3v18h18"/><path d="m7 14 3-3 3 2 4-6"/>',usage:'<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>',pricing:'<line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>',key:'<path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>',dns:'<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',hub:'<circle cx="12" cy="12" r="3"/><circle cx="5" cy="5" r="2"/><circle cx="19" cy="5" r="2"/><circle cx="5" cy="19" r="2"/><circle cx="19" cy="19" r="2"/><line x1="7" y1="7" x2="10" y2="10"/><line x1="17" y1="7" x2="14" y2="10"/><line x1="7" y1="17" x2="10" y2="14"/><line x1="17" y1="17" x2="14" y2="14"/>',speed:'<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',group:'<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',terminal:'<polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/>',gift:'<polyline points="20 12 20 22 4 22 4 12"/><rect x="2" y="7" width="20" height="5"/><line x1="12" y1="22" x2="12" y2="7"/><path d="M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7z"/><path d="M12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z"/>'};class ke extends y{hostStyles(){return"display:block;height:100vh;"}template(){return`
            <style>
                :host {
                    --font-headline: "Archivo Black", "Helvetica Neue", Arial, sans-serif;
                    --font-body: "Archivo", "Helvetica Neue", Arial, sans-serif;
                    --font-mono: "JetBrains Mono", ui-monospace, monospace;
                }
                .app-shell {
                    display: grid;
                    grid-template-columns: 318px minmax(0, 1fr);
                    height: 100vh;
                    background: var(--surface-accent);
                    transition: grid-template-columns 200ms var(--ease-smooth);
                }
                .app-shell.collapsed { grid-template-columns: 64px minmax(0, 1fr); }
                .sidebar {
                    height: 100vh;
                    padding: 22px 16px;
                    background: var(--surface-soft);
                    border-right: 2px solid var(--ink);
                    display: flex;
                    flex-direction: column;
                    gap: 18px;
                    overflow-y: auto;
                    overflow-x: hidden;
                    transition: padding 200ms;
                }
                .collapsed .sidebar { padding: 22px 12px 12px; gap: 14px; }
                /* Brand — clickable as a whole to toggle collapse; chevron is a visual affordance */
                .brand {
                    padding: 4px 8px;
                    display: flex; align-items: flex-start; justify-content: space-between;
                    gap: 10px;
                    cursor: pointer;
                    user-select: none;
                    transition: background 120ms;
                }
                .brand:hover { background: var(--surface-muted); }
                .collapsed .brand { padding: 0; justify-content: center; }
                .collapsed .brand:hover { background: transparent; }
                .brand-copy { min-width: 0; flex: 1; }
                .collapsed .brand-copy { display: none; }
                .brand h1 {
                    margin: 0;
                    font-family: var(--font-headline);
                    font-size: 26px;
                    line-height: 0.92;
                    letter-spacing: -0.05em;
                    text-transform: uppercase;
                }
                .brand p {
                    margin: 6px 0 0;
                    color: var(--muted);
                    font-family: var(--font-mono);
                    font-size: 9px;
                    font-weight: 800;
                    letter-spacing: 0.16em;
                    text-transform: uppercase;
                }
                .sidebar-toggle {
                    width: 30px; height: 30px;
                    border: 2px solid var(--ink);
                    background: var(--surface-soft);
                    color: var(--ink);
                    cursor: pointer;
                    display: inline-flex; align-items: center; justify-content: center;
                    flex-shrink: 0;
                    transition: background 120ms, color 120ms, transform 200ms;
                }
                .sidebar-toggle:hover { background: var(--surface-accent); color: var(--on-accent); }
                .sidebar-toggle svg { width: 13px; height: 13px; stroke: currentColor; fill: none; stroke-width: 2.5; }
                .collapsed .sidebar-toggle { transform: rotate(180deg); }

                /* Section labels */
                .nav-section-label {
                    padding: 10px 10px 4px;
                    font-family: var(--font-mono);
                    font-size: 9px; font-weight: 800;
                    letter-spacing: 0.2em; text-transform: uppercase;
                    color: var(--muted);
                }
                .collapsed .nav-section-label { font-size: 0; height: 8px; padding: 4px 0; overflow: hidden; }

                /* Nav list */
                .nav-list { display: grid; gap: 4px; }
                .nav-link {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                    padding: 10px 12px;
                    color: var(--ink);
                    cursor: pointer;
                    text-decoration: none;
                    border: 2px solid transparent;
                    background: transparent;
                    transition: background 150ms, border-color 150ms, color 150ms;
                    position: relative;
                }
                .nav-link:hover { background: var(--surface-muted); }
                .nav-link.is-active {
                    background: var(--surface-accent);
                    color: var(--on-accent);
                    box-shadow: 4px 4px 0 0 var(--teal);
                }
                .nav-icon {
                    width: 30px; height: 30px;
                    border: 2px solid var(--ink);
                    background: var(--surface-soft);
                    color: var(--ink);
                    display: inline-flex; align-items: center; justify-content: center;
                    flex-shrink: 0;
                    transition: border-color 120ms, color 120ms, background 120ms;
                }
                .nav-icon svg { width: 15px; height: 15px; }
                .nav-link.is-active .nav-icon { background: var(--surface-strong); border-color: var(--ink); color: var(--ink); }
                .nav-copy { min-width: 0; flex: 1; overflow: hidden; }
                .nav-copy strong {
                    display: block;
                    font-family: var(--font-mono);
                    font-size: 12px; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase;
                    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
                }
                .nav-copy span {
                    display: block; margin-top: 3px;
                    font-family: var(--font-mono);
                    font-size: 9.5px; letter-spacing: 0.04em; opacity: 0.7; text-transform: uppercase;
                    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
                }
                .nav-badge {
                    display: inline-flex; align-items: center; justify-content: center;
                    min-width: 22px; height: 18px; padding: 0 6px;
                    background: var(--surface-strong); color: var(--ink);
                    font-family: var(--font-mono); font-size: 9px; font-weight: 800;
                    letter-spacing: 0.04em;
                    border: 2px solid var(--ink);
                }
                .nav-link.is-active .nav-badge { background: var(--teal); color: var(--on-accent); border-color: var(--teal); }
                .collapsed .nav-badge { display: none; }

                /* Collapsed: 56px square icon-only cells, sidebar is 64px wide with 4px padding */
                .collapsed .nav-link { width: 40px; height: 40px; padding: 0; justify-content: center; gap: 0; margin: 4px auto; }
                .collapsed .nav-copy { display: none; }
                .collapsed .nav-icon { width: 28px; height: 28px; }
                /* Collapsed toggle: show favicon (per observability pattern) */
                .collapsed .sidebar-toggle { width: 40px; height: 40px; padding: 0; border: 0; background: transparent; }
                .collapsed .sidebar-toggle:hover { background: var(--surface-muted); }
                .collapsed .sidebar-toggle svg { width: 18px; height: 18px; stroke-width: 2; }

                /* Sidebar footer */
                .sidebar-footer { margin-top: auto; display: grid; gap: 8px; }
                .user-pill {
                    display: flex; align-items: center; gap: 10px;
                    padding: 10px 12px;
                    background: var(--surface-muted);
                    border: 2px solid var(--ink);
                    font-family: var(--font-mono);
                    font-size: 11px; font-weight: 700; color: var(--ink);
                    text-transform: uppercase; letter-spacing: 0.05em;
                    cursor: pointer;
                    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
                    transition: background 120ms, color 120ms;
                }
                .user-pill:hover { background: var(--surface-accent); color: var(--on-accent); }
                .user-pill:hover .user-dot { background: var(--teal); }
                .user-dot { width: 9px; height: 9px; background: var(--green); flex-shrink: 0; transition: background 120ms; }
                .collapsed .user-pill { width: 40px; height: 40px; padding: 0; justify-content: center; margin: 4px auto; }
                .collapsed .user-pill .user-email { display: none; }
                .collapsed .user-pill .user-dot { width: 14px; height: 14px; }
                .logout-btn {
                    width: 100%; border: 2px solid var(--ink); padding: 10px 14px;
                    background: var(--surface-accent); color: var(--on-accent); cursor: pointer;
                    font-family: var(--font-mono); font-size: 11px; font-weight: 800;
                    letter-spacing: 0.14em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .logout-btn:hover { background: var(--teal); border-color: var(--teal); }
                .collapsed .logout-btn { width: 40px; height: 40px; padding: 0; margin: 4px auto; }
                .collapsed .logout-btn::after { content: "⏻"; font-size: 18px; display: block; text-align: center; }
                .collapsed .logout-btn span { display: none; }

                /* User popover (theme + sign out) */
                .user-popover {
                    position: fixed;
                    background: var(--surface-soft);
                    border: 2px solid var(--ink);
                    box-shadow: 6px 6px 0 0 var(--ink);
                    padding: 14px;
                    display: none;
                    z-index: 50;
                    min-width: 240px;
                }
                .user-popover.is-open { display: block; }
                .popover-section { margin-bottom: 12px; }
                .popover-section:last-of-type { margin-bottom: 0; }
                .popover-label {
                    font-family: var(--font-mono);
                    font-size: 9px; font-weight: 800;
                    letter-spacing: 0.16em; text-transform: uppercase;
                    color: var(--muted);
                    margin-bottom: 6px;
                }
                .popover-email {
                    font-family: var(--font-mono);
                    font-size: 11px; font-weight: 700;
                    word-break: break-all;
                }
                .theme-toggle {
                    display: grid;
                    grid-template-columns: 1fr 1fr 1fr;
                    gap: 0;
                    border: 2px solid var(--ink);
                }
                .theme-toggle button {
                    background: var(--surface-soft);
                    color: var(--ink);
                    border: 0;
                    border-right: 2px solid var(--ink);
                    padding: 8px 0;
                    font-family: var(--font-mono);
                    font-size: 10px; font-weight: 800;
                    text-transform: uppercase; letter-spacing: 0.1em;
                    cursor: pointer;
                    transition: background 120ms, color 120ms;
                }
                .theme-toggle button:last-child { border-right: 0; }
                .theme-toggle button:hover { background: var(--surface-muted); }
                .theme-toggle button.is-active { background: var(--surface-accent); color: var(--on-accent); }
                .main-shell { min-width: 0; display: flex; flex-direction: column; height: 100vh; overflow: hidden; background: var(--bg); }
                .topbar {
                    flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; gap: 0;
                    padding: 0; background: var(--surface-soft); border-bottom: 2px solid var(--ink); z-index: 10;
                }
                .topbar-sys {
                    align-self: stretch;
                    display: flex; align-items: center; gap: 10px;
                    padding: 14px 20px;
                    background: var(--surface-soft);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: var(--ink);
                }
                .sys-cross { color: var(--teal); font-weight: 800; font-size: 18px; }
                .topbar-actions {
                    align-self: stretch;
                    display: flex; align-items: center; gap: 0;
                    background: var(--surface-soft);
                }
                .sys-stat {
                    align-self: stretch;
                    display: inline-flex; align-items: center; gap: 7px;
                    padding: 0 16px;
                    background: var(--surface-muted);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.12em; color: var(--muted);
                    border-left: 2px solid var(--ink);
                }
                .sys-dot { width: 8px; height: 8px; background: var(--green); }
                .live-indicator {
                    align-self: stretch;
                    display: inline-flex; align-items: center; gap: 6px;
                    padding: 0 16px;
                    background: var(--surface-muted);
                    font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.12em;
                    color: var(--teal); cursor: pointer; user-select: none;
                    border-left: 2px solid var(--ink);
                    transition: color 120ms;
                }
                .live-dot {
                    width: 8px; height: 8px; background: var(--teal); border-radius: 50%;
                    animation: livePulse 2s ease-in-out infinite;
                }
                .live-indicator.paused { color: var(--muted); }
                .live-indicator.paused .live-dot { background: var(--muted); animation: none; }
                @keyframes livePulse { 0%,100%{ opacity:1; } 50%{ opacity:0.3; } }
                .refresh-btn {
                    align-self: stretch;
                    border: 0;
                    border-left: 2px solid var(--ink);
                    background: var(--surface-soft); color: var(--ink); cursor: pointer;
                    padding: 0 18px; font-family: var(--font-mono); font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase;
                    transition: background 120ms, color 120ms;
                }
                .refresh-btn:hover { background: var(--teal); color: var(--paper); }
                .content {
                    padding: 24px 26px 60px;
                    flex: 1 1 0;
                    min-height: 0;
                    overflow-y: auto;
                    overflow-x: hidden;
                    background-image:
                        repeating-linear-gradient(0deg, transparent 0, transparent 39px, rgba(11,11,11,0.035) 39px, rgba(11,11,11,0.035) 40px),
                        repeating-linear-gradient(90deg, transparent 0, transparent 39px, rgba(11,11,11,0.035) 39px, rgba(11,11,11,0.035) 40px);
                }
                [data-theme="dark"] .content {
                    background-image:
                        repeating-linear-gradient(0deg, transparent 0, transparent 39px, rgba(255,255,255,0.02) 39px, rgba(255,255,255,0.02) 40px),
                        repeating-linear-gradient(90deg, transparent 0, transparent 39px, rgba(255,255,255,0.02) 39px, rgba(255,255,255,0.02) 40px);
                }
                .panel { display: none; }
                .panel.is-active { display: block; }
                @keyframes panel-enter { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
                .panel.fallback-enter { animation: panel-enter 200ms var(--ease-smooth); }
                /* Login overlay */
                .login-overlay {
                    position: fixed; inset: 0; background: var(--bg);
                    background-image:
                        repeating-linear-gradient(0deg, transparent, transparent 39px, rgba(11,11,11,0.04) 39px, rgba(11,11,11,0.04) 40px),
                        repeating-linear-gradient(90deg, transparent, transparent 39px, rgba(11,11,11,0.04) 39px, rgba(11,11,11,0.04) 40px);
                    display: flex; align-items: center; justify-content: center; z-index: 1000;
                }
                .login-card { background: var(--surface-soft); padding: 44px; width: 440px; border: 2px solid var(--ink); box-shadow: var(--shadow-lg); }
                .login-card h2 {
                    margin: 0 0 6px; font-family: var(--font-headline); font-size: 38px; line-height: 0.9;
                    letter-spacing: -0.04em; text-transform: uppercase;
                }
                .login-card > p { margin: 0 0 28px; color: var(--muted); font-family: var(--font-mono); font-size: 11px; letter-spacing: 0.06em; text-transform: uppercase; }
                .login-tabs { display: flex; gap: 0; margin-bottom: 26px; border: 2px solid var(--ink); }
                .login-tab {
                    flex: 1; padding: 11px 0; text-align: center; font-family: var(--font-mono);
                    font-size: 11px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.1em;
                    cursor: pointer; color: var(--ink); background: var(--paper); transition: all 120ms ease;
                }
                .login-tab + .login-tab { border-left: 2px solid var(--ink); }
                .login-tab.active { color: var(--on-accent); background: var(--surface-accent); }
                .field { margin-bottom: 18px; }
                .field label { display: block; font-family: var(--font-mono); font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.14em; color: var(--ink); margin-bottom: 8px; }
                .field input {
                    width: 100%; padding: 12px 14px; border: 2px solid var(--ink); font-size: 14px;
                    background: var(--paper); color: var(--ink); transition: all 120ms ease; font-family: var(--font-mono);
                }
                .field input:focus { outline: none; box-shadow: var(--shadow-sm); background: var(--panel-strong, #fff); }
                .login-btn {
                    width: 100%; padding: 15px 0; background: var(--teal); color: var(--paper); border: 2px solid var(--ink);
                    font-family: var(--font-mono); font-size: 12px; font-weight: 800; letter-spacing: 0.14em; text-transform: uppercase;
                    cursor: pointer; margin-top: 12px; transition: all 120ms ease;
                }
                .login-btn:hover { background: var(--surface-accent); }
                .login-error { color: var(--red); font-family: var(--font-mono); font-size: 12px; margin-top: 12px; display: none; font-weight: 700; }
                .register-name { display: none; }
                .onboarding { margin-top: 24px; padding: 16px 18px; background: var(--teal-soft); border: 2px solid var(--ink); font-family: var(--font-mono); font-size: 11px; line-height: 1.7; color: var(--ink); }
                .onboarding code { background: var(--surface-accent); color: var(--on-accent); padding: 2px 6px; font-family: var(--font-mono); font-size: 11px; }
                @media (max-width: 980px) {
                    .app-shell { grid-template-columns: 1fr; }
                    .sidebar { height: auto; border-right: 0; border-bottom: 2px solid var(--ink); }
                }
            </style>
            <div class="login-overlay" data-ref="loginOverlay">
                <div class="login-card">
                    <h2>AI Proxy</h2>
                    <p>Sign in to manage your AI Gateway platform</p>
                    <div class="login-tabs">
                        <div class="login-tab active" data-ref="tabLogin">Sign In</div>
                        <div class="login-tab" data-ref="tabRegister">Register</div>
                    </div>
                    <div class="field register-name" data-ref="nameField">
                        <label>Display Name</label>
                        <input type="text" data-ref="inputName" placeholder="Your name">
                    </div>
                    <div class="field">
                        <label>Email</label>
                        <input type="email" data-ref="inputEmail" placeholder="admin@example.com" value="admin@example.com">
                    </div>
                    <div class="field">
                        <label>Password</label>
                        <input type="password" data-ref="inputPassword" placeholder="Password" value="admin123">
                    </div>
                    <div class="login-error" data-ref="loginError"></div>
                    <button class="login-btn" data-ref="loginBtn">Sign In</button>
                    <div class="onboarding">
                        <strong>Quick Start:</strong> Use <code>admin@example.com</code> / <code>admin123</code> to sign in.
                        Create API keys in the Keys panel, then test channels and models from the routing panels.
                    </div>
                </div>
            </div>
            <div class="app-shell" data-ref="appShell" hidden>
                <aside class="sidebar">
                    <div class="brand" data-ref="brand" title="Click to collapse sidebar">
                        <div class="brand-copy">
                            <h1>Keel</h1>
                            <p>AI Proxy</p>
                        </div>
                        <button class="sidebar-toggle" data-ref="sidebarToggle" title="Toggle sidebar" aria-label="Toggle sidebar"></button>
                    </div>
                    <nav class="nav-list" data-ref="nav"></nav>
                    <div class="sidebar-footer">
                        <div class="user-pill" data-ref="userPill" title="Account & preferences">
                            <span class="user-dot"></span>
                            <span class="user-email" data-ref="userEmail">Signed in</span>
                        </div>
                        <button class="logout-btn" data-ref="logoutBtn"><span>Sign Out</span></button>
                    </div>
                </aside>
                <div class="main-shell">
                    <header class="topbar">
                        <div class="topbar-sys" data-ref="sysLine">
                            <span class="sys-cross">+</span>
                            <span data-ref="sysCrumb">SECTOR / DASHBOARD</span>
                        </div>
                        <div class="topbar-actions">
                            <span class="sys-stat"><span class="sys-dot"></span>ONLINE</span>
                            <span class="live-indicator" data-ref="liveIndicator" title="Click to toggle live updates">
                                <span class="live-dot"></span>LIVE
                            </span>
                            <button class="refresh-btn" data-ref="refreshBtn">↻ REFRESH</button>
                        </div>
                    </header>
                    <main class="content" data-ref="content">
                        <ai-panel-overview class="panel" data-ref="panelOverview"></ai-panel-overview>
                        <ai-panel-dashboard class="panel" data-ref="panelDashboard"></ai-panel-dashboard>
                        <ai-panel-usage class="panel" data-ref="panelUsage"></ai-panel-usage>
                        <ai-panel-availability class="panel" data-ref="panelAvailability"></ai-panel-availability>
                        <ai-panel-providers class="panel" data-ref="panelChannels"></ai-panel-providers>
                        <ai-panel-groups class="panel" data-ref="panelGroups"></ai-panel-groups>
                        <ai-panel-keys class="panel" data-ref="panelKeys"></ai-panel-keys>
                        <ai-panel-pricing class="panel" data-ref="panelPricing"></ai-panel-pricing>
                        <ai-panel-rate-limits class="panel" data-ref="panelRateLimits"></ai-panel-rate-limits>
                        <ai-panel-users class="panel" data-ref="panelUsers"></ai-panel-users>
                        <ai-panel-customers class="panel" data-ref="panelCustomers"></ai-panel-customers>
                        <ai-panel-redemption-codes class="panel" data-ref="panelCodes"></ai-panel-redemption-codes>
                    </main>
                </div>
            </div>
            <div class="user-popover" data-ref="userPopover">
                <div class="popover-section">
                    <div class="popover-label">Signed in as</div>
                    <div class="popover-email" data-ref="popoverEmail">—</div>
                </div>
                <div class="popover-section">
                    <div class="popover-label">Appearance</div>
                    <div class="theme-toggle" data-ref="themeToggle">
                        <button data-theme="auto">Auto</button>
                        <button data-theme="light">Light</button>
                        <button data-theme="dark">Dark</button>
                    </div>
                </div>
            </div>
        `}afterMount(){Y(),B(),this._setupLogin(),this._setupNav(),this._setupSidebar(),this._setupTheme(),this._setupUserPopover(),this._setupLiveIndicator(),this._renderState(),this.refs.refreshBtn.addEventListener("click",()=>this._refreshActive()),window.addEventListener("hashchange",()=>{B(),this._renderState()}),this._reveal(),this._autoRefresh=setInterval(()=>{g.loggedIn&&this._refreshActive()},15e3)}_reveal(){const e=window.gsap;if(!e)return;const t=[this.shadowRoot.querySelector(".brand h1"),...this.shadowRoot.querySelectorAll(".nav-link"),this.shadowRoot.querySelector(".user-pill")].filter(Boolean);t.length!==0&&e.fromTo(t,{autoAlpha:0,y:8},{autoAlpha:1,y:0,duration:.45,ease:"power2.out",stagger:.04})}_setupLogin(){let e=!1;this.refs.tabLogin.addEventListener("click",()=>{e=!1,this.refs.tabLogin.classList.add("active"),this.refs.tabRegister.classList.remove("active"),this.refs.nameField.style.display="none",this.refs.loginBtn.textContent="Sign In"}),this.refs.tabRegister.addEventListener("click",()=>{e=!0,this.refs.tabRegister.classList.add("active"),this.refs.tabLogin.classList.remove("active"),this.refs.nameField.style.display="block",this.refs.loginBtn.textContent="Register"}),this.refs.loginBtn.addEventListener("click",async()=>{const t=this.refs.inputEmail.value.trim(),r=this.refs.inputPassword.value,a=this.refs.inputName.value.trim();this.refs.loginError.style.display="none";try{e?await Q(t,r,a||t.split("@")[0]):await J(t,r),this._renderState()}catch(s){this.refs.loginError.textContent=s.message,this.refs.loginError.style.display="block"}}),this.refs.inputPassword.addEventListener("keydown",t=>{t.key==="Enter"&&this.refs.loginBtn.click()})}_setupNav(){this.refs.nav.addEventListener("click",e=>{const t=e.target.closest("[data-tab-id]");t&&V(t.dataset.tabId)})}_renderState(){var t,r;const e=g.loggedIn;this.refs.loginOverlay.style.display=e?"none":"flex",this.refs.appShell.hidden=!e,e&&(this.refs.userEmail.textContent=((t=g.user)==null?void 0:t.email)||"Signed in",this.refs.popoverEmail&&(this.refs.popoverEmail.textContent=((r=g.user)==null?void 0:r.email)||"Signed in"),this._loadBadges().then(()=>this._renderNav()))}async _loadBadges(){try{const e=await v(`${API.airelay}/admin/nav-counts`);this._navBadges={customers:e.customers||0,redemptionCodes:e.redemptionCodes||0,apiKeys:e.apiKeys||0}}catch{this._navBadges=this._navBadges||{}}}_renderNav(){var i;const e=this._navBadges||{},t={};D.forEach(o=>{const d=o.section||"OTHER";(t[d]=t[d]||[]).push(o)}),this.refs.nav.innerHTML=Object.entries(t).map(([o,d])=>{const h=d.map(n=>{const p=e[n.badge],u=p>0?`<span class="nav-badge">${p}</span>`:"";return`
                <a class="nav-link ${g.activeTab===n.id?"is-active":""}" data-tab-id="${n.id}" href="#${n.id}">
                    <span class="nav-icon">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            ${ye[n.icon]||""}
                        </svg>
                    </span>
                    <span class="nav-copy">
                        <strong>${n.label}</strong>
                        <span>${n.hint||""}</span>
                    </span>
                    ${u}
                </a>
            `}).join("");return`<div class="nav-section-label">${o}</div>${h}`}).join("");const r=D.find(o=>o.id===g.activeTab);this.refs.sysCrumb&&(this.refs.sysCrumb.textContent=`SECTOR / ${((r==null?void 0:r.label)||"Overview").toUpperCase()}`);const a={overview:this.refs.panelOverview,dashboard:this.refs.panelDashboard,usage:this.refs.panelUsage,availability:this.refs.panelAvailability,channels:this.refs.panelChannels,groups:this.refs.panelGroups,keys:this.refs.panelKeys,pricing:this.refs.panelPricing,ratelimits:this.refs.panelRateLimits,customers:this.refs.panelCustomers,codes:this.refs.panelCodes,users:this.refs.panelUsers},s=(i=Object.entries(a).find(([,o])=>o.classList.contains("is-active")))==null?void 0:i[0];Object.entries(a).forEach(([o,d])=>{d.classList.toggle("is-active",o===g.activeTab),o===g.activeTab&&typeof d.refresh=="function"&&d.refresh()}),s!==g.activeTab&&this._animateTabSwitch(a[g.activeTab],s)}_animateTabSwitch(e,t){if(!e)return;const r=window.gsap;if(!r){e.classList.add("fallback-enter"),setTimeout(()=>e.classList.remove("fallback-enter"),260);return}r.fromTo(e,{autoAlpha:0,y:10},{autoAlpha:1,y:0,duration:.32,ease:"power2.out",clearProps:"transform"}),this.refs.sysCrumb&&r.fromTo(this.refs.sysCrumb,{autoAlpha:0,x:-6},{autoAlpha:1,x:0,duration:.25,ease:"power2.out"})}_refreshActive(){const t={overview:this.refs.panelOverview,dashboard:this.refs.panelDashboard,usage:this.refs.panelUsage,availability:this.refs.panelAvailability,channels:this.refs.panelChannels,groups:this.refs.panelGroups,keys:this.refs.panelKeys,pricing:this.refs.panelPricing,ratelimits:this.refs.panelRateLimits,customers:this.refs.panelCustomers,codes:this.refs.panelCodes,users:this.refs.panelUsers}[g.activeTab];t&&typeof t.refresh=="function"&&t.refresh()}_setupSidebar(){const e=this.refs.appShell;localStorage.getItem("keel-sidebar-collapsed")==="1"&&e.classList.add("collapsed"),this._renderToggleIcon();const r=()=>{e.classList.toggle("collapsed"),localStorage.setItem("keel-sidebar-collapsed",e.classList.contains("collapsed")?"1":"0"),this._renderToggleIcon()};this.refs.brand.addEventListener("click",r),this.refs.sidebarToggle.addEventListener("click",a=>{a.stopPropagation(),r()})}_renderToggleIcon(){this.refs.appShell.classList.contains("collapsed")?this.refs.sidebarToggle.innerHTML='<img src="/favicon.svg" alt="" style="width:22px;height:22px;display:block;">':this.refs.sidebarToggle.innerHTML='<svg viewBox="0 0 24 24" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>'}_setupTheme(){const e=localStorage.getItem("keel-theme-pref");this._themePref=e||"auto",this._mediaQuery=window.matchMedia("(prefers-color-scheme: dark)"),this._applyTheme(),this._mediaQuery.addEventListener("change",()=>{this._themePref==="auto"&&this._applyTheme()})}_applyTheme(){const e=this._themePref==="dark"||this._themePref==="auto"&&this._mediaQuery&&this._mediaQuery.matches;document.documentElement.setAttribute("data-theme",e?"dark":"light"),this.refs.themeToggle&&this._updateThemePopoverUI()}setThemePref(e){this._themePref=e,e==="auto"?localStorage.removeItem("keel-theme-pref"):localStorage.setItem("keel-theme-pref",e),this._applyTheme()}_updateThemePopoverUI(){this.refs.themeToggle&&this.refs.themeToggle.querySelectorAll("button").forEach(e=>{e.classList.toggle("is-active",e.dataset.theme===this._themePref)})}_setupUserPopover(){const e=this.refs.userPopover,t=()=>{e.classList.remove("is-open")},r=()=>{this._positionUserPopover(),this._updateThemePopoverUI(),e.classList.add("is-open")};this.refs.userPill.addEventListener("click",a=>{a.stopPropagation(),e.classList.contains("is-open")?t():r()}),this.refs.themeToggle.addEventListener("click",a=>{const s=a.target.closest("button[data-theme]");s&&this.setThemePref(s.dataset.theme)}),this.refs.logoutBtn.addEventListener("click",()=>{j(),this._renderState()}),window.addEventListener("resize",()=>{e.classList.contains("is-open")&&this._positionUserPopover()}),document.addEventListener("click",a=>{e.classList.contains("is-open")&&(e.contains(a.target)||this.refs.userPill.contains(a.target)||t())})}_positionUserPopover(){const e=this.refs.userPopover,t=this.refs.userPill;if(!e||!t)return;const r=t.getBoundingClientRect(),a=Math.max(240,e.offsetWidth||240),s=e.offsetHeight||160;let i=r.right-a;i<12&&(i=12),i+a>window.innerWidth-12&&(i=window.innerWidth-a-12);let o=r.top-s-12;o<12&&(o=r.bottom+12),o+s>window.innerHeight-12&&(o=window.innerHeight-s-12),e.style.left=`${i}px`,e.style.top=`${Math.max(12,o)}px`,e.style.right="auto",e.style.bottom="auto",e.style.width=`${a}px`}_setupLiveIndicator(){this._livePaused=!1,this.refs.liveIndicator.addEventListener("click",()=>{this._livePaused=!this._livePaused,this.refs.liveIndicator.classList.toggle("paused",this._livePaused),this.refs.liveIndicator.querySelector(".live-dot").nextSibling.textContent=this._livePaused?" PAUSED":" LIVE";const e=this.refs.panelDashboard;e&&typeof e.setLiveMode=="function"&&e.setLiveMode(!this._livePaused)})}}customElements.define("ai-proxy-app",ke);
