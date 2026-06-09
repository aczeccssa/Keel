# AI Gateway Frontend Quality Roadmap Step 2 Result

## Status

Step 2 is complete.

The frontend API contracts for AI Relay Manager Console and Customer Portal were validated against the running backend at `http://localhost:8080`, the live OpenAPI document at `/api/_system/docs/openapi.json`, and mock-backed Gradle integration tests for relay/customer success flows.

## Changes Made

- Added `npm run api:contracts` and `npm run api:contracts:matrix` in `keel-samples/frontend/package.json`.
- Added `keel-samples/frontend/scripts/verify-api-contracts.mjs`.
- Fixed `AiGatewayApi.register` to require backend field `displayName`.
- Fixed `CustomerPortalApi.register` to use backend field `displayName`.
- Fixed `CustomerPortalApi.createKey` to use backend field `routingGroupId` and return `CustomerKeyCreatedResponse`.
- Fixed customer auth state persistence to read the backend's flat `CustomerAuthResponse.email` field.
- Added unit coverage for the corrected request body fields.

## Compatibility Matrix

| Frontend method | Method | Path | Backend owner | Auth | Success shape | Failure behavior | Verification |
| --- | --- | --- | --- | --- | --- | --- | --- |
| AiGatewayApi.login | POST | `/api/plugins/account/v1/auth/login` | account | public | AuthResponse: accessToken, refreshToken, user | 401 empty/text or JSON error for invalid credentials | unit + openapi + runtime-login |
| AiGatewayApi.register | POST | `/api/plugins/account/v1/auth/register` | account | public | AuthResponse: accessToken, refreshToken, user | 400/409 empty/text or JSON error for invalid or duplicate account | unit + openapi |
| AiGatewayApi.refresh | POST | `/api/plugins/account/v1/auth/refresh` | account | public refresh token | AuthResponse: accessToken, refreshToken, user | 401 empty/text or JSON error for invalid refresh token | openapi + runtime-invalid-refresh |
| AiGatewayApi.navCounts | GET | `/api/plugins/airelay/admin/nav-counts` | airelay | manager token sent; backend currently open | NavCountsResponse: customers | JSON error if backend dependency unavailable | openapi + runtime-get |
| AiGatewayApi.usageGlobal | GET | `/api/plugins/token/admin/usage/global` | token | account admin JWT | UsageSnapshot | 401/403 empty/text or JSON error without admin JWT | openapi + runtime-auth |
| AiGatewayApi.usageRecords | GET | `/api/plugins/token/admin/usage/records` | token | account admin JWT | UsageListResponse: records, total | 401/403 empty/text or JSON error without admin JWT | openapi + runtime-auth |
| AiGatewayApi.channels | GET | `/api/plugins/airelay/admin/channels` | airelay | manager token sent; backend currently open | ChannelListResponse: channels | JSON error if channel store unavailable | openapi + runtime-get |
| AiGatewayApi.groups | GET | `/api/plugins/airelay/admin/groups` | airelay | manager token sent; backend currently open | GroupListResponse: groups | JSON error if channel store unavailable | openapi + runtime-get |
| AiGatewayApi.keys | GET | `/api/plugins/token/admin/keys` | token | account admin JWT | ApiKeyListResponse: keys, total | 401/403 empty/text or JSON error without admin JWT | openapi + runtime-auth |
| AiGatewayApi.pricing | GET | `/api/plugins/airelay/admin/pricing` | airelay | manager token sent; backend currently open | PricingListResponse: pricings | JSON error if channel store unavailable | openapi + runtime-get |
| AiGatewayApi.pools | GET | `/api/plugins/airelay/admin/pools` | airelay | manager token sent; backend currently open | PoolChainSnapshot: chains | JSON error if pool manager unavailable | openapi + runtime-get |
| AiGatewayApi.rateLimitRules | GET | `/api/plugins/riskcontrol/v1/rules` | riskcontrol | account admin JWT | RateLimitRuleListResponse: rules, total | 401/403 empty/text or JSON error without admin JWT | openapi + runtime-auth |
| AiGatewayApi.users | GET | `/api/plugins/account/admin/users` | account | account admin JWT | AccountUserListResponse: users, total | 401/403 empty/text or JSON error without admin JWT | openapi + runtime-auth |
| AiGatewayApi.customers | GET | `/api/plugins/customer-portal/admin/customers` | customer-portal | account admin JWT | CustomerListResponse: customers, total | 401/403 empty/text or JSON error without admin JWT | openapi + runtime-auth |
| AiGatewayApi.redemptionCodes | GET | `/api/plugins/customer-portal/admin/codes` | customer-portal | account admin JWT | RedemptionCodeListResponse: codes, total | 401/403 empty/text or JSON error without admin JWT | openapi + runtime-auth |
| AiGatewayApi.chatCompletions | POST | `/api/plugins/airelay/v1/chat/completions` | airelay | AI gateway API key | OpenAI chat completion envelope | 400/401/402/403/404/429/503 empty/text or JSON error | openapi + runtime-safe-relay + gradle integration |
| AiGatewayApi.responses | POST | `/api/plugins/airelay/v1/responses` | airelay | AI gateway API key | OpenAI Responses envelope | 400/401/402/403/404/429/503 empty/text or JSON error | openapi + runtime-safe-relay + gradle integration |
| AiGatewayApi.messages | POST | `/api/plugins/airelay/v1/messages` | airelay | AI gateway API key or x-api-key | Anthropic Messages envelope | 400/401/402/403/404/429/503 empty/text or JSON error | openapi + runtime-safe-relay + gradle integration |
| CustomerPortalApi.login | POST | `/api/plugins/customer-portal/v1/customer/auth/login` | customer-portal | public | CustomerAuthResponse: accessToken, refreshToken, customerId, email, displayName | 401 empty/text or JSON error for invalid credentials | unit + openapi |
| CustomerPortalApi.register | POST | `/api/plugins/customer-portal/v1/customer/auth/register` | customer-portal | public | CustomerAuthResponse: accessToken, refreshToken, customerId, email, displayName | 400/409 empty/text or JSON error for invalid or duplicate customer | unit + openapi + runtime-customer |
| CustomerPortalApi.refresh | POST | `/api/plugins/customer-portal/v1/customer/auth/refresh` | customer-portal | public refresh token | CustomerAuthResponse | 401 empty/text or JSON error for invalid refresh token | openapi + runtime-invalid-refresh |
| CustomerPortalApi.profile | GET | `/api/plugins/customer-portal/v1/customer/auth/me` | customer-portal | customer JWT | CustomerProfile | 401 empty/text or JSON error without customer JWT | openapi + runtime-customer |
| CustomerPortalApi.credits | GET | `/api/plugins/customer-portal/v1/customer/credits` | customer-portal | customer JWT | CreditBalanceResponse: balanceCredits | 401 empty/text or JSON error without customer JWT | openapi + runtime-customer |
| CustomerPortalApi.creditLedger | GET | `/api/plugins/customer-portal/v1/customer/credits/ledger` | customer-portal | customer JWT | CreditLedgerResponse: entries, total, nextCursor | 401 empty/text or JSON error without customer JWT | openapi + runtime-customer |
| CustomerPortalApi.redeem | POST | `/api/plugins/customer-portal/v1/customer/credits/redeem` | customer-portal | customer JWT | RedeemCodeResponse | 400/404/409/410 JSON error for invalid code | openapi + runtime-customer |
| CustomerPortalApi.usage | GET | `/api/plugins/customer-portal/v1/customer/usage` | customer-portal | customer JWT | CustomerUsageListResponse: records, total | 401 empty/text or JSON error without customer JWT | openapi + runtime-customer |
| CustomerPortalApi.keys | GET | `/api/plugins/customer-portal/v1/customer/keys` | customer-portal | customer JWT | CustomerKeyListResponse: keys, total | 401 empty/text or JSON error without customer JWT | openapi + runtime-customer |
| CustomerPortalApi.createKey | POST | `/api/plugins/customer-portal/v1/customer/keys` | customer-portal | customer JWT | CustomerKeyCreatedResponse: key, rawKey | 400/401 empty/text or JSON error for invalid key request | unit + openapi + runtime-customer |
| CustomerPortalApi.deleteKey | DELETE | `/api/plugins/customer-portal/v1/customer/keys/{keyId}` | customer-portal | customer JWT | CustomerKeyView | 401/403/404 empty/text or JSON error | openapi + runtime-customer |
| CustomerPortalApi.pricing | GET | `/api/plugins/customer-portal/v1/customer/pricing` | customer-portal | customer JWT | ModelPricingListResponse: summaries | 401 empty/text or JSON error without customer JWT | openapi + runtime-customer |
| CustomerPortalApi.listRelayGroups | GET | `/api/plugins/airelay/admin/groups` | airelay | customer token sent; backend currently open | GroupListResponse: groups | JSON error if channel store unavailable | unit + openapi + runtime-customer |
| LegacyAiGateway.PanelProviders.create | POST | `/api/plugins/airelay/admin/channels` | airelay | manager token sent; backend currently open | ChannelView | 400/503 JSON error for invalid channel | openapi |
| LegacyAiGateway.PanelProviders.update | PUT | `/api/plugins/airelay/admin/channels/{channelId}` | airelay | manager token sent; backend currently open | ChannelView | 400/404/503 JSON error | openapi |
| LegacyAiGateway.PanelProviders.delete | DELETE | `/api/plugins/airelay/admin/channels/{channelId}` | airelay | manager token sent; backend currently open | DeleteChannelResponse | 404/503 JSON error | openapi |
| LegacyAiGateway.PanelProviders.toggle | POST | `/api/plugins/airelay/admin/channels/{channelId}/enabled/{enabled}` | airelay | manager token sent; backend currently open | ToggleChannelResponse | 400/404/503 JSON error | openapi |
| LegacyAiGateway.PanelProviders.test | POST | `/api/plugins/airelay/admin/channels/{channelId}/test` | airelay | manager token sent; backend currently open | ChannelTestResponse | 404/503 JSON error | openapi |
| LegacyAiGateway.PanelProviders.discoverDraft | POST | `/api/plugins/airelay/admin/channels/discover-models` | airelay | manager token sent; backend currently open | DiscoverModelsResponse | 400/503 JSON error | openapi |
| LegacyAiGateway.PanelProviders.discoverSaved | POST | `/api/plugins/airelay/admin/channels/{channelId}/discover-models` | airelay | manager token sent; backend currently open | DiscoverModelsResponse | 404/503 JSON error | openapi |
| LegacyAiGateway.PanelProviders.testModel | POST | `/api/plugins/airelay/admin/channels/{channelId}/test-model` | airelay | manager token sent; backend currently open | ChannelTestResponse | 400/404/503 JSON error | openapi |
| LegacyAiGateway.PanelKeys.create | POST | `/api/plugins/token/v1/keys` | token | account JWT | ApiKeyCreatedResponse: key, rawKey | 400/401 empty/text or JSON error | openapi + runtime-token-key |
| LegacyAiGateway.PanelKeys.delete | DELETE | `/api/plugins/token/v1/keys/{keyId}` | token | account JWT | ApiKeyView | 401/403/404 empty/text or JSON error | openapi + runtime-token-key |
| LegacyAiGateway.PanelGroups.create | POST | `/api/plugins/airelay/admin/groups` | airelay | manager token sent; backend currently open | GroupView | 400/409/503 JSON error | openapi |
| LegacyAiGateway.PanelGroups.update | PUT | `/api/plugins/airelay/admin/groups/{groupId}` | airelay | manager token sent; backend currently open | GroupView | 400/404/503 JSON error | openapi |
| LegacyAiGateway.PanelGroups.delete | DELETE | `/api/plugins/airelay/admin/groups/{groupId}` | airelay | manager token sent; backend currently open | DeleteGroupResponse | 400/404/409/503 JSON error | openapi |
| LegacyAiGateway.PanelGroups.attachMembership | POST | `/api/plugins/airelay/admin/groups/{groupId}/memberships` | airelay | manager token sent; backend currently open | GroupMembershipView | 400/404/503 JSON error | openapi |
| LegacyAiGateway.PanelGroups.updateMembership | PUT | `/api/plugins/airelay/admin/groups/{groupId}/memberships/{channelId}` | airelay | manager token sent; backend currently open | GroupMembershipView | 404/503 JSON error | openapi |
| LegacyAiGateway.PanelGroups.detachMembership | DELETE | `/api/plugins/airelay/admin/groups/{groupId}/memberships/{channelId}` | airelay | manager token sent; backend currently open | DeleteChannelResponse | 404/503 JSON error | openapi |
| LegacyAiGateway.PanelPricing.upsert | PUT | `/api/plugins/airelay/admin/pricing` | airelay | manager token sent; backend currently open | PricingView | 400/503 JSON error | openapi |
| LegacyAiGateway.PanelPricing.delete | DELETE | `/api/plugins/airelay/admin/pricing/{model}` | airelay | manager token sent; backend currently open | DeletePricingResponse | 404/503 JSON error | openapi |
| LegacyAiGateway.PanelRateLimits.create | POST | `/api/plugins/riskcontrol/v1/rules` | riskcontrol | account admin JWT | RateLimitRuleView | 400/401/403 JSON error | openapi |
| LegacyAiGateway.PanelRateLimits.update | PUT | `/api/plugins/riskcontrol/v1/rules/{ruleId}` | riskcontrol | account admin JWT | RateLimitRuleView | 400/401/403 JSON error | openapi |
| LegacyAiGateway.PanelRateLimits.delete | DELETE | `/api/plugins/riskcontrol/v1/rules/{ruleId}` | riskcontrol | account admin JWT | ResetRateLimitResponse | 401/403/404 JSON error | openapi |
| LegacyAiGateway.PanelUsers.createGroup | POST | `/api/plugins/account/admin/groups` | account | account admin JWT | AccountGroupView | 400/401/403/409 JSON error | openapi |
| LegacyAiGateway.PanelCustomers.detail | GET | `/api/plugins/customer-portal/admin/customers/{customerId}` | customer-portal | account admin JWT | CustomerAdminDetailView | 401/403/404 JSON error | openapi |
| LegacyAiGateway.PanelRedemptionCodes.create | POST | `/api/plugins/customer-portal/admin/codes` | customer-portal | account admin JWT | RedemptionCodeView | 400/401/403/409 JSON error | openapi |
| LegacyAiGateway.PanelRedemptionCodes.delete | DELETE | `/api/plugins/customer-portal/admin/codes/{code}` | customer-portal | account admin JWT | DeleteResponse | 401/403/404 JSON error | openapi |
| LegacyCustomerPortal.oauthStub | POST | `/api/plugins/customer-portal/v1/customer/auth/oauth/stub` | customer-portal | public | CustomerAuthResponse | 400 JSON error for invalid OAuth stub request | openapi |
| LegacyCustomerPortal.keyDetail | GET | `/api/plugins/customer-portal/v1/customer/keys/{keyId}` | customer-portal | customer JWT | CustomerKeyView | 401/403/404 JSON error | openapi |
| LegacyCustomerPortal.keyUpdate | PUT | `/api/plugins/customer-portal/v1/customer/keys/{keyId}` | customer-portal | customer JWT | CustomerKeyView | 400/401/403/404 JSON error | openapi |

## Verification

Commands run:

```bash
npm run test:run
npm run typecheck
npm run build:customer
npm run api:contracts
./gradlew :keel-test-suite:test --tests 'com.keel.test.kernel.AiGatewayPluginIntegrationTest' --tests 'com.keel.test.kernel.AliasCreditIntegrationTest'
```

Results:

- `npm run api:contracts` verified 59 frontend API contracts against `http://localhost:8080`.
- Frontend unit tests passed. Existing React `act(...)` warnings remain in panel tests and were not introduced by this step.
- Frontend typecheck passed.
- Customer frontend build passed.
- Gradle relay/customer integration tests passed.
- Gradle still reports existing npm audit warnings: 5 vulnerabilities, 4 moderate and 1 critical.

## Notes For Step 3

- The current served Step 1 app remains the migrated legacy UI; the TypeScript API clients are now aligned for the Step 3 React redesign.
- Live relay POST success probes are intentionally not sent by `api:contracts` because the user's 8080 server can be configured with real upstream provider credentials. Safe runtime relay checks validate OpenAPI presence, API-key auth, model listing, and invalid-model failure; successful relay envelopes are covered by mock-backed Gradle integration tests.
- Several AIRelay `/admin` routes are currently open at the backend while the frontend still sends a token. Step 3 should preserve token sending, and any decision to enforce admin auth on AIRelay admin routes should be a backend security task, not a visual redesign side effect.
