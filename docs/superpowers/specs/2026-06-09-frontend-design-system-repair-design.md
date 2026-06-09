# Frontend Design System Repair Design

## Context

The transported React frontends in `keel-samples/frontend` have lost a large part of their visual system. The served backend build at `http://localhost:8080/api/plugins/airelay/ui/` shows the AI Relay shell with partial layout styling, while lower-level content, footer controls, and typography still fall back to browser defaults. The Customer Portal build at `http://localhost:8080/api/plugins/customer-portal/ui/` is more severe: the login view renders as mostly raw HTML because its app stylesheet only defines the global background and font.

The repository still contains pre-React static resources under `keel-samples/src/main/resources/ui/*`. Those files establish the intended quality bar: industrial/brutalist operational UI, matte paper surfaces, strong black structure, one teal accent, hard-edged controls, and dense but readable dashboard composition. The repair should not copy those legacy selectors directly because the React migration uses a new `keel-*` component vocabulary. Instead, the new React design system should keep the `keel-*` naming model and restore visual completeness at the shared UI layer.

## Goal

Rebuild the React sample frontend styling so both AI Relay Manager Console and Customer Portal render as complete, intentional operational tools, with shared component styles living in `@keel/sample-ui` and app-level CSS limited to app-specific layout or product variation.

## Non-Goals

- Do not change backend APIs, authentication logic, or business data shape.
- Do not replace the React/Vite migration with the old static JavaScript UI.
- Do not introduce Tailwind, Framer Motion, icon libraries, or new third-party dependencies for this repair.
- Do not redesign product flows beyond styling, layout hierarchy, component state, and responsiveness.

## Design Direction

The React frontends should use a unified `keel-*` visual language:

- Typography: high-quality sans-serif and monospace stacks already represented by the token file, with display labels using a heavy grotesque feel and numbers using the mono stack.
- Color: matte neutral background, off-black ink, muted text, a single teal accent, plus restrained danger/success/amber state colors.
- Geometry: firm, low-radius or square operational controls. Avoid the current soft SaaS radius where it weakens the old UI intent.
- Surfaces: hard structural borders and subtle hard-offset shadows where elevation matters.
- Density: daily operational dashboard density, not a marketing page and not a decorative card grid.
- Motion: CSS-only hover, focus, active, and loading shimmer states. No runtime animation dependency is needed.

## Architecture

### Shared UI Package

`keel-samples/frontend/packages/ui/src/styles/tokens.css` becomes the single shared stylesheet entry point for:

- design tokens
- global reset and document defaults
- shared component styles for exported UI components
- form, table, empty, error, stat, chart, and theme-toggle styles
- responsive shell rules
- dark theme token overrides

Both app entry files already import this stylesheet:

- `keel-samples/frontend/apps/ai-gateway/src/main.tsx`
- `keel-samples/frontend/apps/customer-portal/src/main.tsx`

That means the repair can centralize almost all cross-app styling without changing import topology.

### App Stylesheets

App-level CSS should stay small:

- `keel-samples/frontend/apps/ai-gateway/src/styles/app.css`
- `keel-samples/frontend/apps/customer-portal/src/styles/app.css`

These files should only define app-specific page composition that is not owned by shared components, such as login page placement, panel stacks, product-specific dashboard grid sizing, and any one-off classes already used by app panels.

Duplicate shell, card, button, table, and form rules should move out of the AI Gateway app stylesheet into the shared stylesheet so Customer Portal receives the same baseline.

### Dev and Backend Build Targets

Use two verification modes:

1. Vite dev servers for fast frontend iteration:
   - AI Relay: `npm run dev -w @keel/ai-gateway-ui`
   - Customer Portal: `npm run dev -w @keel/customer-portal-ui`
   - both Vite configs should proxy `/api` requests to `http://localhost:8080` in dev mode

2. Backend-served static build for final integration:
   - build the frontend workspaces
   - let Gradle sync the `dist` output into generated resources
   - verify:
     - `http://localhost:8080/api/plugins/airelay/ui/`
     - `http://localhost:8080/api/plugins/customer-portal/ui/`

The Vite dev setup must keep frontend assets on the Vite port while API calls continue to hit the backend on `localhost:8080`.

## Components To Style

The following shared component classes must have complete base, hover, focus, disabled, empty, and responsive behavior where applicable:

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

The shared stylesheet should also cover raw elements that appear inside existing panels:

- `form`
- `label`
- `input`
- `textarea`
- `select`
- `button`
- `h1` through `h3`
- `p`
- `table`, `thead`, `tbody`, `th`, `td`

Raw element rules must be scoped through app roots or shared containers when possible, so backend docs or unrelated pages do not inherit unintended frontend styling.

## Page-Level Styling

### AI Relay Manager Console

AI Relay should keep the sidebar-plus-main operational layout. The repair should:

- preserve the current tab structure and hash navigation
- give nav title and hint separate block/flex spacing so text no longer collapses into strings like `DashboardCost`
- make footer controls match the design system instead of browser defaults
- style dashboard panels, data tables, status cards, charts, and empty data areas
- keep numbers and telemetry-like values in the mono stack

### Customer Portal

Customer Portal should share the same system while feeling slightly simpler and customer-facing. The repair should:

- give the unauthenticated login/register view a centered but not oversized operational form layout
- style labels above inputs with clear focus rings and error text placement
- use the same `Card`, `Button`, `DataTable`, `EmptyState`, and shell styles as AI Relay once authenticated
- avoid introducing a marketing landing page

## State Handling

The visual system must cover:

- loading: skeleton or shimmer blocks that match the eventual component size
- empty: structured empty states using `.keel-empty` or `.keel-table-empty`
- error: inline `.keel-error-banner` with clear contrast and no layout jump
- disabled: muted controls with no hover lift
- focus: keyboard-visible outlines on buttons, inputs, nav items, and theme controls
- active: small tactile transform for buttons and nav actions

Existing React data loading behavior can remain unchanged unless a panel already exposes a loading or error state that lacks class hooks.

## Responsive Behavior

The desktop shell should remain a two-column layout. Below tablet width:

- the shell collapses to a single column
- sidebar navigation becomes a top block with wrapping controls
- main content uses compact padding
- tables remain readable through horizontal overflow containers or compact cell spacing
- forms use full-width inputs and buttons

No view should require horizontal page scrolling on mobile except intentionally scrollable tables.

## Testing And Verification

Automated checks:

- Run `npm run typecheck` from `keel-samples/frontend`.
- Run `npm run test:run` from `keel-samples/frontend`.
- Run both app builds:
  - `npm run build:ai-gateway`
  - `npm run build:customer`

Visual checks:

- Open AI Relay in the Vite dev server and verify shell, nav, dashboard, table, footer, theme toggle, login, empty, and error states where reachable.
- Open Customer Portal in the Vite dev server and verify login/register and authenticated shell states where reachable.
- Verify the backend-served static builds at the two 8080 plugin URLs after rebuilding/syncing resources.
- Capture desktop and mobile-width screenshots for both apps before calling the repair complete.

Regression checks:

- Confirm CSS asset URLs still respect Vite `base` paths:
  - `/api/plugins/airelay/ui/`
  - `/api/plugins/customer-portal/ui/`
- Confirm theme preference continues to use `keel-theme-pref`.
- Confirm no browser-default input or button styling remains inside either React app.

## Risks

- Some app panel JSX uses raw labels, inputs, and buttons instead of shared components. The stylesheet must deliberately cover these without creating global side effects.
- The old static UI uses a different DOM and selector model. It is a visual reference, not a drop-in implementation source.
- Backend-served 8080 pages are static builds, so Vite hot reload must be validated separately from final integration.

## Acceptance Criteria

- AI Relay and Customer Portal both render with a complete, coherent `keel-*` design system.
- Shared component styles live primarily in `packages/ui`, not duplicated per app.
- Customer Portal no longer renders login/register as raw HTML.
- AI Relay footer controls, content panels, tables, and nav text spacing no longer use browser-default styling.
- Dev server iteration and backend static build verification are both documented and working.
- Typecheck, unit tests, and production builds pass for the frontend workspace.
