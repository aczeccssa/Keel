# AI Gateway Frontend Quality Roadmap Design

## Context

The transported React frontends in `keel-samples/frontend` currently have three separate quality problems:

1. **Foundation loss:** Several UI styles were lost during transport. AI Relay Manager Console has a partially styled shell, but footer controls, panel content, tables, forms, and many lower-level elements still fall back to browser defaults. Customer Portal is more severe: the unauthenticated login/register view renders almost like raw HTML.
2. **API uncertainty:** The React frontends call a broad set of backend plugin APIs across `account`, `token`, `airelay`, `riskcontrol`, and `customer-portal`. Before doing a full UI rewrite, every frontend API path must be checked against the backend route definitions and runtime behavior.
3. **Wrong visual direction:** The current yellow/brown industrial/brutalist visual language is not the target. The final UI should be rebuilt as a clean, premium, mostly white SaaS experience with soft 3D minimal surfaces. Existing business functionality stays intact, and new product Landing/Hero pages are added.

This spec replaces the narrower frontend style repair spec. The work should proceed serially so each phase has a clear verification boundary.

## Goal

Upgrade Customer Portal and AI Relay Manager Console through a three-step roadmap:

1. restore a complete shared React UI foundation,
2. verify all frontend API calls against working backend endpoints,
3. redesign every page into a Premium Minimal SaaS Landing Page / Soft 3D Minimal Web Design system, including new product Landing/Hero pages and a redesigned primary logo.

## Non-Goals

- Do not change the product's core business behavior.
- Do not remove existing routes, panels, auth flows, or data workflows.
- Do not preserve the current yellow/brown industrial/brutalist style.
- Do not finalize the concrete logo artwork, brand illustration assets, or detailed hero compositions in this spec. This spec defines the required direction and constraints; Step 3 includes the asset exploration and final asset production work.
- Do not introduce new third-party UI or animation dependencies unless the implementation plan justifies them after checking `package.json`.

## Serial Roadmap

### Step 1: Restore The React UI Foundation

Step 1 fixes the transported frontend so the app is no longer visually broken.

Requirements:

- Centralize shared styles in `keel-samples/frontend/packages/ui/src/styles/tokens.css`.
- Keep app stylesheets small and app-specific:
  - `keel-samples/frontend/apps/ai-gateway/src/styles/app.css`
  - `keel-samples/frontend/apps/customer-portal/src/styles/app.css`
- Style the shared `keel-*` component vocabulary completely:
  - `.keel-app-shell`
  - `.keel-sidebar`
  - `.keel-brand`
  - `.keel-logo`
  - `.keel-nav-section`
  - `.keel-main`
  - `.keel-card`
  - `.keel-button`
  - `.keel-data-table`
  - `.keel-table-empty`
  - `.keel-empty`
  - `.keel-error-banner`
  - `.keel-stat-grid`
  - `.keel-stat`
  - `.keel-chart-placeholder`
  - `.keel-theme-toggle`
- Scope raw element styling for forms, labels, inputs, buttons, headings, paragraphs, and tables so existing panels stop using browser defaults.
- Add Vite dev proxy behavior so `/api` calls go to `http://localhost:8080` while frontend assets hot reload from the app dev server.
- Verify both dev-server rendering and backend-served static rendering.

Step 1 is a stabilization phase. It should not attempt the final Premium Minimal redesign yet, but it should avoid adding more industrial/brutalist styling.

### Step 2: Validate Frontend API Contracts

Step 2 ensures every frontend API method has a matching backend route and works through the expected auth mode.

Frontend API clients to validate:

- `keel-samples/frontend/apps/ai-gateway/src/api/aiGatewayApi.ts`
- `keel-samples/frontend/apps/customer-portal/src/api/customerPortalApi.ts`

Backend plugin areas to validate against:

- `account`
- `token`
- `airelay`
- `riskcontrol`
- `customer-portal`

AI Relay Manager Console API coverage:

- `POST /api/plugins/account/v1/auth/login`
- `POST /api/plugins/account/v1/auth/register`
- `POST /api/plugins/account/v1/auth/refresh`
- `GET /api/plugins/airelay/admin/nav-counts`
- `GET /api/plugins/token/admin/usage/global`
- `GET /api/plugins/token/admin/usage/records?limit=...`
- `GET /api/plugins/airelay/admin/channels`
- `GET /api/plugins/airelay/admin/groups`
- `GET /api/plugins/token/admin/keys`
- `GET /api/plugins/airelay/admin/pricing`
- `GET /api/plugins/airelay/admin/pools`
- `GET /api/plugins/riskcontrol/v1/rules`
- `GET /api/plugins/account/admin/users`
- `GET /api/plugins/customer-portal/admin/customers`
- `GET /api/plugins/customer-portal/admin/codes`
- `POST /api/plugins/airelay/v1/chat/completions`
- `POST /api/plugins/airelay/v1/responses`
- `POST /api/plugins/airelay/v1/messages`

Customer Portal API coverage:

- `POST /api/plugins/customer-portal/v1/customer/auth/login`
- `POST /api/plugins/customer-portal/v1/customer/auth/register`
- `POST /api/plugins/customer-portal/v1/customer/auth/refresh`
- `GET /api/plugins/customer-portal/v1/customer/auth/me`
- `GET /api/plugins/customer-portal/v1/customer/credits`
- `GET /api/plugins/customer-portal/v1/customer/credits/ledger`
- `POST /api/plugins/customer-portal/v1/customer/credits/redeem`
- `GET /api/plugins/customer-portal/v1/customer/usage`
- `GET /api/plugins/customer-portal/v1/customer/keys`
- `POST /api/plugins/customer-portal/v1/customer/keys`
- `DELETE /api/plugins/customer-portal/v1/customer/keys/{keyId}`
- `GET /api/plugins/customer-portal/v1/customer/pricing`
- `GET /api/plugins/airelay/admin/groups`

Step 2 should produce a clear compatibility matrix showing:

- frontend method name,
- HTTP method and path,
- backend route owner,
- auth requirement,
- expected success shape,
- expected failure behavior,
- verification command or test.

Any mismatched path, missing backend route, bad response shape, or auth mismatch must be fixed before Step 3 starts.

### Step 3: Full Premium Minimal / Soft 3D Redesign

Step 3 is the final visual rewrite. It covers every page and keeps functionality unchanged.

Scope:

- Replace the current visual direction across all Customer Portal and Manager Console screens.
- Add product Landing/Hero pages.
- Redesign the primary Keel logo direction to match the new system.
- Keep all existing authenticated product features available.
- Keep all data flows, API clients, auth storage keys, and panel routing behavior unless Step 2 proves a contract needs correction.

The UI should feel like a premium SaaS product, not an internal demo or a legacy admin page.

## Final Visual Style Standard

### Light Mode Base

Light mode is the primary design target.

- Base UI color: pure white and near-white.
- Recommended core palette:
  - `#ffffff` for primary surfaces,
  - `#f7f8fb` for page background,
  - `#eef2f7` for subtle surface separation,
  - `#101828` for primary text,
  - `#667085` for secondary text.
- Use one restrained accent color only. Acceptable directions are calm teal or clean blue with saturation below the typical AI-purple/neon range.
- Do not use yellow, brown, tan, sand, paper, sepia, industrial hazard colors, or heavy black borders.
- Do not use purple/blue AI gradients as the dominant identity.

### Dark Mode Base

Dark mode must be completely redesigned, not derived from the current yellow/brown or industrial palette.

- Use a premium dark SaaS palette with charcoal, graphite, and cool neutral surfaces.
- Keep the same information hierarchy and component geometry as light mode.
- Recalibrate shadows, borders, and glass/soft 3D effects for dark surfaces instead of simply inverting colors.
- Preserve accessibility contrast for text, controls, focus rings, errors, and data tables.
- Dark mode is a first-class target, but the implementation may sequence it after the light-mode redesign if the implementation plan keeps both modes within Step 3.

### Typography

- Dashboard and SaaS UI must use sans-serif typography only.
- Final font direction should be Geist, Satoshi, Outfit, or a similar premium sans stack.
- Inter is not the target brand font.
- Numeric telemetry, IDs, token usage, model names, and API-like values should use a refined mono stack.
- Headings should feel controlled and precise, not oversized by default.

### Material And Surface Language

- Use soft 3D minimal surfaces: white ceramic panels, subtle inner highlights, low-opacity borders, gentle depth, and restrained environmental shadows.
- Cards are allowed when they represent a functional grouping, but avoid repetitive generic card grids.
- Prefer soft layering, spacious rhythm, and clean hierarchy over hard dividers.
- Hover and active states should use transform and opacity, not layout shifts.
- Loading states should use skeletons or gentle shimmer blocks that match final layout sizes.
- Empty and error states should be designed, not textual afterthoughts.

### Landing And Hero Pages

Add product Landing/Hero pages for the frontend experience.

Requirements:

- The product identity must be visible in the first viewport.
- The hero should present the product through actual UI surfaces, product screenshots, or high-fidelity product mock panels, not abstract decoration alone.
- Avoid centered generic hero text as the only composition.
- The hero should hint at the next section on common desktop and mobile viewports.
- The landing page should route naturally into sign in, create account, or the authenticated product shell.
- Marketing copy should be concrete and product-specific.

### Logo Direction

The primary Keel logo must be redesigned to fit the new premium minimal system.

This spec does not define the final artwork. It defines constraints:

- no industrial badge look,
- no yellow/brown palette,
- no heavy black-outline mark,
- must work on white and dark premium surfaces,
- must scale down cleanly in sidebars, nav bars, and favicon-like contexts,
- must have a monochrome fallback.

Logo exploration and final asset production should happen during Step 3 or a dedicated design task before Step 3 implementation finishes.

## Architecture

### Shared UI Package

`@keel/sample-ui` should own the common design system:

- tokens,
- reset,
- layout shell primitives,
- buttons,
- cards,
- tables,
- empty/error/loading states,
- theme toggle,
- logo component,
- reusable landing/hero primitives if shared by both apps.

### App Packages

AI Relay Manager Console and Customer Portal should own product-specific pages and data views:

- `keel-samples/frontend/apps/ai-gateway`
- `keel-samples/frontend/apps/customer-portal`

App packages should not duplicate shared component styling. They can define product-specific page composition, dashboard layouts, hero content, and panel-specific classes.

### Dev And Backend Build Targets

Use two verification modes throughout:

1. Vite dev servers for fast frontend iteration:
   - AI Relay: `npm run dev -w @keel/ai-gateway-ui`
   - Customer Portal: `npm run dev -w @keel/customer-portal-ui`
   - dev proxy sends `/api` to `http://localhost:8080`

2. Backend-served static build for final integration:
   - build frontend workspaces,
   - sync or generate backend static resources through the existing Gradle frontend tasks,
   - verify:
     - `http://localhost:8080/api/plugins/airelay/ui/`
     - `http://localhost:8080/api/plugins/customer-portal/ui/`

## Page Coverage

The redesign must cover all current app surfaces.

AI Relay Manager Console:

- product Landing/Hero page,
- login/register page,
- dashboard,
- usage,
- channels,
- groups,
- API keys,
- pricing,
- pools,
- rate limits,
- users,
- customers,
- redemption codes,
- playground-style relay calls where present.

Customer Portal:

- product Landing/Hero page,
- login/register page,
- dashboard/home,
- API keys,
- billing/credits,
- pricing.

If implementation discovers additional routed panels or hidden states, those must be included in the same design system coverage.

## State Handling

Every redesigned surface must define:

- loading state,
- empty state,
- error state,
- disabled state,
- focus-visible state,
- active/pressed state,
- mobile layout,
- dark-mode layout and colors.

Existing data behavior can remain unchanged, but missing class hooks or component structure should be added when required to present these states correctly.

## Testing And Verification

Automated checks:

- Run `npm run typecheck` from `keel-samples/frontend`.
- Run `npm run test:run` from `keel-samples/frontend`.
- Run both app builds:
  - `npm run build:ai-gateway`
  - `npm run build:customer`

API checks:

- Validate the Step 2 compatibility matrix.
- Add or update frontend API tests for path, method, auth token, and request body behavior.
- Add backend route tests or integration checks where route existence or response shape is not already covered.

Visual checks:

- Verify both apps in Vite dev mode.
- Verify both apps through backend-served static URLs on port 8080.
- Capture desktop and mobile screenshots for:
  - landing/hero,
  - login/register,
  - authenticated shell,
  - one dense data table view,
  - one empty state,
  - one error state,
  - light mode,
  - dark mode.

Regression checks:

- CSS asset URLs still respect Vite `base` paths:
  - `/api/plugins/airelay/ui/`
  - `/api/plugins/customer-portal/ui/`
- Theme preference continues to use `keel-theme-pref`.
- Auth storage keys continue to work:
  - `keel-ai-gateway-auth`
  - `keel-customer-portal-auth`
- No browser-default form or button styling remains inside either React app after Step 1.
- No yellow/brown/industrial/brutalist palette remains after Step 3.

## Risks

- Step 2 may reveal backend route mismatches that require implementation before the visual rewrite can proceed.
- Step 3 is a full UI rewrite, so it must not start until Step 1 and Step 2 have clear passing verification.
- Landing/Hero pages add new navigation states and must not block direct access to login or authenticated routes.
- Dark mode requires a full palette redesign and should not be treated as automatic token inversion.
- Logo redesign may need asset iteration; the implementation plan should isolate logo work so it does not block API validation.

## Acceptance Criteria

- The work is implemented serially as Step 1, then Step 2, then Step 3.
- Step 1 restores a complete shared React UI foundation and removes raw browser-default styling.
- Step 2 verifies every frontend API method against a working backend route and records the compatibility matrix.
- Step 3 replaces all Customer Portal and AI Relay Manager Console pages with the new Premium Minimal / Soft 3D visual system.
- New product Landing/Hero pages exist and route naturally into the product flows.
- The primary logo direction is replaced or prepared for replacement according to the new style constraints.
- Light mode uses white/near-white premium SaaS UI as the default.
- Dark mode is fully redesigned with its own premium palette.
- Existing product functionality remains available.
- Frontend typecheck, tests, builds, and backend-served static verification pass.
