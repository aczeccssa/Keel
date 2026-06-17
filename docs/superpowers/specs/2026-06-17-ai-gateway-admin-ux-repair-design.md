# AI Gateway Admin UX Repair Design

Date: 2026-06-17

## Goal

Repair the AI Gateway admin experience around usage history, dashboard refresh/layout, routing groups, API keys, customers, and unclear system pages. The UI focus is the legacy Web Components app under `keel-samples/frontend/apps/ai-gateway/src/legacy/`. The service focus is `keel-samples/src/main/kotlin/com/keel/samples/aigateway/`.

## Context From Current Code

The legacy admin UI already contains panels for Dashboard, Usage, Groups, API Keys, Customers, Rate Limits, and Users. Several panels render controls whose backing data contract is incomplete or incorrectly wired:

- `PanelUsage.js` fetches `/api/plugins/token/admin/usage/records?limit=...`, but the repository caps results at 200 and returns `total = rows.size`, so the UI cannot page through historical records.
- Usage group filtering sends `groupId`, while table display uses `poolLevelId` as the visible group cell. These values can differ, especially for priority level IDs such as `group-p0`.
- `PanelDashboard.js` has a private auto-refresh checkbox, while `app.js` has a global Live indicator and Refresh button. The app calls `dashboard.setLiveMode(...)`, but the dashboard panel does not implement that method.
- Dashboard Recent Request Stream fetches 200 records and renders 20; the requested behavior is fixed-height 5 rows with horizontal scrolling.
- `PanelGroups.js` renders `group.aliasRoutes`, while the backend also exposes `/admin/groups/{groupId}/aliases`. Alias data must be sourced consistently.
- `PanelKeys.js` lists `/admin/keys`, but deletes through `/v1/keys/{keyId}`, which is the current-user key API, not an admin mutation API.
- `PanelCustomers.js` is intentionally read-only even though the customer portal backend exposes admin update, soft delete, credit adjustment, and key revoke routes.
- Rate Limits and Users & Groups have real backend concepts, but their UI labels and affordances do not explain their scope.

## Recommended Approach

Use one repair spec split into four implementation tracks:

1. Usage Ledger
2. Dashboard Live/Layout
3. Admin CRUD
4. Product Clarity

This keeps shared data contracts consistent across Usage, Customers, API Keys, and Dashboard while still allowing implementation checkpoints to be completed page by page.

Rejected alternatives:

- Separate specs per page: smaller documents, but likely to duplicate and drift on the usage/customer/key contracts.
- Frontend-only polish: faster, but it would keep the missing history, admin delete, and customer consumption root causes intact.

## Track 1: Usage Ledger

### Problems Covered

1. Usage filtering is performed within the existing 200 records, has no pagination, and cannot show history.
2. Usage group filter uses group names/IDs differently from the table, so selected groups cannot match displayed rows.
3. Usage filters are not treated as one mutually coherent query.
12. There is no page/detail view to inspect a specific customer's consumption, and usage request detail lacks useful user/customer identity.

### Service Design

Extend the token admin usage endpoint:

`GET /api/plugins/token/admin/usage/records`

Supported query parameters:

- `limit`: page size, default 50, max 200.
- `cursor`: opaque next-page cursor for records older than the current page.
- `offset`: optional offset fallback for simple UI paging if cursor is not implemented first.
- `from`: inclusive ISO timestamp filter.
- `to`: exclusive ISO timestamp filter.
- `routingGroupId`: relay routing group ID selected in the UI.
- `poolLevelId`: low-level pool/priority identifier for diagnostics only.
- `channelId`: upstream channel ID.
- `model`: exact model string.
- `statusFilter`: `success` or `error`.
- `status`: exact HTTP status integer.
- `userId`: B-end account user.
- `keyId`: virtual API key.
- `customerId`: C-end customer, when the record is tied to a customer API key.

Response shape:

```json
{
  "records": [],
  "total": 1234,
  "nextCursor": "opaque-or-null",
  "pageSize": 50,
  "filtersApplied": {
    "routingGroupId": "premium",
    "channelId": "openai-main",
    "statusFilter": "success"
  }
}
```

`TokenUsageRecordView` should expose separate fields instead of overloading `poolLevelId`:

- `routingGroupId`
- `routingGroupName`
- `poolLevelId`
- `channelId`
- `channelName`
- `userId`
- `userEmail`, if available from `UserDirectory`
- `keyId`
- `keyDisplayName`
- `customerId`, when available
- `customerEmail`, when available

Filtering semantics:

- All selected filters are combined with AND semantics on the server.
- Empty filter values are ignored.
- `statusFilter=success` means `status >= 200 && status < 300`.
- `statusFilter=error` means `status >= 400`.
- `routingGroupId` must match the logical routing group, not the priority pool level ID.

### UI Design

Modify `PanelUsage.js`:

- Build a full query object on every refresh.
- Reset pagination to the first page when any filter changes.
- Add Prev/Next controls, current range, total count, and page-size selector.
- Keep `limit` as page size, not as "show all available records".
- Populate group filter options with `value = group.groupId` and label `group.name || group.groupId`.
- Display the group column as `routingGroupName || routingGroupId || "unknown"`.
- Display `poolLevelId` only in the detail drawer.
- Clear button resets all filters and returns to page 1.
- Detail drawer includes user/key/customer identity fields.
- Empty state states whether there are no records globally or no records matching filters.

### Acceptance Checkpoints

- [ ] Usage can show records older than the first 200 entries by paging.
- [ ] Usage filters are applied by the backend before pagination.
- [ ] Selecting a group option returns matching table rows.
- [ ] Group column shows human-readable group name when available.
- [ ] Detail drawer shows `routingGroupId`, `routingGroupName`, `poolLevelId`, `channelId`, `channelName`, `userId`, `keyId`, and customer fields when present.
- [ ] Multiple filters operate together with AND semantics.
- [ ] Clear removes all filters and resets pagination.

## Track 2: Dashboard Live/Layout

### Problems Covered

4. Dashboard window switching works, but Requests/Tokens chart labels become dense because hour-style labels are reused for day windows.
5. Dashboard auto-refresh should be default and governed by the global Live/Refresh controls, not by a panel-local checkbox.
6. Channel Health should not be the same height as Recent Request Stream; Recent should show 5 rows, fixed height, and horizontal scroll.
7. Dashboard modules should adapt to width changes.

### Service Design

Extend `GET /api/plugins/airelay/admin/stats/dashboard?window=...` to include bucket metadata:

```json
{
  "overview": {},
  "trends": {
    "bucketGranularity": "5m",
    "bucketLabelMode": "time",
    "requests": [],
    "tokens": [],
    "latency": []
  },
  "distributions": {}
}
```

Bucket policy:

- `1h`: 12 buckets, 5-minute granularity, time labels.
- `24h`: 24 buckets, 1-hour granularity, time labels.
- `7d`: 7 buckets, 1-day granularity, day labels.
- `30d`: 30 one-day buckets if labels are thinned in UI, or 15 two-day buckets if chart density requires it.

The backend must not return misleading `requestsByHour` names for non-hour windows. If backward compatibility is needed, keep old fields temporarily but add canonical fields.

### UI Design

Modify `app.js`:

- Treat Live as a global default-on state.
- Store refresh interval and live state in the shell.
- Topbar Refresh button calls `refresh({ reason: "manual" })` on the active panel.
- Live interval calls `refresh({ reason: "live" })` on the active panel only.
- Dispatch `setLiveMode(enabled)` to active panels that need to react visually.
- Do not create per-panel refresh loops unless a panel owns a streaming connection.

Modify `PanelDashboard.js`:

- Remove the panel-local Auto refresh checkbox.
- Implement `setLiveMode(enabled)` so the global Live toggle works.
- Use dashboard bucket metadata to choose labels.
- Thin labels on day-based windows so charts remain readable.
- Recent Request Stream requests only the amount needed for display, preferably `limit=5`.
- Recent table renders 5 rows in a fixed-height scroll container.
- Recent table container uses horizontal overflow and a table min width, so columns remain readable instead of compressed.
- Channel Health is a compact summary with healthy, cooldown, disabled, total, and optional warning copy.
- Health card is content-height, not stretched to match Recent.
- Layout uses responsive CSS grid:
  - stats first,
  - trend cards auto-fit from 3 columns to 1,
  - distributions auto-fit,
  - Health and Recent arranged independently and allowed to wrap.

### Acceptance Checkpoints

- [ ] `1h`, `24h`, `7d`, and `30d` windows each render readable chart labels.
- [ ] Dashboard no longer shows a local auto-refresh checkbox.
- [ ] Global Live is on by default and refreshes the active Dashboard tab.
- [ ] Global Refresh button refreshes Dashboard data immediately.
- [ ] Pausing Live stops automatic refreshes.
- [ ] Channel Health card height follows its own content.
- [ ] Recent Request Stream shows exactly 5 latest rows.
- [ ] Recent Request Stream has fixed height and horizontal scrolling.
- [ ] Dashboard cards wrap cleanly across wide, medium, and narrow widths without overlap.

## Track 3: Admin CRUD

### Problems Covered

8. Groups delete button is effectively a placeholder from the user's point of view.
9. Group alias routes list is always empty.
10. API Keys delete is not available for all keys, and many clicks do not produce visible action.
11. Customers cannot be deleted.
12. Customer-specific usage and consumption records are not available from a customer detail surface.

### Groups

Service behavior:

- Keep `DELETE /api/plugins/airelay/admin/groups/{groupId}` as the delete endpoint.
- Return clear errors:
  - `400` for default group deletion.
  - `409` when channels are still attached.
  - `404` when group does not exist.
- Ensure `GET /admin/groups` either includes `aliasRoutes` or document that the UI must fetch `GET /admin/groups/{groupId}/aliases`.
- Prefer including `aliasRoutes` in `GroupView` to avoid N+1 frontend calls.
- `PUT /admin/groups/{groupId}` should persist group metadata only.
- Alias replacement should go through `PUT /admin/groups/{groupId}/aliases`.

UI behavior in `PanelGroups.js`:

- Delete button disables while request is in flight.
- Success removes the card after refresh.
- Errors are shown inline on the group card, not only as `alert`.
- Alias routes render from the canonical `aliasRoutes` source.
- Save flow updates group metadata and alias routes explicitly.
- Alias section shows target model, channel constraint, target count, enabled state, and credit multiplier.

Checkpoints:

- [ ] Deleting an empty non-default group removes it.
- [ ] Deleting a group with memberships returns a clear "detach channels first" message.
- [ ] Default group cannot be deleted and the UI does not present it as a normal destructive action.
- [ ] Saved alias routes display after page refresh.
- [ ] Editing alias routes persists and remains visible after reload.

### API Keys

Service behavior:

Add admin key mutation endpoints under `/api/plugins/token/admin`:

- `GET /keys`: already exists.
- `PUT /keys/{keyId}`: update display name, routing group, budget, limits, status.
- `POST /keys/{keyId}/revoke`: revoke without soft-deleting.
- `DELETE /keys/{keyId}`: soft-delete as admin.

All admin mutations must use `includeAll = true` semantics in `TokenRepository`, and must return `403` only when the caller is not admin, not when the key belongs to another user.

UI behavior in `PanelKeys.js`:

- Delete active keys through `/admin/keys/{keyId}`.
- For old keys, Connect should not imply that the raw secret can be recovered.
- Rename Connect for saved/non-new keys to "Setup" or "Snippet".
- Keep raw secret display only for keys created during the current browser session.
- Buttons show loading state and disable duplicate clicks.
- Errors render inline in the panel.

Checkpoints:

- [ ] Admin can revoke/delete any key from `/admin/keys`.
- [ ] Delete does not fail because the key belongs to a different user.
- [ ] Revoked/deleted keys disappear or show revoked according to chosen table filter.
- [ ] Setup modal never claims to recover a secret that is no longer available.
- [ ] Generate, copy, setup, and delete clicks all visibly respond.

### Customers

Service behavior:

Backend already has admin routes:

- `GET /api/plugins/customer-portal/admin/customers`
- `GET /admin/customers/{customerId}`
- `PUT /admin/customers/{customerId}`
- `DELETE /admin/customers/{customerId}`
- `POST /admin/customers/{customerId}/credits`
- `DELETE /admin/customers/{customerId}/keys/{keyId}`

Add or expose these if missing:

- `GET /admin/customers/{customerId}/usage?cursor=&limit=`
- `GET /admin/customers/{customerId}/ledger?cursor=&limit=`

These can delegate to existing repository methods that already support customer usage and credit ledger.

UI behavior in `PanelCustomers.js`:

- Replace read-only modal with tabs: Profile, Keys, Usage, Ledger.
- Profile tab allows status changes and display name update.
- Keys tab can revoke a customer key.
- Usage tab shows customer request usage with pagination.
- Ledger tab shows credit ledger and admin adjustments.
- Add destructive Delete Customer action with confirmation.
- Soft-delete removes the customer from list and revokes keys.
- Add credit adjustment form with reason and delta.

Checkpoints:

- [ ] Customer can be soft-deleted from the Customers page.
- [ ] Customer key can be revoked by admin.
- [ ] Customer status and display name can be updated.
- [ ] Customer credits can be adjusted with a reason.
- [ ] Customer detail shows usage records with model, time, status, tokens, cost, and request/key identifiers.
- [ ] Customer detail shows credit ledger entries.
- [ ] Deleted customers disappear from the active list.

## Track 4: Product Clarity

### Problems Covered

13. Rate Limits and Users & Groups functionality is unclear.

### Rate Limits

Definition:

Rate Limits is runtime traffic protection. It manages token bucket rules that match incoming relay requests by dimension and path. It is not billing budget, user group defaults, or routing fallback.

UI changes in `PanelRateLimits.js`:

- Rename section copy around "Rules" to "Runtime Rules".
- Show rule scope fields clearly:
  - Rule Name
  - Dimension
  - Path Pattern
  - Capacity
  - Refill/sec
  - Priority
  - Enabled
- Show active buckets as "currently matched traffic buckets".
- Add compact explanation in empty states, not long marketing copy.
- Keep actions limited to Create Rule, Delete Rule, Reset Buckets.
- Do not add complex policy builder features.

Checkpoints:

- [ ] User can understand that Rate Limits controls runtime request throttling.
- [ ] Rule fields map directly to backend rule fields.
- [ ] Active Buckets table describes observed bucket state, not configuration.
- [ ] Reset Buckets action clearly states it clears runtime counters, not rules.

### Users & Groups

Definition:

Users & Groups is B-end account administration. These users sign into the AI Gateway admin UI. These groups are account-level defaults and permissions. They are not routing groups, and they are not customers.

UI changes in `PanelUsers.js`:

- Rename page title to "Admin Users".
- Rename group section to "Account Groups".
- Add table labels that make role, account group, status, default RPM/TPM, and default budget clear.
- Allow admin update of user role, account group, and status if supported by existing `PUT /admin/users/{userId}`.
- Keep routing groups in `PanelGroups.js`.
- Keep customer accounts in `PanelCustomers.js`.

Checkpoints:

- [ ] Users page no longer looks like it manages routing groups.
- [ ] Account groups are labeled as admin account defaults.
- [ ] User status/role/group actions call account admin APIs.
- [ ] Customers remain separate from admin users.

## Cross-Page Navigation

Add lightweight deep-link behavior where it improves diagnosis:

- From Customers detail key row: open Usage with `keyId` or customer usage tab.
- From Usage detail customer field: open Customers detail.
- From Usage channel field: open Channels filtered/detail view if that panel supports it.
- From API Keys row: open Usage filtered by `keyId`.

Deep links can be hash query parameters such as `#usage?keyId=...` if the legacy router can support them without a large rewrite. If not, use direct method calls inside the current shell as a first implementation.

## Error Handling

All repaired actions must avoid silent failures:

- Destructive actions require confirmation.
- Buttons disable while pending.
- Success refreshes the relevant panel.
- Errors render inline near the action or at the top of the panel.
- `alert()` may remain as a fallback only when there is no inline container yet.

## Test Strategy

### Frontend Tests

Use Vitest and jsdom under `keel-samples/frontend/apps/ai-gateway`.

Required coverage:

- `PanelUsage.js`: query builder, page reset on filter change, group display mapping, detail identity fields.
- `PanelDashboard.js`: window label density, recent rows limited to 5, live mode method behavior.
- `PanelGroups.js`: alias render from saved routes, delete error/success rendering.
- `PanelKeys.js`: admin delete endpoint and non-recoverable secret setup copy.
- `PanelCustomers.js`: delete/revoke/update/credit buttons call correct admin endpoints and render usage/ledger tabs.
- `PanelRateLimits.js`: clarified table labels and reset wording.
- `PanelUsers.js`: account group wording and update action wiring.

### Backend Tests

Use Kotlin tests under `keel-samples/src/test/kotlin/com/keel/samples/aigateway`.

Required coverage:

- Token repository usage pagination returns historical pages and accurate total.
- Token repository filters combine with AND semantics.
- Usage group filtering uses logical routing group, while pool level remains diagnostic.
- Admin key delete/revoke works for keys owned by different users.
- Dashboard stats returns correct bucket metadata for 1h, 24h, 7d, and 30d.
- Customer soft delete revokes keys and hides the customer from active list.
- Customer admin usage and ledger routes return paginated data.
- Group aliases persist and render through the chosen canonical endpoint.

## Issue-To-Checkpoint Matrix

1. Usage filters only existing 200 and lacks pagination:
   - Track 1 service pagination and UI Prev/Next.
2. Usage group filter cannot match table group display:
   - Track 1 separate `routingGroupId/routingGroupName/poolLevelId`.
3. Usage filter conditions are incoherent:
   - Track 1 full query object and backend AND semantics.
4. Dashboard window charts are dense:
   - Track 2 bucket metadata and label thinning.
5. Dashboard auto-refresh should be global/default:
   - Track 2 global Live state and `setLiveMode`.
6. Channel Health and Recent layout are wrong:
   - Track 2 compact Health, fixed Recent 5 rows, horizontal scroll.
7. Dashboard should adapt to width changes:
   - Track 2 responsive grids.
8. Groups delete button is not useful:
   - Track 3 Groups delete state and clear backend errors.
9. Group alias routes list is always empty:
   - Track 3 canonical alias route source and persistence.
10. API Keys delete/clicks fail:
   - Track 3 admin key endpoints and visible button states.
11. Customers cannot be deleted:
   - Track 3 Customers admin actions.
12. No customer consumption/usage view:
   - Track 1 identity enrichment and Track 3 Customer Usage/Ledger tabs.
13. Rate Limits and Users & Groups are unclear:
   - Track 4 definitions, labels, and constrained actions.

## Implementation Boundaries

In scope:

- Legacy AI Gateway admin UI.
- Backend sample plugins required by the legacy UI.
- Focused tests for repaired behavior.
- Small shared UI utility updates when needed for tables and errors.

Out of scope:

- Replacing the legacy UI with React.
- Large redesign of visual language.
- New billing model or pricing engine.
- Complex rate-limit policy builder.
- Hard delete of customers or keys.
- Secret recovery for existing API keys.

## Definition of Done

- Every issue in the matrix has a passing checkpoint.
- Usage, Dashboard, Groups, API Keys, Customers, Rate Limits, and Users pages remain reachable in the legacy UI.
- Actions that mutate data have clear loading, success, and error feedback.
- Historical usage and customer-specific usage are accessible through paginated server-backed queries.
- Dashboard live refresh is controlled globally and works without panel-local toggles.
- The implementation includes frontend and backend tests for changed contracts.
