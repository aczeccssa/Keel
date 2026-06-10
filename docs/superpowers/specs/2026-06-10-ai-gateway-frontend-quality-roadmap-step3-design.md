# AI Gateway Frontend Quality Roadmap Step 3 Design

## Context

Step 1 restored migration parity by serving the original legacy custom-element apps through Vite.
Step 2 validated the frontend API contracts against the backend and fixed the client shape mismatches that blocked typecheck and runtime verification.

Step 3 is the real UI rewrite. The visual direction must move both apps to a premium minimal SaaS system with soft 3D surfaces, white-first light mode, a fully redesigned dark mode, and a new Keel logo direction. Business functionality stays intact.

## Decision

Use **React design-system rewrite** as the implementation path.

This means:

- the served app entry returns to the React `src/main.tsx` app,
- `@keel/sample-ui` becomes the single shared design system source,
- product apps own page composition and data mapping only,
- the legacy custom-element apps remain in `src/legacy` as parity references, but they are no longer the served Step 3 experience.

## Goals

- Replace the current industrial/brutalist look with a premium minimal SaaS visual system.
- Make light mode the default and best-polished mode.
- Redesign dark mode as a first-class palette, not an inverted light theme.
- Add product Landing/Hero pages for both apps.
- Redesign the primary Keel logo direction so it works across nav, sidebar, hero, and favicon-like contexts.
- Keep every existing product feature, route, and data flow available.

## Non-Goals

- Do not change backend business logic unless Step 2 explicitly proved a contract mismatch.
- Do not remove any existing authenticated product feature.
- Do not introduce new UI dependencies unless the implementation plan justifies them.
- Do not keep the old yellow/brown industrial palette in any production surface.
- Do not make the logo final artwork a separate blocking project; it must be part of the same design system work.

## Product Direction

### Light Mode

Light mode is the default and should feel like a polished SaaS product, not a generic admin panel.

Use:

- `#ffffff` primary surfaces,
- `#f7f8fb` page background,
- `#eef2f7` subtle surface separation,
- `#101828` primary text,
- `#667085` secondary text,
- one restrained accent color only, preferably calm blue or teal.

Avoid:

- yellow, brown, tan, paper, sepia,
- heavy black borders,
- purple AI gradients,
- oversized headline treatment,
- generic card-grid marketing layouts.

### Dark Mode

Dark mode must be designed independently.

Use:

- charcoal / graphite surfaces,
- cool neutral borders,
- calibrated elevation instead of color inversion,
- preserved contrast for tables, focus rings, form fields, and error banners.

Dark mode should feel like a premium operator console, not a dimmed light theme.

### Typography

- Use a premium sans stack such as Geist, Satoshi, or Outfit.
- Use mono for token strings, IDs, counts, model names, and request/response metadata.
- Keep headings controlled; rely on hierarchy, spacing, and weight rather than huge display type.

### Motion And Surface Language

- Use soft 3D surfaces: white ceramic panels, subtle inner highlights, low-opacity borders, gentle shadows.
- Hover and active states must animate with transform and opacity only.
- Loading states must use skeletons or shimmer blocks that match the final layout.
- Empty and error states must be designed, not plain text afterthoughts.

## Information Architecture

### Shared Shell

`@keel/sample-ui` will own:

- theme tokens,
- app shell,
- logo,
- button system,
- cards and surfaces,
- tables,
- stat blocks,
- chart placeholders,
- loading / empty / error states,
- hero and landing primitives,
- responsive navigation / side rail patterns.

### AI Relay Manager Console

Required surfaces:

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
- playground / relay request surface where relevant.

### Customer Portal

Required surfaces:

- product Landing/Hero page,
- login/register page,
- dashboard/home,
- API keys,
- billing / credits,
- pricing.

## Architecture

### App Composition

Each app should be split into:

1. `src/main.tsx`
2. `src/App.tsx`
3. `src/state/*`
4. `src/api/*`
5. `src/pages/*` or `src/panels/*`
6. shared UI imports from `@keel/sample-ui`

Product pages should only handle:

- route selection,
- auth state,
- tab state,
- data fetching orchestration,
- empty / loading / error decisions,
- product-specific labels and copy.

Shared visual behavior should live in `@keel/sample-ui`.

### Data Flow

- Auth state is loaded from existing storage keys and remains compatible with the current Step 2 contract fixes.
- The Landing/Hero page routes into sign in / register or the authenticated shell.
- Authenticated shells preserve hash-based tab routing where already used.
- Data views keep using the Step 2-aligned API clients.

### Logo Direction

The logo should move away from any industrial badge feel and toward a minimal premium mark with a soft geometric system.

Constraints:

- must work on white and dark surfaces,
- must scale down to sidebar / favicon-size usage,
- must have a monochrome fallback,
- must avoid heavy outlines and hazard-style contrast,
- should read as a modern Keel mark rather than a literal gateway icon.

Implementation should start with a vector-first SVG/logo component, then derive favicon and smaller variants from the same shape language.

## Page Design

### Landing / Hero

Each app needs a product landing page with:

- product name in the first viewport,
- a concise product claim,
- a concrete CTA pair,
- a real UI mock or product preview surface,
- a visible hint of the next section below the fold.

The hero should use a left/right asymmetric composition rather than a centered marketing block.

### Manager Console

The manager console should feel like a premium operations surface:

- sidebar with concise navigation,
- understated top area,
- dense tables and management forms,
- grouped sections rather than heavy nested cards,
- clear action hierarchy for create/edit/test/delete operations.

### Customer Portal

The customer portal should feel lighter and more self-service oriented:

- simpler landing and auth flow,
- focused credits / keys / pricing views,
- smaller control surface,
- clear path from landing to sign-in to authenticated dashboard.

## Component Model

### Shared Component Set

`@keel/sample-ui` should provide or own:

- `AppShell`
- `Hero`
- `Surface`
- `Button`
- `IconButton`
- `Card`
- `StatGrid`
- `DataTable`
- `EmptyState`
- `ErrorBanner`
- `SkeletonBlock`
- `ThemeToggle`
- `KeelLogo`

### App-Specific Components

Each app may own:

- landing hero composition,
- auth form composition,
- dashboard summary layout,
- panel-specific action blocks,
- table row / empty state copy,
- route-specific explanatory copy.

## Error, Empty, And Loading States

Every screen should define:

- loading skeleton,
- empty copy,
- retryable error state where relevant,
- disabled state for unavailable actions,
- focus-visible styling,
- mobile fallback layout,
- dark mode fallback layout.

Forms must use:

- label above field,
- helper text when needed,
- inline error beneath field.

## Implementation Constraints

- Preserve backend route usage and auth storage keys.
- Preserve hash routing for existing tab flows unless a page explicitly moves to real route navigation.
- Use stable responsive dimensions for tables, sidebars, icon buttons, and data cards.
- Keep typography fixed and avoid viewport-based font scaling.
- Do not overuse cards; use surface grouping only where hierarchy requires it.

## Verification

The implementation must pass:

- `npm run test:run` in `keel-samples/frontend`,
- `npm run typecheck` in `keel-samples/frontend`,
- `npm run build:ai-gateway`,
- `npm run build:customer`,
- `npm run api:contracts`,
- `./gradlew :keel-samples:processResources`,
- browser verification of:
  - AI Relay Manager Console dev server,
  - Customer Portal dev server,
  - backend-served 8080 URLs,
  - light mode,
  - dark mode,
  - landing / auth / authenticated shell,
  - one dense table view,
  - one empty state,
  - one error state.

## Acceptance Criteria

- The served apps use the new React UI instead of the legacy custom-element shell.
- Both products have a visible landing / hero experience.
- The UI reads as Premium Minimal SaaS / Soft 3D rather than industrial/brutalist.
- Light mode is white-first and premium.
- Dark mode is fully redesigned.
- The logo is updated to the new direction.
- Existing business functionality is intact.
- The Step 2 API contract alignment remains valid.
