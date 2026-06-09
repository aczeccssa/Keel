# AI Gateway Frontend Quality Roadmap Step 1 Result

Source spec: `docs/superpowers/specs/2026-06-10-ai-gateway-frontend-quality-roadmap-design.md`

Step completed: Step 1, restore the transported frontend without changing the existing product UI style or dropping existing UI functionality.

## Correction Applied

The first Step 1 attempt was too narrow: it styled the simplified React panels and changed the foundation palette, but it did not preserve the complete pre-existing frontend functionality. That was not acceptable for Step 1.

This corrected Step 1 treats the existing backend static UIs as the functional and visual source of truth, then makes the Vite frontend build serve those full legacy custom-element apps:

- AI Relay Manager Console now builds from the complete existing `ai-gateway-ui` custom-element implementation.
- Customer Portal now builds from the complete existing `customer-portal-ui` custom-element implementation.
- The simplified React panel implementation remains in source for later refactor work, but it is no longer the served Step 1 app entry.
- Step 1 does not redesign the UI. The old industrial/brutalist styling and behavior are preserved until Step 3.

## Scope Completed

- Copied existing static UI JS/CSS into Vite app legacy source directories:
  - `keel-samples/frontend/apps/ai-gateway/src/legacy`
  - `keel-samples/frontend/apps/customer-portal/src/legacy`
- Rewired Vite app entries:
  - `keel-samples/frontend/apps/ai-gateway/index.html` now loads `<ai-proxy-app>` and `/src/legacy/js/app.js`.
  - `keel-samples/frontend/apps/customer-portal/index.html` now loads `<customer-app>` and `/src/legacy/js/app.js`.
- Preserved legacy CSS loading through Vite:
  - `/src/legacy/css/style.css`
- Preserved legacy font/CDN and flicker-free theme bootstrapping behavior from the old static HTML.
- Added Vite dev proxy behavior for both apps so `/api` proxies to `http://localhost:8080` during hot reload.
- Preserved production Vite `base` paths:
  - `/api/plugins/airelay/ui/`
  - `/api/plugins/customer-portal/ui/`

## Functionality Preserved

AI Relay Manager Console legacy panels preserved:

- dashboard,
- usage,
- channels/providers management with add/edit/test/enable-disable/delete controls,
- groups and routing membership controls,
- API keys controls,
- pricing management controls,
- rate-limit controls,
- users and groups controls,
- customers detail view,
- redemption code controls.

Customer Portal legacy panels preserved:

- login/register,
- dashboard,
- API keys create/connect/delete flow,
- credits/billing ledger and redeem flow,
- pricing view,
- theme/sidebar/account shell behavior.

## Tests Added

- `keel-samples/frontend/apps/ai-gateway/src/legacyParity.test.ts`
  - verifies the Vite entry loads `<ai-proxy-app>`,
  - verifies the Vite entry loads legacy CSS/JS,
  - verifies migrated legacy JS/CSS file list and content match `keel-samples/src/main/resources/ui/ai-gateway-ui`.
- `keel-samples/frontend/apps/customer-portal/src/legacyParity.test.ts`
  - verifies the Vite entry loads `<customer-app>`,
  - verifies the Vite entry loads legacy CSS/JS,
  - verifies migrated legacy JS/CSS file list and content match `keel-samples/src/main/resources/ui/customer-portal-ui`.
- `keel-samples/frontend/apps/ai-gateway/src/viteConfig.test.ts`
  - verifies the production backend static base,
  - verifies dev `/api` proxy to `http://localhost:8080`.
- `keel-samples/frontend/apps/customer-portal/src/viteConfig.test.ts`
  - verifies the production backend static base,
  - verifies dev `/api` proxy to `http://localhost:8080`.

## Verification Evidence

Commands run from `keel-samples/frontend`:

```bash
npm run test:run -w @keel/ai-gateway-ui -- src/legacyParity.test.ts src/viteConfig.test.ts
npm run test:run -w @keel/customer-portal-ui -- src/legacyParity.test.ts src/viteConfig.test.ts
npm run typecheck
npm run test:run
npm run build:ai-gateway
npm run build:customer
```

All commands passed.

Command run from repository root:

```bash
./gradlew :keel-samples:processResources
```

Result: build successful. The Gradle task rebuilt both Vite apps, synced generated frontend resources, and copied them into backend runtime resources.

## Runtime Verification

Vite dev servers:

- AI Relay Manager Console opened at `http://127.0.0.1:5177/`.
  - browser confirmed `<ai-proxy-app>` exists,
  - browser confirmed there is no `#root` React entry,
  - browser confirmed login title `AI Proxy`,
  - browser confirmed 10 legacy panel elements.
- Customer Portal opened at `http://127.0.0.1:5178/`.
  - browser confirmed `<customer-app>` exists,
  - browser confirmed there is no `#root` React entry,
  - browser confirmed login title `Keel Portal`,
  - browser confirmed 4 legacy panel elements.

Backend-served static URLs on port 8080:

- `http://localhost:8080/api/plugins/airelay/ui/`
  - returned `/api/plugins/airelay/ui/assets/index-B8L8sdRS.js`,
  - returned `/api/plugins/airelay/ui/assets/index-BgQIjutG.css`,
  - browser confirmed `<ai-proxy-app>` exists,
  - browser confirmed Channels page exposes legacy controls including Add Channel, Test, Edit, Disable, and Delete.
- `http://localhost:8080/api/plugins/customer-portal/ui/`
  - returned `/api/plugins/customer-portal/ui/assets/index-B-6CuH3i.js`,
  - returned `/api/plugins/customer-portal/ui/assets/index-D46QIRfJ.css`,
  - browser confirmed `<customer-app>` exists,
  - browser confirmed login/register legacy entry.

## Notes

- The existing React source and tests still remain. They are no longer the served Step 1 entry and should be treated as scaffolding for later Step 3 refactor work unless a dedicated Step 2/Step 3 plan says otherwise.
- The existing frontend test suite still prints React `act(...)` warnings in several React panel tests. These warnings do not fail the suite and are not part of the legacy Vite entry.
- `npm install` during Gradle frontend tasks reported existing audit findings: 5 vulnerabilities, including 1 critical. This was not introduced or changed by this Step 1 correction.
- Step 2 API compatibility validation and Step 3 full Premium Minimal / Soft 3D redesign were intentionally not implemented in this Step 1 result.
