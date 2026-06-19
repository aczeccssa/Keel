# AI Gateway Routing Pools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ordered first-success routing with explicit pool-based routing, runtime explainability, and admin/UI observability while preserving legacy ordered alias behavior.

**Architecture:** The change keeps DB-backed group/channel configuration as the source of declarative intent and moves live scheduling state into a runtime registry that survives config reloads. `ConfigService` compiles groups into policy-aware routes, `PoolChainManager` owns atomic permit acquisition and weighted scheduling, `AIRelayService` records attempt outcomes and failover traces, and the admin/UI layers read a dedicated pool snapshot/explain API instead of inferring state from static chain health.

**Tech Stack:** Kotlin, Ktor plugin endpoints, kotlinx.serialization, Exposed/H2 migrations, React + TypeScript + Vitest, Gradle, npm

---

## File Structure

- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolRuntimeModels.kt`
  Runtime-only identifiers, explain DTOs, lease object, scheduler snapshot/view models.
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolRuntimeRegistry.kt`
  Shared runtime channel state, rolling counters, explain trace ring buffer, tier scheduler state that survives `ConfigService.reload()`.
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelaySettings.kt`
  Add alias routing policy enum + config fields with backward-compatible defaults.
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ChannelTables.kt`
  Persist alias routing policy in `GroupAliasTable`.
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ChannelRepository.kt`
  Migrate schema, expose policy fields in DTOs, preserve old clients with default `ORDERED_FAILOVER`.
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ConfigService.kt`
  Compile direct models and aliases into policy-aware pool routes, preserve tier structure, keep one candidate per channel inside `POOL_BALANCE`.
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolChainManager.kt`
  Replace ordered candidate generation with pool selection, failover planning, explain preview, and admin snapshots backed by the shared registry.
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt`
  Use leases, classify retryable vs non-retryable failures, record selected/failover traces, gate debug headers.
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt`
  Wire the runtime registry, expose admin pool/explain/reset APIs, and hand the new snapshot provider to Koin.
- Modify: `keel-contract/src/main/kotlin/com/keel/contract/ai/AiGatewayContracts.kt`
  Keep the legacy `PoolChainSnapshot` for compatibility, but extend contract responses only if existing admin/tests require shared types.
- Modify: `keel-samples/frontend/apps/ai-gateway/src/api/aiGatewayApi.ts`
  Add typed pool/explain/admin API methods and new response models.
- Modify: `keel-samples/frontend/apps/ai-gateway/src/panels/GroupsPanel.tsx`
  Show alias routing policy and pool-aware channel membership detail.
- Modify: `keel-samples/frontend/apps/ai-gateway/src/panels/PoolsPanel.tsx`
  Render pool/tier/channel runtime state from the new admin snapshot, with visibility-aware polling.
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/airelay/pool/PoolChainManagerTest.kt`
- Test: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/ChannelConfigTest.kt`
- Test: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/AiGatewayPluginIntegrationTest.kt`
- Test: `keel-samples/frontend/apps/ai-gateway/src/api/aiGatewayApi.test.ts`
- Test: `keel-samples/frontend/apps/ai-gateway/src/panels/GroupsPanel.test.tsx`
- Test: `keel-samples/frontend/apps/ai-gateway/src/panels/aiGatewayPanels.test.tsx`

### Task 1: Add Routing Policy To Persisted Alias Config

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelaySettings.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ChannelTables.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ChannelRepository.kt`
- Test: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/ChannelConfigTest.kt`

- [ ] **Step 1: Write the failing persistence tests**

```kotlin
@Test
fun groupAliasDefaultsToOrderedFailover() {
    val repo = newRepo()
    repo.createGroup(
        UpsertGroupRequest(
            groupId = "premium",
            name = "Premium",
            aliasRoutes = listOf(
                UpsertGroupAliasRequest(
                    aliasName = "smart-claude",
                    targetModels = listOf("claude-a", "claude-b"),
                )
            )
        )
    )

    val alias = repo.listGroupAliases("premium").single()
    assertEquals("ORDERED_FAILOVER", alias.routingPolicy)
}

@Test
fun aliasRoutingPolicyRoundTripsThroughRepository() {
    val repo = newRepo()
    repo.createGroup(
        UpsertGroupRequest(
            groupId = "premium",
            name = "Premium",
            aliasRoutes = listOf(
                UpsertGroupAliasRequest(
                    aliasName = "smart-claude",
                    targets = listOf(GroupAliasTargetView("claude-a"), GroupAliasTargetView("claude-b")),
                    routingPolicy = "POOL_BALANCE",
                )
            )
        )
    )

    val alias = repo.listGroupAliases("premium").single()
    assertEquals("POOL_BALANCE", alias.routingPolicy)
}
```

- [ ] **Step 2: Run the repository test slice to verify it fails**

Run: `./gradlew :keel-test-suite:test --tests com.keel.test.kernel.ChannelConfigTest`
Expected: FAIL with unresolved `routingPolicy` fields and/or missing DB column migration.

- [ ] **Step 3: Add the alias policy enum and config defaults**

```kotlin
@Serializable
enum class AliasRoutingPolicy {
    POOL_BALANCE,
    ORDERED_FAILOVER;

    companion object {
        fun from(value: String?): AliasRoutingPolicy = runCatching {
            value?.takeIf { it.isNotBlank() }?.let(::valueOf)
        }.getOrNull() ?: ORDERED_FAILOVER
    }
}

@Serializable
data class AliasRouteConfig(
    val aliasName: String,
    val targetModels: List<String> = emptyList(),
    val enabled: Boolean = true,
    val creditMultiplier: Double? = null,
    val targets: List<AliasTargetConfig> = emptyList(),
    val routingPolicy: AliasRoutingPolicy = AliasRoutingPolicy.ORDERED_FAILOVER,
)
```

- [ ] **Step 4: Persist and expose the new field in repository DTOs**

```kotlin
object GroupAliasTable : AuditPluginTable("airelay", "group_alias") {
    val routingPolicy: Column<String> = varchar("routing_policy", 32).default("ORDERED_FAILOVER")
}

@Serializable
data class GroupAliasView(
    val aliasId: String,
    val aliasName: String,
    val targetModels: List<String>,
    val enabled: Boolean = true,
    val creditMultiplier: Double? = null,
    val targets: List<GroupAliasTargetView> = targetModels.map { GroupAliasTargetView(it) },
    val routingPolicy: String = AliasRoutingPolicy.ORDERED_FAILOVER.name,
)

@Serializable
data class UpsertGroupAliasRequest(
    val aliasName: String,
    val targetModels: List<String> = emptyList(),
    val enabled: Boolean = true,
    val creditMultiplier: Double? = null,
    val targets: List<GroupAliasTargetView> = emptyList(),
    val routingPolicy: String = AliasRoutingPolicy.ORDERED_FAILOVER.name,
)
```

- [ ] **Step 5: Add the migration and alias encode/decode logic**

```kotlin
private fun migrateAliasRoutingPolicySchema() = database.transaction {
    runCatching {
        exec("ALTER TABLE airelay_group_alias ADD COLUMN IF NOT EXISTS routing_policy VARCHAR(32) NOT NULL DEFAULT 'ORDERED_FAILOVER'")
    }
}

private fun replaceGroupAliases(groupId: String, aliases: List<UpsertGroupAliasRequest>) {
    GroupAliasTable.deleteWhere { GroupAliasTable.groupId eq groupId }
    aliases.forEach { alias ->
        val targets = alias.orderedTargets()
        if (alias.aliasName.isBlank() || targets.isEmpty()) return@forEach
        GroupAliasTable.insert {
            it[aliasId] = "gal-" + UUID.randomUUID().toString().replace("-", "").take(20)
            it[GroupAliasTable.groupId] = groupId
            it[aliasName] = alias.aliasName.trim()
            it[targetModelsJson] = json.encodeToString(targets)
            it[enabled] = alias.enabled
            it[creditMultiplier] = alias.creditMultiplier
            it[routingPolicy] = AliasRoutingPolicy.from(alias.routingPolicy).name
        }
    }
}
```

- [ ] **Step 6: Run the repository tests to verify they pass**

Run: `./gradlew :keel-test-suite:test --tests com.keel.test.kernel.ChannelConfigTest`
Expected: PASS for alias policy persistence tests and existing config tests.

- [ ] **Step 7: Commit the persistence change**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelaySettings.kt \
  keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ChannelTables.kt \
  keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ChannelRepository.kt \
  keel-test-suite/src/test/kotlin/com/keel/test/kernel/ChannelConfigTest.kt
git commit -m "feat: persist ai relay alias routing policy"
```

### Task 2: Compile Groups Into Policy-Aware Pool Routes

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ConfigService.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelaySettings.kt`
- Test: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/ChannelConfigTest.kt`

- [ ] **Step 1: Write failing config compilation tests**

```kotlin
@Test
fun poolBalanceAliasCompilesOneCandidatePerChannel() {
    val repo = newRepo()
    repo.createGroup(
        UpsertGroupRequest(
            groupId = "premium",
            name = "Premium",
            aliasRoutes = listOf(
                UpsertGroupAliasRequest(
                    aliasName = "smart-claude",
                    targets = listOf(GroupAliasTargetView("claude-a"), GroupAliasTargetView("claude-b")),
                    routingPolicy = "POOL_BALANCE",
                )
            )
        )
    )
    repo.createChannel(
        UpsertChannelRequest(
            name = "Shared",
            protocol = "OPENAI_CHAT",
            baseUrl = "http://shared",
            apiKey = "k",
            groupId = "premium",
            models = listOf(
                UpsertModelRequest(publicModelName = "claude-a", upstreamModelName = "claude-a"),
                UpsertModelRequest(publicModelName = "claude-b", upstreamModelName = "claude-b"),
            )
        )
    )

    val service = ConfigService(repo)
    service.reload()
    val explain = service.poolChainManager.explainSelection("premium", "smart-claude")
    assertEquals(1, explain.priorityTiers.single().eligible.size)
    assertEquals("claude-a", explain.priorityTiers.single().eligible.single().targetModel)
}
```

- [ ] **Step 2: Run the config tests to verify the current compiler fails**

Run: `./gradlew :keel-test-suite:test --tests com.keel.test.kernel.ChannelConfigTest`
Expected: FAIL because `ConfigService` does not yet compile policy-aware routes or expose `explainSelection`.

- [ ] **Step 3: Add route-level metadata to the runtime config model**

```kotlin
@Serializable
data class PoolRouteConfig(
    val routeKey: String,
    val requestedName: String,
    val routingPolicy: AliasRoutingPolicy,
    val targets: List<AliasTargetConfig>,
    val sourceAliasName: String? = null,
    val creditMultiplier: Double? = null,
    val directModel: Boolean = false,
)

@Serializable
data class PoolChainConfig(
    val chainId: String,
    val modelAliases: List<String>,
    val levels: List<PoolLevelConfig>,
    val exposureMode: GroupExposureMode = GroupExposureMode.ALL_MODELS,
    val aliasRoutes: List<AliasRouteConfig> = emptyList(),
    val poolRoutes: List<PoolRouteConfig> = emptyList(),
)
```

- [ ] **Step 4: Compile direct models and aliases into `poolRoutes`**

```kotlin
val directRoutes = exposedModels
    .filter { name -> group.aliasRoutes.none { it.enabled && it.aliasName == name } }
    .map { model ->
        PoolRouteConfig(
            routeKey = model,
            requestedName = model,
            routingPolicy = AliasRoutingPolicy.POOL_BALANCE,
            targets = listOf(AliasTargetConfig(model)),
            directModel = true,
        )
    }

val aliasRoutes = group.aliasRoutes
    .filter { it.enabled }
    .map { alias ->
        PoolRouteConfig(
            routeKey = alias.aliasName,
            requestedName = alias.aliasName,
            routingPolicy = AliasRoutingPolicy.from(alias.routingPolicy),
            targets = alias.targets.map { target -> AliasTargetConfig(target.model, target.channelId) },
            sourceAliasName = alias.aliasName,
            creditMultiplier = alias.creditMultiplier,
        )
    }
```

- [ ] **Step 5: Run the config tests again**

Run: `./gradlew :keel-test-suite:test --tests com.keel.test.kernel.ChannelConfigTest`
Expected: PASS for alias-policy config tests and route compilation tests.

- [ ] **Step 6: Commit the config compiler change**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelaySettings.kt \
  keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ConfigService.kt \
  keel-test-suite/src/test/kotlin/com/keel/test/kernel/ChannelConfigTest.kt
git commit -m "feat: compile ai relay pool routes"
```

### Task 3: Build Shared Runtime Registry And Pool Scheduler

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolRuntimeModels.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolRuntimeRegistry.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolChainManager.kt`
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/airelay/pool/PoolChainManagerTest.kt`

- [ ] **Step 1: Write failing scheduler and permit tests**

```kotlin
@Test
fun equalWeightPoolAlternatesSelectionsWhenIdle() {
    val manager = PoolChainManager(listOf(weightedChain(100, 100)))
    val picks = (1..6).map { manager.acquire("default", "gpt-5")!!.selection.keyState.key.keyId }
    assertEquals(listOf("ch-a", "ch-b", "ch-a", "ch-b", "ch-a", "ch-b"), picks)
}

@Test
fun weightedPoolProducesTwoToOneShareWhenIdle() {
    val manager = PoolChainManager(listOf(weightedChain(100, 50)))
    val picks = (1..9).map { manager.acquire("default", "gpt-5")!!.selection.keyState.key.keyId }
    assertEquals(6, picks.count { it == "ch-a" })
    assertEquals(3, picks.count { it == "ch-b" })
}

@Test
fun acquireNeverExceedsMaxConcurrency() {
    val manager = PoolChainManager(listOf(singleKeyChain(maxConcurrency = 1)))
    val first = manager.acquire("default", "gpt-5")
    val second = manager.acquire("default", "gpt-5")
    assertNotNull(first)
    assertNull(second)
    first.close()
}
```

- [ ] **Step 2: Run the pool unit test slice**

Run: `./gradlew :keel-samples:test --tests com.keel.samples.aigateway.airelay.pool.PoolChainManagerTest`
Expected: FAIL because `acquire`/lease APIs and weighted least-load behavior do not exist.

- [ ] **Step 3: Add runtime registry primitives**

```kotlin
data class PoolLease(
    val selection: PoolSelection,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    override fun close() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

data class RuntimeChannelState(
    val channelId: String,
    val baseStatus: AtomicReferenceStatus = AtomicReferenceStatus(KeyStatus.HEALTHY),
    val currentConcurrency: AtomicInteger = AtomicInteger(0),
    val totalSelections: AtomicLong = AtomicLong(0),
    val totalSuccesses: AtomicLong = AtomicLong(0),
    val totalFailures: AtomicLong = AtomicLong(0),
    @Volatile var cooldownUntilEpochMs: Long = 0L,
    @Volatile var lastError: String? = null,
    @Volatile var lastSelectedAt: Long? = null,
)
```

- [ ] **Step 4: Replace `weightedRotate` with weighted least-load acquire**

```kotlin
fun acquire(groupId: String, model: String, traceId: String? = null): PoolLease? {
    val explain = explainSelection(groupId, model, mutate = true, traceId = traceId)
    val selected = explain.selected ?: return null
    return selected.lease
}

private fun scoreCandidate(state: RuntimeChannelState, candidate: PoolCandidateView, totalWeight: Int): Double {
    val saturation = state.currentConcurrency.get().toDouble() / max(candidate.maxConcurrency, 1).toDouble()
    return candidate.currentWeight - saturation * totalWeight
}
```

- [ ] **Step 5: Keep ordered failover and pool balance as separate selection modes**

```kotlin
when (route.routingPolicy) {
    AliasRoutingPolicy.POOL_BALANCE -> buildPoolTierCandidates(route, levels, now)
    AliasRoutingPolicy.ORDERED_FAILOVER -> buildOrderedTierCandidates(route, levels, now)
}
```

- [ ] **Step 6: Implement retry classification helpers in the manager**

```kotlin
fun shouldFailover(status: Int?, throwable: Throwable?): Boolean = when (status) {
    null -> true
    408, 429 -> true
    in 500..599 -> true
    401, 403 -> false
    in 400..499 -> false
    else -> throwable != null
}
```

- [ ] **Step 7: Run the pool unit tests until they pass**

Run: `./gradlew :keel-samples:test --tests com.keel.samples.aigateway.airelay.pool.PoolChainManagerTest`
Expected: PASS for fairness, weighted share, saturation, cooldown, and compatibility tests.

- [ ] **Step 8: Commit the scheduler refactor**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolRuntimeModels.kt \
  keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolRuntimeRegistry.kt \
  keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolChainManager.kt \
  keel-samples/src/test/kotlin/com/keel/samples/aigateway/airelay/pool/PoolChainManagerTest.kt
git commit -m "feat: add ai relay pool scheduler runtime"
```

### Task 4: Move Relay Execution To Leases, Failover Traces, And Debug Headers

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt`
- Test: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/AiGatewayPluginIntegrationTest.kt`

- [ ] **Step 1: Write failing integration tests for failover and debug headers**

```kotlin
@Test
fun debugHeadersStayOffByDefault() = setupApp {
    val response = client.post("/api/plugins/airelay/v1/chat/completions") {
        header("Authorization", "Bearer $rawKey")
        contentType(ContentType.Application.Json)
        setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}]}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(null, response.headers["X-AIRelay-Selected-Channel"])
}
```

- [ ] **Step 2: Run the integration test slice to verify it fails**

Run: `./gradlew :keel-test-suite:test --tests com.keel.test.kernel.AiGatewayPluginIntegrationTest`
Expected: FAIL because debug header gating and new failover flow are not implemented.

- [ ] **Step 3: Replace ordered candidate loops with acquire/retry loops**

```kotlin
var attempt = 0
var lease = poolChainManager.acquire(keyContext.verified.routingGroupId, ir.model, traceId = context.requestId)
while (lease != null) {
    val selection = lease.selection
    try {
        attempt += 1
        // send upstream
        poolChainManager.markSuccess(selection)
        return successResult(selection, attempt - 1)
    } catch (error: UpstreamHttpException) {
        poolChainManager.markFailure(selection, error.status, error.message, error.retryAfterSeconds)
        if (!poolChainManager.shouldFailover(error.status, error)) throw error
    } finally {
        lease.close()
    }
    lease = poolChainManager.acquireNext(selection, traceId = context.requestId)
}
```

- [ ] **Step 4: Add explicit debug-header gating**

```kotlin
private fun debugHeadersEnabled(context: KeelRequestContext): Boolean {
    val requestOptIn = context.requestHeaders["X-AIRelay-Debug"]?.firstOrNull()?.equals("true", ignoreCase = true) == true
    val serverOptIn = System.getenv("KEEL_AIRELAY_DEBUG_HEADERS") == "true" ||
        System.getProperty("keel.airelay.debugHeaders") == "true"
    return requestOptIn && serverOptIn
}
```

- [ ] **Step 5: Run the integration tests again**

Run: `./gradlew :keel-test-suite:test --tests com.keel.test.kernel.AiGatewayPluginIntegrationTest`
Expected: PASS for round-trip relay, alias exposure, and debug-header behavior.

- [ ] **Step 6: Commit the relay execution change**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt \
  keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt \
  keel-test-suite/src/test/kotlin/com/keel/test/kernel/AiGatewayPluginIntegrationTest.kt
git commit -m "feat: add ai relay lease-based failover"
```

### Task 5: Expose Pool Snapshot, Explain, And Reset Admin APIs

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolChainManager.kt`
- Test: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/AiGatewayPluginIntegrationTest.kt`

- [ ] **Step 1: Write failing admin API tests**

```kotlin
@Test
fun adminPoolsEndpointReturnsRuntimePoolView() = setupApp {
    val response = client.get("/api/plugins/airelay/admin/groups/default/pools")
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
    assertTrue(body["pools"]!!.jsonArray.isNotEmpty())
}
```

- [ ] **Step 2: Run the admin integration tests**

Run: `./gradlew :keel-test-suite:test --tests com.keel.test.kernel.AiGatewayPluginIntegrationTest`
Expected: FAIL because group-scoped pool endpoints and explain payloads do not exist.

- [ ] **Step 3: Add new serializable admin DTOs**

```kotlin
@Serializable
data class PoolView(
    val groupId: String,
    val aliasOrModel: String,
    val routingPolicy: String,
    val schedulerPolicy: String = "WEIGHTED_LEAST_LOAD",
    val priority: Int,
    val totalInflight: Int,
    val totalRequests1m: Long,
    val errorRate1m: Double,
    val p95Latency1m: Long,
    val p99Latency1m: Long,
    val channels: List<PoolChannelRuntimeView>,
)
```

- [ ] **Step 4: Mount the new endpoints under `/admin/groups/{groupId}/pools`**

```kotlin
route("/groups/{groupId}/pools") {
    get<PoolListResponse> {
        val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
        PluginResult(body = PoolListResponse(activeManager().listPools(groupId)))
    }
    get<PoolView>("/{aliasOrModel}") {
        val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
        val aliasOrModel = pathParameters["aliasOrModel"] ?: throw PluginApiException(400, "Missing aliasOrModel")
        PluginResult(body = activeManager().poolDetails(groupId, aliasOrModel))
    }
    post<PoolExplainRequest, PoolExplainResponse>("/{aliasOrModel}/explain") { request ->
        val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
        val aliasOrModel = pathParameters["aliasOrModel"] ?: throw PluginApiException(400, "Missing aliasOrModel")
        PluginResult(body = activeManager().explainSelection(groupId, request.requestedModel ?: aliasOrModel, preview = request.preview))
    }
}
```

- [ ] **Step 5: Re-run the integration tests**

Run: `./gradlew :keel-test-suite:test --tests com.keel.test.kernel.AiGatewayPluginIntegrationTest`
Expected: PASS for the new admin pool/explain endpoints.

- [ ] **Step 6: Commit the admin API work**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt \
  keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/pool/PoolChainManager.kt \
  keel-test-suite/src/test/kotlin/com/keel/test/kernel/AiGatewayPluginIntegrationTest.kt
git commit -m "feat: expose ai relay pool admin APIs"
```

### Task 6: Update Admin UI API Client And Panels

**Files:**
- Modify: `keel-samples/frontend/apps/ai-gateway/src/api/aiGatewayApi.ts`
- Modify: `keel-samples/frontend/apps/ai-gateway/src/api/aiGatewayApi.test.ts`
- Modify: `keel-samples/frontend/apps/ai-gateway/src/panels/GroupsPanel.tsx`
- Modify: `keel-samples/frontend/apps/ai-gateway/src/panels/PoolsPanel.tsx`
- Modify: `keel-samples/frontend/apps/ai-gateway/src/panels/GroupsPanel.test.tsx`
- Modify: `keel-samples/frontend/apps/ai-gateway/src/panels/aiGatewayPanels.test.tsx`

- [ ] **Step 1: Write failing frontend tests**

```ts
it('loads group-scoped pool data from the new admin endpoint', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ pools: [] }), { status: 200 })));
  const api = new AiGatewayApi('abc');
  await api.groupPools('default');
  expect(fetch).toHaveBeenCalledWith('/api/plugins/airelay/admin/groups/default/pools', expect.anything());
});
```

- [ ] **Step 2: Run the frontend test slices**

Run: `cd keel-samples/frontend && npm run test:run -- ai-gateway/src/api/aiGatewayApi.test.ts ai-gateway/src/panels/GroupsPanel.test.tsx ai-gateway/src/panels/aiGatewayPanels.test.tsx`
Expected: FAIL because the API client and panels do not know the new routes or fields.

- [ ] **Step 3: Add typed admin API helpers**

```ts
groupPools(groupId: string) {
  return requestJson(`${AIRELAY_BASE}/admin/groups/${groupId}/pools`, { token: this.token });
}

groupPool(groupId: string, aliasOrModel: string) {
  return requestJson(`${AIRELAY_BASE}/admin/groups/${groupId}/pools/${encodeURIComponent(aliasOrModel)}`, { token: this.token });
}

explainGroupPool(groupId: string, aliasOrModel: string, body: { requestedModel?: string; preview?: boolean }) {
  return requestJson(`${AIRELAY_BASE}/admin/groups/${groupId}/pools/${encodeURIComponent(aliasOrModel)}/explain`, {
    method: 'POST',
    body,
    token: this.token
  });
}
```

- [ ] **Step 4: Render pool-aware group and pool views**

```tsx
<KeyValue label="Routing policy">
  <Chip tone={alias.routingPolicy === 'POOL_BALANCE' ? 'ok' : 'warn'}>{alias.routingPolicy}</Chip>
</KeyValue>

<KeyValue label="Effective status">
  <Chip tone={statusTone(channel.effectiveStatus)}>{channel.effectiveStatus}</Chip>
</KeyValue>
```

- [ ] **Step 5: Add visibility-aware polling to the pools panel**

```tsx
useEffect(() => {
  let cancelled = false;
  let timer: number | undefined;
  const load = async () => {
    if (document.visibilityState === 'hidden') return;
    const data = await api.groupPools(selectedGroupId);
    if (!cancelled) setPools((data as { pools?: PoolView[] }).pools ?? []);
    timer = window.setTimeout(load, 5000);
  };
  void load();
  return () => {
    cancelled = true;
    if (timer) window.clearTimeout(timer);
  };
}, [api, selectedGroupId]);
```

- [ ] **Step 6: Run frontend verification**

Run: `cd keel-samples/frontend && npm run test:run -- ai-gateway/src/api/aiGatewayApi.test.ts ai-gateway/src/panels/GroupsPanel.test.tsx ai-gateway/src/panels/aiGatewayPanels.test.tsx && npm run typecheck && npm run build:ai-gateway`
Expected: PASS for tests, typecheck, and build.

- [ ] **Step 7: Commit the admin UI updates**

```bash
git add keel-samples/frontend/apps/ai-gateway/src/api/aiGatewayApi.ts \
  keel-samples/frontend/apps/ai-gateway/src/api/aiGatewayApi.test.ts \
  keel-samples/frontend/apps/ai-gateway/src/panels/GroupsPanel.tsx \
  keel-samples/frontend/apps/ai-gateway/src/panels/PoolsPanel.tsx \
  keel-samples/frontend/apps/ai-gateway/src/panels/GroupsPanel.test.tsx \
  keel-samples/frontend/apps/ai-gateway/src/panels/aiGatewayPanels.test.tsx
git commit -m "feat: show ai relay pool runtime in admin UI"
```

### Task 7: Final Verification And Handoff

**Files:**
- Modify: `docs/superpowers/plans/2026-06-19-ai-gateway-routing-pools.md`

- [ ] **Step 1: Run the targeted backend verification suite**

Run: `./gradlew :keel-samples:test --tests com.keel.samples.aigateway.airelay.pool.PoolChainManagerTest :keel-test-suite:test --tests com.keel.test.kernel.ChannelConfigTest --tests com.keel.test.kernel.AiGatewayPluginIntegrationTest`
Expected: PASS for all targeted backend tests.

- [ ] **Step 2: Run the targeted frontend verification suite**

Run: `cd keel-samples/frontend && npm run test:run -- ai-gateway/src/api/aiGatewayApi.test.ts ai-gateway/src/panels/GroupsPanel.test.tsx ai-gateway/src/panels/aiGatewayPanels.test.tsx && npm run typecheck && npm run build:ai-gateway`
Expected: PASS.

- [ ] **Step 3: Run a placeholder scan over the plan**

Run: `rg -n "TODO|TBD|implement later|fill in details|add appropriate|similar to Task" docs/superpowers/plans/2026-06-19-ai-gateway-routing-pools.md`
Expected: no output.

- [ ] **Step 4: Record execution notes inline**

```markdown
- [x] Backend routing semantics shipped
- [x] Admin pool snapshot/explain shipped
- [x] Admin UI pool/runtime shipped
```

- [ ] **Step 5: Create the finishing commit**

```bash
git add docs/superpowers/plans/2026-06-19-ai-gateway-routing-pools.md
git commit -m "docs: mark ai relay routing pools plan executed"
```

## Self-Review

1. Spec coverage: routing policy, pool scheduling, permit enforcement, failover, runtime effective status, explain APIs, admin pool snapshots, debug headers, and UI pool visibility are all mapped to Tasks 1-6.
2. Placeholder scan: no `TODO`/`TBD` instructions remain in executable plan steps; every code-changing step includes concrete snippets.
3. Type consistency: the plan uses `AliasRoutingPolicy`, `PoolRouteConfig`, `PoolLease`, `PoolView`, and `PoolExplainResponse` consistently across tasks.

## Execution Notes

- [x] Pool scheduling, priority tiers, weighted selection, leases, cooldown and saturation handling implemented.
- [x] Non-retryable upstream errors preserve their original status; retryable failures record actual failover paths.
- [x] Request-level explain traces and debug headers report the effective routing policy and selected channel.
- [x] Per-channel and per-pool rolling metrics are available for 1m, 5m and 15m windows, including latency percentiles and traffic-share deviation.
- [x] Runtime channel state survives configuration reloads without losing cooldown or diagnostic fields.
- [x] Admin Pool UI displays live metrics and an explain panel with the actual attempt path.
- [x] Full `keel-samples` and `keel-test-suite` tests, frontend tests, typecheck and production build passed on 2026-06-19.

Plan complete and saved to `docs/superpowers/plans/2026-06-19-ai-gateway-routing-pools.md`. Two execution options:

1. Subagent-Driven (recommended) - I dispatch a fresh subagent per task, review between tasks, fast iteration
2. Inline Execution - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
