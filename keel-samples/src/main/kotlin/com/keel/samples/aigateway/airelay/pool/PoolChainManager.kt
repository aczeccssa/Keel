package com.keel.samples.aigateway.airelay.pool

import com.keel.contract.ai.PoolChainHealth
import com.keel.contract.ai.PoolChainSnapshot
import com.keel.contract.ai.PoolChainSnapshotProvider
import com.keel.contract.ai.PoolKeyHealth
import com.keel.contract.ai.PoolLevelHealth
import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.aigateway.airelay.AliasRouteConfig
import com.keel.samples.aigateway.airelay.AliasRoutingPolicy
import com.keel.samples.aigateway.airelay.AliasTargetConfig
import com.keel.samples.aigateway.airelay.GroupExposureMode
import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.modelVariantSemantics
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

const val DEFAULT_ROUTING_GROUP_ID: String = "default"
private const val TRANSIENT_HTTP_FAILURES_BEFORE_COOLDOWN = 3
private const val TRANSIENT_TRANSPORT_FAILURES_BEFORE_COOLDOWN = 2
private const val TRANSIENT_HTTP_COOLDOWN_MS = 5_000L
private const val TRANSIENT_TRANSPORT_COOLDOWN_MS = 3_000L
private const val HALF_OPEN_SUCCESSES_BEFORE_CLOSE = 2
private const val HALF_OPEN_PROBE_CONCURRENCY = 1

enum class KeyStatus { HEALTHY, COOLDOWN, DEGRADED, DISABLED }
enum class BreakerState { CLOSED, OPEN, HALF_OPEN }
enum class FailureScope { CHANNEL_GLOBAL, CHANNEL_MODEL }
enum class FailureKind { AUTH, RATE_LIMIT, SERVER_5XX, TIMEOUT, NETWORK, CLIENT, UNKNOWN }

data class UpstreamKeyState(
    val chain: PoolChainConfig,
    val level: PoolLevelConfig,
    val key: PooledKeyConfig,
    val status: AtomicReferenceStatus = AtomicReferenceStatus(KeyStatus.HEALTHY),
    val currentConcurrency: AtomicInteger = AtomicInteger(0),
    val totalRequests: AtomicLong = AtomicLong(0),
    val totalFailures: AtomicLong = AtomicLong(0),
    val consecutiveFailures: AtomicInteger = AtomicInteger(0),
    private val sharedState: SharedChannelRuntimeState? = null,
) {
    @Volatile private var localCooldownUntilEpochMs: Long = 0L
    @Volatile private var localLastError: String? = null
    @Volatile private var localLastStatus: Int? = null
    @Volatile private var localLastSelectedAt: Long? = null
    @Volatile private var localLastAttemptAtEpochMs: Long? = null
    @Volatile private var localLastSuccessAtEpochMs: Long? = null
    @Volatile private var localFailureScope: FailureScope? = null
    @Volatile private var localFailureKind: FailureKind? = null

    var cooldownUntilEpochMs: Long
        get() = sharedState?.cooldownUntilEpochMs ?: localCooldownUntilEpochMs
        set(value) {
            if (sharedState != null) sharedState.cooldownUntilEpochMs = value else localCooldownUntilEpochMs = value
        }

    var lastError: String?
        get() = sharedState?.lastError ?: localLastError
        set(value) {
            if (sharedState != null) sharedState.lastError = value else localLastError = value
        }

    var lastStatus: Int?
        get() = sharedState?.lastStatus ?: localLastStatus
        set(value) {
            if (sharedState != null) sharedState.lastStatus = value else localLastStatus = value
        }

    var lastSelectedAt: Long?
        get() = sharedState?.lastSelectedAt ?: localLastSelectedAt
        set(value) {
            if (sharedState != null) sharedState.lastSelectedAt = value else localLastSelectedAt = value
        }

    var lastAttemptAtEpochMs: Long?
        get() = sharedState?.lastAttemptAtEpochMs ?: localLastAttemptAtEpochMs
        set(value) {
            if (sharedState != null) sharedState.lastAttemptAtEpochMs = value else localLastAttemptAtEpochMs = value
        }

    var lastSuccessAtEpochMs: Long?
        get() = sharedState?.lastSuccessAtEpochMs ?: localLastSuccessAtEpochMs
        set(value) {
            if (sharedState != null) sharedState.lastSuccessAtEpochMs = value else localLastSuccessAtEpochMs = value
        }

    var failureScope: FailureScope?
        get() = sharedState?.failureScope ?: localFailureScope
        set(value) {
            if (sharedState != null) sharedState.failureScope = value else localFailureScope = value
        }

    var failureKind: FailureKind?
        get() = sharedState?.failureKind ?: localFailureKind
        set(value) {
            if (sharedState != null) sharedState.failureKind = value else localFailureKind = value
        }
}

class AtomicReferenceStatus(initial: KeyStatus) {
    @Volatile private var value: KeyStatus = initial
    fun get(): KeyStatus = value
    fun set(next: KeyStatus) {
        value = next
    }
}

data class PoolSelection(
    val chain: PoolChainConfig,
    val level: PoolLevelConfig,
    val provider: UpstreamProviderConfig,
    val keyState: UpstreamKeyState,
    val routeModelKey: String,
    val resolvedModel: String,
    val upstreamModel: String,
    val requestedModel: String,
    val routingPolicy: AliasRoutingPolicy = AliasRoutingPolicy.POOL_BALANCE,
    val poolId: String = "",
    val priority: Int = 0,
    val traceId: String? = null,
    val matchedAliasName: String? = null,
    val matchedAliasCreditMultiplier: Double? = null,
)

private data class ResolvedRoute(
    val chain: PoolChainConfig,
    val requestedModel: String,
    val routedModel: String,
    val matchedAlias: AliasRouteConfig?,
    val targets: List<AliasTargetConfig>,
    val routingPolicy: AliasRoutingPolicy,
)

private data class PoolCandidate(
    val keyState: UpstreamKeyState,
    val target: AliasTargetConfig,
)

class PoolChainManager(
    chains: List<PoolChainConfig>,
    internal val runtimeRegistry: PoolRuntimeRegistry = PoolRuntimeRegistry(),
) : PoolChainSnapshotProvider {
    private val allChains = chains
    private val chainByGroup = chains.associateBy { it.chainId }.toMutableMap().apply {
        if (DEFAULT_ROUTING_GROUP_ID !in this) {
            this["default-chain"]?.let { put(DEFAULT_ROUTING_GROUP_ID, it) }
                ?: chains.singleOrNull()?.let { put(DEFAULT_ROUTING_GROUP_ID, it) }
        }
    }
    private val states = chains.associateWith { chain ->
        chain.levels.sortedBy { it.levelIndex }.associateWith { level ->
            level.keys.map { key ->
                val shared = runtimeRegistry.channelState(key.keyId)
                UpstreamKeyState(
                    chain = chain,
                    level = level,
                    key = key,
                    status = shared.status,
                    currentConcurrency = shared.currentConcurrency,
                    totalRequests = shared.totalRequests,
                    totalFailures = shared.totalFailures,
                    consecutiveFailures = shared.consecutiveFailures,
                    sharedState = shared,
                )
            }
        }
    }
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    fun resolve(groupId: String, model: String): PoolChainConfig {
        val chain = resolveChainForModel(groupId, model)
        if (resolveRequestedModel(chain, model) == null) {
            throw PluginApiException(404, "Model $model is not available in group $groupId")
        }
        return chain
    }

    fun acquire(
        groupId: String,
        model: String,
        excludedKeyIds: Set<String> = emptySet(),
        traceId: String? = null,
    ): PoolLease? {
        val route = resolveRoute(groupId, model)
        if (traceId != null) runtimeRegistry.beginTrace(traceId, route.chain.chainId, model, route.routingPolicy.name)
        val lease = when (route.routingPolicy) {
            AliasRoutingPolicy.POOL_BALANCE -> acquirePoolBalance(route, excludedKeyIds, traceId)
            AliasRoutingPolicy.ORDERED_FAILOVER -> acquireOrderedFailover(route, excludedKeyIds, traceId)
        }
        lease?.selection?.let { runtimeRegistry.recordSelection(traceId, it) }
        return lease
    }

    fun selectCandidates(groupId: String, model: String): List<PoolSelection> {
        val route = resolveRoute(groupId, model)
        return when (route.routingPolicy) {
            AliasRoutingPolicy.POOL_BALANCE -> previewPoolBalance(route)
            AliasRoutingPolicy.ORDERED_FAILOVER -> previewOrderedFailover(route)
        }
    }

    fun explainSelection(groupId: String, model: String, traceId: String? = null): PoolExplainResponse {
        val route = resolveRoute(groupId, model)
        val now = System.currentTimeMillis()
        val tiers = states.getValue(route.chain).entries.sortedBy { it.key.levelIndex }.map { (level, keyStates) ->
            keyStates.forEach { maybeRecover(it, now) }
            val poolCandidates = buildPoolCandidates(keyStates, route.targets)
            val eligible = mutableListOf<PoolExplainCandidateView>()
            val ineligible = mutableListOf<PoolExplainCandidateView>()
            val ordered = if (route.routingPolicy == AliasRoutingPolicy.POOL_BALANCE) {
                orderPoolCandidates(route, level, poolCandidates)
            } else {
                emptyList()
            }
            keyStates.forEach { state ->
                val target = poolCandidates.firstOrNull { it.keyState.key.keyId == state.key.keyId }?.target
                val provider = providerFor(level, state.key)
                val routeModelKey = target?.model ?: route.targets.firstOrNull()?.model.orEmpty()
                val routeState = runtimeRegistry.routeSnapshot(state.key.keyId, routeModelKey)
                val effectiveStatus = routeEffectiveStatus(state, routeModelKey, state.key.maxConcurrency, now)
                val base = PoolExplainCandidateView(
                    channelId = state.key.keyId,
                    channelName = provider.providerId,
                    priority = priorityForLevel(level),
                    weight = state.key.weight,
                    effectiveStatus = effectiveStatus,
                    breakerState = routeState.breakerState.name,
                    failureScope = routeState.failureScope?.name,
                    failureKind = routeState.failureKind?.name,
                    currentConcurrency = state.currentConcurrency.get(),
                    maxConcurrency = state.key.maxConcurrency,
                    score = ordered.firstOrNull { it.keyState.key.keyId == state.key.keyId && it.target.model == routeModelKey }?.let {
                        scoreCandidate(route, level, ordered.map { candidate -> candidate.keyState }, state)
                    },
                    probeEligible = routeState.breakerState == BreakerState.HALF_OPEN,
                    targetModel = routeModelKey,
                    resolvedModel = target?.let { resolvePublicModel(state.key, it.model) } ?: route.routedModel,
                    upstreamModel = target?.let {
                        resolveUpstreamModel(state.key, it.model, resolvePublicModel(state.key, it.model))
                    } ?: route.routedModel,
                    cooldownUntilEpochMs = routeState.cooldownUntilEpochMs.takeIf { it > 0L },
                    cooldownRemainingMs = routeState.cooldownUntilEpochMs.takeIf { it > now }?.minus(now),
                    lastStatus = routeState.lastStatus,
                    lastError = routeState.lastError,
                    lastAttemptAtEpochMs = routeState.lastAttemptAtEpochMs,
                    lastSuccessAtEpochMs = routeState.lastSuccessAtEpochMs,
                )
                if (target != null && effectiveStatus == KeyStatus.HEALTHY.name) {
                    eligible += base
                } else {
                    ineligible += base.copy(reason = exclusionReason(state, target))
                }
            }
            PoolExplainTierView(
                priority = priorityForLevel(level),
                eligible = eligible,
                ineligible = ineligible,
            )
        }
        val selected = selectCandidates(groupId, model).firstOrNull()?.keyState?.key?.keyId
        return PoolExplainResponse(
            groupId = route.chain.chainId,
            requestedModel = model,
            routedModel = route.routedModel,
            routingPolicy = route.routingPolicy.name,
            selectedChannelId = selected,
            result = if (selected != null) "SELECTED" else "EXHAUSTED",
            priorityTiers = tiers,
            requestTrace = runtimeRegistry.trace(traceId, route.chain.chainId, model),
        )
    }

    fun listPools(groupId: String): List<PoolView> {
        val chain = chainByGroup[groupId] ?: throw PluginApiException(404, "No routing group $groupId")
        val aliasNames = chain.aliasRoutes.filter { it.enabled }.map { it.aliasName }
        val directModels = chain.modelAliases.filter { it !in aliasNames }
        val routeKeys = (aliasNames + directModels).distinct()
        return routeKeys.flatMap { routeKey -> poolViewsForRoute(groupId, routeKey) }
    }

    fun poolDetails(groupId: String, aliasOrModel: String): PoolView {
        return poolViewsForRoute(groupId, aliasOrModel).firstOrNull()
            ?: throw PluginApiException(404, "Pool $aliasOrModel not found in group $groupId")
    }

    fun explainAvailability(groupId: String, model: String): String = runCatching {
        val explain = explainSelection(groupId, model)
        val tiers = explain.priorityTiers.joinToString("; ") { tier ->
            val eligible = tier.eligible.joinToString(",") { "${it.channelId}[cc=${it.currentConcurrency}/${it.maxConcurrency},status=${it.effectiveStatus},target=${it.targetModel}]" }
            val ineligible = tier.ineligible.joinToString(",") { "${it.channelId}[${it.reason ?: "ineligible"}]" }
            "p${tier.priority}{eligible=$eligible;ineligible=$ineligible}"
        }
        "group=$groupId requested=$model routed=${explain.routedModel ?: "-"} policy=${explain.routingPolicy} selected=${explain.selectedChannelId ?: "-"} tiers=$tiers"
    }.getOrElse { error ->
        "group=$groupId requested=$model explain_error=${error.message ?: error::class.simpleName ?: "unknown"}"
    }

    fun selectProtocolCandidates(groupId: String, protocol: WireProtocol): List<PoolSelection> {
        val chain = chainByGroup[groupId]
            ?: throw PluginApiException(404, "No routing group $groupId")
        val now = System.currentTimeMillis()
        return states.getValue(chain).entries.sortedBy { it.key.levelIndex }.flatMap { (level, keyStates) ->
            keyStates.forEach { maybeRecover(it, now) }
            val available = keyStates.filter { state ->
                val provider = providerFor(level, state.key)
                provider.protocol == protocol &&
                    state.status.get() == KeyStatus.HEALTHY &&
                    state.currentConcurrency.get() < state.key.maxConcurrency
            }
            weightedRotate(level.levelId, available).map { state ->
                val provider = providerFor(level, state.key)
                PoolSelection(
                    chain = chain,
                    level = level,
                    provider = provider,
                    keyState = state,
                    routeModelKey = chain.modelAliases.firstOrNull().orEmpty(),
                    resolvedModel = chain.modelAliases.firstOrNull().orEmpty(),
                    upstreamModel = chain.modelAliases.firstOrNull().orEmpty(),
                    requestedModel = chain.modelAliases.firstOrNull().orEmpty(),
                )
            }
        }
    }

    fun markSuccess(selection: PoolSelection, latencyMs: Long = 0L) {
        val routeState = runtimeRegistry.routeState(selection.keyState.key.keyId, selection.routeModelKey)
        val now = System.currentTimeMillis()
        selection.keyState.consecutiveFailures.set(0)
        selection.keyState.lastError = null
        selection.keyState.lastStatus = 200
        selection.keyState.lastSuccessAtEpochMs = now
        selection.keyState.failureScope = null
        selection.keyState.failureKind = null
        if (selection.keyState.status.get() != KeyStatus.DISABLED) {
            selection.keyState.status.set(KeyStatus.HEALTHY)
            selection.keyState.cooldownUntilEpochMs = 0L
        }
        routeState.consecutiveTransientFailures.set(0)
        routeState.lastError = null
        routeState.lastStatus = 200
        routeState.lastSuccessAtEpochMs = now
        routeState.failureScope = null
        routeState.failureKind = null
        routeState.cooldownUntilEpochMs = 0L
        if (routeState.breakerState.get() == BreakerState.HALF_OPEN) {
            val successes = routeState.halfOpenSuccesses.incrementAndGet()
            if (successes >= HALF_OPEN_SUCCESSES_BEFORE_CLOSE) {
                closeRouteBreaker(routeState)
            }
        } else {
            closeRouteBreaker(routeState)
        }
        runtimeRegistry.recordOutcome(selection, success = true, status = 200, reason = null, latencyMs = latencyMs)
        syncSharedState(selection.keyState)
    }

    fun markFailure(
        selection: PoolSelection,
        status: Int?,
        message: String?,
        retryAfterSeconds: Long? = null,
        latencyMs: Long = 0L,
    ) {
        val state = selection.keyState
        val routeState = runtimeRegistry.routeState(state.key.keyId, selection.routeModelKey)
        val previousBreakerState = routeState.breakerState.get()
        val now = System.currentTimeMillis()
        state.totalFailures.incrementAndGet()
        state.consecutiveFailures.incrementAndGet()
        state.lastError = message ?: status?.toString()
        state.lastStatus = status
        state.lastAttemptAtEpochMs = now

        val failureKind = failureKindFor(status)
        val routeAlreadyOpen = previousBreakerState == BreakerState.OPEN
        when (failureKind) {
            FailureKind.AUTH -> {
                state.status.set(KeyStatus.DISABLED)
                state.cooldownUntilEpochMs = 0L
                state.failureScope = FailureScope.CHANNEL_GLOBAL
                state.failureKind = failureKind
                routeState.failureScope = FailureScope.CHANNEL_GLOBAL
                routeState.failureKind = failureKind
                routeState.lastError = state.lastError
                routeState.lastStatus = status
                routeState.lastAttemptAtEpochMs = now
                routeState.cooldownUntilEpochMs = 0L
                routeState.breakerState.set(BreakerState.CLOSED)
            }
            FailureKind.RATE_LIMIT -> {
                val retryAfterCooldown = retryAfterSeconds?.times(1000)
                if (routeAlreadyOpen && retryAfterCooldown == null) {
                    recordRouteOpenFailure(routeState, failureKind, message, status, now)
                } else {
                    val cooldown = retryAfterCooldown
                        ?: cooldownBackoffMs(30_000L, routeState.consecutiveTransientFailures.incrementAndGet(), selection.level.cooldownMs)
                    openRouteBreaker(routeState, cooldown, failureKind, message, status, now, extendOnly = routeAlreadyOpen)
                }
                state.status.set(KeyStatus.HEALTHY)
                state.cooldownUntilEpochMs = 0L
                state.failureScope = FailureScope.CHANNEL_MODEL
                state.failureKind = failureKind
            }
            FailureKind.SERVER_5XX -> {
                if (routeAlreadyOpen) {
                    recordRouteOpenFailure(routeState, failureKind, message, status, now)
                } else {
                    val failures = routeState.consecutiveTransientFailures.incrementAndGet()
                    if (failures >= TRANSIENT_HTTP_FAILURES_BEFORE_COOLDOWN) {
                        openRouteBreaker(
                            routeState,
                            cooldownBackoffMs(
                                TRANSIENT_HTTP_COOLDOWN_MS,
                                failures - TRANSIENT_HTTP_FAILURES_BEFORE_COOLDOWN + 1,
                                selection.level.cooldownMs,
                            ),
                            failureKind,
                            message,
                            status,
                            now,
                        )
                    } else {
                        keepRouteClosed(routeState, failureKind, message, status, now)
                    }
                }
                state.status.set(KeyStatus.HEALTHY)
                state.cooldownUntilEpochMs = 0L
                state.failureScope = FailureScope.CHANNEL_MODEL
                state.failureKind = failureKind
            }
            FailureKind.TIMEOUT,
            FailureKind.NETWORK,
            FailureKind.UNKNOWN -> {
                if (routeAlreadyOpen) {
                    recordRouteOpenFailure(routeState, failureKind, message, status, now)
                } else {
                    val failures = routeState.consecutiveTransientFailures.incrementAndGet()
                    if (failures >= TRANSIENT_TRANSPORT_FAILURES_BEFORE_COOLDOWN) {
                        openRouteBreaker(
                            routeState,
                            cooldownBackoffMs(
                                TRANSIENT_TRANSPORT_COOLDOWN_MS,
                                failures - TRANSIENT_TRANSPORT_FAILURES_BEFORE_COOLDOWN + 1,
                                selection.level.cooldownMs,
                            ),
                            failureKind,
                            message,
                            status,
                            now,
                        )
                    } else {
                        keepRouteClosed(routeState, failureKind, message, status, now)
                    }
                }
                state.status.set(KeyStatus.HEALTHY)
                state.cooldownUntilEpochMs = 0L
                state.failureScope = FailureScope.CHANNEL_MODEL
                state.failureKind = failureKind
            }
            FailureKind.CLIENT -> {
                keepRouteClosed(routeState, failureKind, message, status, now)
                state.failureScope = FailureScope.CHANNEL_MODEL
                state.failureKind = failureKind
            }
        }
        if (routeState.breakerState.get() == BreakerState.OPEN && previousBreakerState != BreakerState.OPEN) {
            routeState.cooldownCount.incrementAndGet()
        }
        runtimeRegistry.recordOutcome(selection, success = false, status = status, reason = message, latencyMs = latencyMs)
        syncSharedState(state)
    }

    fun recordFailover(selection: PoolSelection) = runtimeRegistry.recordFailover(selection)

    fun completeTrace(traceId: String, outcome: String) = runtimeRegistry.completeTrace(traceId, outcome)

    fun reset(chainId: String, keyId: String): Boolean {
        val chain = states.keys.firstOrNull { it.chainId == chainId } ?: return false
        val keyStates = states.getValue(chain).values.flatten().filter { it.key.keyId == keyId }
        if (keyStates.isEmpty()) return false
        keyStates.forEach { state ->
            state.status.set(KeyStatus.HEALTHY)
            state.consecutiveFailures.set(0)
            state.cooldownUntilEpochMs = 0L
            state.lastError = null
            state.lastStatus = null
            state.failureScope = null
            state.failureKind = null
            syncSharedState(state)
        }
        chain.levels.flatMap { level -> level.keys.filter { it.keyId == keyId } }
            .flatMap { key -> chain.modelAliases.mapNotNull { model -> model.takeIf { supportsTargetModel(key, it) } } }
            .distinct()
            .forEach { model -> runtimeRegistry.resetRouteState(keyId, model) }
        return true
    }

    override fun snapshot(): PoolChainSnapshot {
        val now = System.currentTimeMillis()
        return PoolChainSnapshot(
            chains = states.map { (chain, levels) ->
                PoolChainHealth(
                    chainId = chain.chainId,
                    modelAliases = chain.modelAliases,
                    levels = levels.entries.sortedBy { it.key.levelIndex }.map { (level, keyStates) ->
                        keyStates.forEach { maybeRecover(it, now) }
                        val providers = keyStates.map { providerFor(level, it.key) }
                        val providerId = providers.map { it.providerId }.distinct().joinToString("+")
                        val protocol = providers.map { it.protocol.name }.distinct().joinToString("+")
                        PoolLevelHealth(
                            levelId = level.levelId,
                            levelIndex = level.levelIndex,
                            providerId = providerId.ifBlank { level.provider.providerId },
                            protocol = protocol.ifBlank { level.provider.protocol.name },
                            healthyKeys = keyStates.count { effectiveStatus(it, it.key.maxConcurrency, now) == "HEALTHY" },
                            cooldownKeys = keyStates.count { effectiveStatus(it, it.key.maxConcurrency, now) == "COOLDOWN" },
                            degradedKeys = keyStates.count { effectiveStatus(it, it.key.maxConcurrency, now) == "DEGRADED" },
                            disabledKeys = keyStates.count { effectiveStatus(it, it.key.maxConcurrency, now) == "SATURATED" || it.status.get() == KeyStatus.DISABLED },
                            keys = keyStates.map { state ->
                                val routeModelKey = chain.modelAliases.firstOrNull { supportsTargetModel(state.key, it) } ?: chain.modelAliases.firstOrNull().orEmpty()
                                val routeState = runtimeRegistry.routeSnapshot(state.key.keyId, routeModelKey)
                                val status = routeEffectiveStatus(state, routeModelKey, state.key.maxConcurrency, now)
                                PoolKeyHealth(
                                    keyId = state.key.keyId,
                                    status = status,
                                    breakerState = routeState.breakerState.name,
                                    failureScope = routeState.failureScope?.name,
                                    failureKind = routeState.failureKind?.name,
                                    totalRequests = state.totalRequests.get(),
                                    totalFailures = state.totalFailures.get(),
                                    currentConcurrency = state.currentConcurrency.get(),
                                    maxConcurrency = state.key.maxConcurrency,
                                    cooldownUntilEpochMs = routeState.cooldownUntilEpochMs.takeIf { it > 0L },
                                    cooldownRemainingMs = routeState.cooldownUntilEpochMs.takeIf { it > now }?.minus(now),
                                    lastStatus = routeState.lastStatus,
                                    lastError = routeState.lastError,
                                    lastAttemptAtEpochMs = routeState.lastAttemptAtEpochMs,
                                    lastSuccessAtEpochMs = routeState.lastSuccessAtEpochMs,
                                    probeEligible = routeState.breakerState == BreakerState.HALF_OPEN,
                                )
                            }
                        )
                    }
                )
            }
        )
    }

    private fun resolveChainForModel(groupId: String, model: String): PoolChainConfig {
        val exact = chainByGroup[groupId]
            ?: throw PluginApiException(404, "No routing group $groupId")
        if (resolveRequestedModel(exact, model) != null) return exact
        if (groupId == DEFAULT_ROUTING_GROUP_ID) {
            allChains.firstOrNull { resolveRequestedModel(it, model) != null }?.let { return it }
        }
        return exact
    }

    private fun resolveRoute(groupId: String, model: String): ResolvedRoute {
        val chain = resolve(groupId, model)
        val routedModel = resolveRequestedModel(chain, model)
            ?: throw PluginApiException(404, "Model $model is not available in group $groupId")
        val matchedAlias = resolveMatchedAlias(chain, model, routedModel)
        return ResolvedRoute(
            chain = chain,
            requestedModel = model,
            routedModel = routedModel,
            matchedAlias = matchedAlias,
            targets = resolveTargetModels(chain, model, routedModel, matchedAlias),
            routingPolicy = matchedAlias?.routingPolicy ?: AliasRoutingPolicy.POOL_BALANCE,
        )
    }

    private fun resolveMatchedAlias(
        chain: PoolChainConfig,
        requestedModel: String,
        routedModel: String,
    ) = chain.aliasRoutes.firstOrNull { it.enabled && it.aliasName == requestedModel }
        ?: routedModel
            .takeIf { it != requestedModel }
            ?.let { fallback -> chain.aliasRoutes.firstOrNull { it.enabled && it.aliasName == fallback } }

    private fun resolveTargetModels(
        chain: PoolChainConfig,
        requestedModel: String,
        routedModel: String,
        matchedAlias: AliasRouteConfig?,
    ): List<AliasTargetConfig> {
        if (matchedAlias != null) return matchedAlias.orderedTargets()
        return when (chain.exposureMode.normalized()) {
            GroupExposureMode.ALIASES_ONLY -> throw PluginApiException(404, "Model $requestedModel is not available in group ${chain.chainId}")
            GroupExposureMode.ALIASES_AND_MODELS,
            GroupExposureMode.ALL_MODELS -> listOf(AliasTargetConfig(routedModel))
            GroupExposureMode.ALIAS_ONLY,
            GroupExposureMode.ALIAS_AND_MODEL_NAMES,
            GroupExposureMode.MODEL_NAMES_ONLY -> error("legacy exposure mode should be normalized")
        }
    }

    private fun resolveRequestedModel(chain: PoolChainConfig, requestedModel: String): String? {
        if (requestedModel in chain.modelAliases) return requestedModel
        val fallback = modelVariantSemantics(requestedModel).routingFallbackModel
        return fallback?.takeIf { it in chain.modelAliases }
    }

    private fun previewOrderedFailover(route: ResolvedRoute): List<PoolSelection> {
        val now = System.currentTimeMillis()
        return route.targets.flatMap { target ->
            states.getValue(route.chain).entries.sortedBy { it.key.levelIndex }.flatMap { (level, keyStates) ->
                keyStates.forEach { maybeRecover(it, now) }
                val available = keyStates.filter {
                    maybeRecoverRoute(it.key.keyId, target.model, now)
                    val routeState = runtimeRegistry.routeSnapshot(it.key.keyId, target.model)
                    it.status.get() != KeyStatus.DISABLED &&
                        routeState.breakerState != BreakerState.OPEN &&
                        it.currentConcurrency.get() < it.key.maxConcurrency &&
                        (target.channelId == null || it.key.keyId == target.channelId) &&
                        supportsTargetModel(it.key, target.model)
                }
                weightedRotate(level.levelId, available).map { state ->
                    val resolvedModel = resolvePublicModel(state.key, target.model)
                    PoolSelection(
                        chain = route.chain,
                        level = level,
                        provider = providerFor(level, state.key),
                        keyState = state,
                        routeModelKey = target.model,
                        resolvedModel = resolvedModel,
                        upstreamModel = resolveUpstreamModel(state.key, target.model, resolvedModel),
                        requestedModel = route.requestedModel,
                        routingPolicy = route.routingPolicy,
                        matchedAliasName = route.matchedAlias?.aliasName,
                        matchedAliasCreditMultiplier = route.matchedAlias?.creditMultiplier,
                    )
                }
            }
        }
    }

    private fun previewPoolBalance(route: ResolvedRoute): List<PoolSelection> {
        val now = System.currentTimeMillis()
        return states.getValue(route.chain).entries.sortedBy { it.key.levelIndex }.flatMap { (level, keyStates) ->
            keyStates.forEach { maybeRecover(it, now) }
            orderPoolCandidates(route, level, buildPoolCandidates(keyStates, route.targets)).map { candidate ->
                val resolvedModel = resolvePublicModel(candidate.keyState.key, candidate.target.model)
                PoolSelection(
                    chain = route.chain,
                    level = level,
                    provider = providerFor(level, candidate.keyState.key),
                    keyState = candidate.keyState,
                    routeModelKey = candidate.target.model,
                    resolvedModel = resolvedModel,
                    upstreamModel = resolveUpstreamModel(candidate.keyState.key, candidate.target.model, resolvedModel),
                    requestedModel = route.requestedModel,
                    routingPolicy = route.routingPolicy,
                    matchedAliasName = route.matchedAlias?.aliasName,
                    matchedAliasCreditMultiplier = route.matchedAlias?.creditMultiplier,
                )
            }
        }
    }

    private fun acquireOrderedFailover(route: ResolvedRoute, excludedKeyIds: Set<String>, traceId: String?): PoolLease? {
        previewOrderedFailover(route).forEach { selection ->
            if (selection.keyState.key.keyId in excludedKeyIds) return@forEach
            val routeState = runtimeRegistry.routeState(selection.keyState.key.keyId, selection.routeModelKey)
            val isHalfOpen = routeState.breakerState.get() == BreakerState.HALF_OPEN
            if (isHalfOpen && routeState.probeInFlight.incrementAndGet() > HALF_OPEN_PROBE_CONCURRENCY) {
                routeState.probeInFlight.decrementAndGet()
                return@forEach
            }
            if (tryAcquire(selection.keyState)) {
                val tracedSelection = selection.copy(
                    poolId = tierId(route, selection.level),
                    priority = priorityForLevel(selection.level),
                    traceId = traceId,
                )
                selection.keyState.totalRequests.incrementAndGet()
                selection.keyState.lastSelectedAt = System.currentTimeMillis()
                syncSharedState(selection.keyState)
                return PoolLease(tracedSelection) {
                    selection.keyState.currentConcurrency.decrementAndGet()
                    if (isHalfOpen) routeState.probeInFlight.decrementAndGet()
                    syncSharedState(selection.keyState)
                }
            }
            if (isHalfOpen) routeState.probeInFlight.decrementAndGet()
        }
        return null
    }

    private fun acquirePoolBalance(route: ResolvedRoute, excludedKeyIds: Set<String>, traceId: String?): PoolLease? {
        val now = System.currentTimeMillis()
        states.getValue(route.chain).entries.sortedBy { it.key.levelIndex }.forEach { (level, keyStates) ->
            keyStates.forEach { maybeRecover(it, now) }
            val candidates = buildPoolCandidates(keyStates, route.targets)
                .filter { it.keyState.key.keyId !in excludedKeyIds }
            if (candidates.isEmpty()) return@forEach
            val tierId = tierId(route, level)
            val tierState = runtimeRegistry.tierState(tierId)
            var lease: PoolLease? = null
            tierState.withLock {
                val active = orderPoolCandidates(route, level, candidates).toMutableList()
                if (active.isEmpty()) return@withLock
                while (active.isNotEmpty() && lease == null) {
                    val totalWeight = active.sumOf { max(it.keyState.key.weight, 1) }
                    val scored = active.map { candidate ->
                        val keyId = candidate.keyState.key.keyId
                        val base = tierState.currentWeights[keyId] ?: 0.0
                        val nextWeight = base + max(candidate.keyState.key.weight, 1)
                        val saturation = candidate.keyState.currentConcurrency.get().toDouble() /
                            max(candidate.keyState.key.maxConcurrency, 1).toDouble()
                        val routeState = runtimeRegistry.routeSnapshot(candidate.keyState.key.keyId, candidate.target.model)
                        val breakerPenalty = when (routeState.breakerState) {
                            BreakerState.CLOSED -> 0.0
                            BreakerState.HALF_OPEN -> totalWeight.toDouble()
                            BreakerState.OPEN -> totalWeight.toDouble() * 2
                        }
                        ScoredCandidate(candidate, nextWeight - saturation * totalWeight - breakerPenalty, nextWeight)
                    }
                    val selected = chooseScoredCandidate(scored, tierState.tieCursor.get())
                    val candidate = selected?.candidate
                    if (candidate == null) {
                        active.clear()
                        return@withLock
                    }
                    val routeState = runtimeRegistry.routeState(candidate.keyState.key.keyId, candidate.target.model)
                    val isHalfOpen = routeState.breakerState.get() == BreakerState.HALF_OPEN
                    if (isHalfOpen && routeState.probeInFlight.incrementAndGet() > HALF_OPEN_PROBE_CONCURRENCY) {
                        routeState.probeInFlight.decrementAndGet()
                        active.removeAll { it.keyState.key.keyId == candidate.keyState.key.keyId && it.target.model == candidate.target.model }
                        return@withLock
                    }
                    if (tryAcquire(candidate.keyState)) {
                        val selectedKeyId = candidate.keyState.key.keyId
                        scored.forEach { score ->
                            val keyId = score.candidate.keyState.key.keyId
                            tierState.currentWeights[keyId] = if (keyId == selectedKeyId) {
                                score.nextWeight - totalWeight
                            } else {
                                score.nextWeight
                            }
                        }
                        tierState.tieCursor.incrementAndGet()
                        val keyState = candidate.keyState
                        keyState.totalRequests.incrementAndGet()
                        keyState.lastSelectedAt = System.currentTimeMillis()
                        syncSharedState(keyState)
                        val resolvedModel = resolvePublicModel(keyState.key, candidate.target.model)
                        val selection = PoolSelection(
                            chain = route.chain,
                            level = level,
                            provider = providerFor(level, keyState.key),
                            keyState = keyState,
                            routeModelKey = candidate.target.model,
                            resolvedModel = resolvedModel,
                            upstreamModel = resolveUpstreamModel(keyState.key, candidate.target.model, resolvedModel),
                            requestedModel = route.requestedModel,
                            routingPolicy = route.routingPolicy,
                            poolId = tierId,
                            priority = priorityForLevel(level),
                            traceId = traceId,
                            matchedAliasName = route.matchedAlias?.aliasName,
                            matchedAliasCreditMultiplier = route.matchedAlias?.creditMultiplier,
                        )
                        lease = PoolLease(selection) {
                            keyState.currentConcurrency.decrementAndGet()
                            if (isHalfOpen) routeState.probeInFlight.decrementAndGet()
                            syncSharedState(keyState)
                        }
                    } else {
                        if (isHalfOpen) routeState.probeInFlight.decrementAndGet()
                        active.removeAll { it.keyState.key.keyId == candidate.keyState.key.keyId && it.target.model == candidate.target.model }
                    }
                }
            }
            if (lease != null) {
                return lease
            }
        }
        return null
    }

    private fun buildPoolCandidates(keyStates: List<UpstreamKeyState>, targets: List<AliasTargetConfig>): List<PoolCandidate> {
        return keyStates.mapNotNull { state ->
            firstMatchingTarget(state.key, targets)?.let { PoolCandidate(state, it) }
        }
    }

    private fun poolViewsForRoute(groupId: String, aliasOrModel: String): List<PoolView> {
        val route = resolveRoute(groupId, aliasOrModel)
        val now = System.currentTimeMillis()
        return states.getValue(route.chain).entries.sortedBy { it.key.levelIndex }.mapNotNull { (level, keyStates) ->
            keyStates.forEach { maybeRecover(it, now) }
            val candidates = buildPoolCandidates(keyStates, route.targets)
            if (candidates.isEmpty()) return@mapNotNull null
            val channels = candidates.map { candidate ->
                val provider = providerFor(level, candidate.keyState.key)
                val poolId = tierId(route, level)
                val routeKey = PoolRuntimeRegistry.routeKey(candidate.keyState.key.keyId, candidate.target.model)
                val routeState = runtimeRegistry.routeSnapshot(candidate.keyState.key.keyId, candidate.target.model)
                val poolMetrics1m = runtimeRegistry.metrics(poolId, windowMs = 60_000L)
                val channelMetrics1m = runtimeRegistry.metrics(poolId, routeKey, 60_000L)
                val totalWeight = candidates.sumOf { max(it.keyState.key.weight, 1) }.toDouble()
                val expectedShare = max(candidate.keyState.key.weight, 1).toDouble() / totalWeight
                val trafficShare = if (poolMetrics1m.selectedRequests == 0L) 0.0 else {
                    channelMetrics1m.selectedRequests.toDouble() / poolMetrics1m.selectedRequests.toDouble()
                }
                PoolChannelRuntimeView(
                    channelId = candidate.keyState.key.keyId,
                    channelName = provider.providerId,
                    routeModelKey = candidate.target.model,
                    effectiveStatus = effectiveStatus(candidate.keyState, candidate.keyState.key.maxConcurrency, now),
                    breakerState = routeState.breakerState.name,
                    failureScope = routeState.failureScope?.name,
                    failureKind = routeState.failureKind?.name,
                    priority = priorityForLevel(level),
                    weight = candidate.keyState.key.weight,
                    currentConcurrency = candidate.keyState.currentConcurrency.get(),
                    maxConcurrency = candidate.keyState.key.maxConcurrency,
                    saturation = candidate.keyState.currentConcurrency.get().toDouble() /
                        max(candidate.keyState.key.maxConcurrency, 1).toDouble(),
                    totalRequests = candidate.keyState.totalRequests.get(),
                    totalFailures = candidate.keyState.totalFailures.get(),
                    selectedRequests1m = channelMetrics1m.selectedRequests,
                    successRequests1m = channelMetrics1m.successRequests,
                    failedRequests1m = channelMetrics1m.failedRequests,
                    errorRate1m = channelMetrics1m.errorRate,
                    p95Latency1m = channelMetrics1m.p95LatencyMs,
                    p99Latency1m = channelMetrics1m.p99LatencyMs,
                    metrics1m = channelMetrics1m,
                    metrics5m = runtimeRegistry.metrics(poolId, routeKey, 5 * 60_000L),
                    metrics15m = runtimeRegistry.metrics(poolId, routeKey, 15 * 60_000L),
                    trafficShare1m = trafficShare,
                    expectedShare = expectedShare,
                    shareDeviation = trafficShare - expectedShare,
                    cooldownCount = routeState.cooldownCount,
                    cooldownUntilEpochMs = routeState.cooldownUntilEpochMs.takeIf { it > 0L },
                    cooldownRemainingMs = routeState.cooldownUntilEpochMs.takeIf { it > now }?.minus(now),
                    lastStatus = routeState.lastStatus,
                    lastError = routeState.lastError,
                    lastSelectedAt = routeState.lastSelectedAt,
                    lastAttemptAtEpochMs = routeState.lastAttemptAtEpochMs,
                    lastSuccessAtEpochMs = routeState.lastSuccessAtEpochMs,
                    probeEligible = routeState.breakerState == BreakerState.HALF_OPEN,
                )
            }
            val poolId = tierId(route, level)
            val statuses = channels.groupingBy { it.effectiveStatus }.eachCount()
            val metrics1m = runtimeRegistry.metrics(poolId, windowMs = 60_000L)
            PoolView(
                groupId = groupId,
                aliasOrModel = aliasOrModel,
                routingPolicy = route.routingPolicy.name,
                priority = priorityForLevel(level),
                totalInflight = channels.sumOf { it.currentConcurrency },
                totalRequests1m = metrics1m.selectedRequests,
                errorRate1m = metrics1m.errorRate,
                p95Latency1m = metrics1m.p95LatencyMs,
                p99Latency1m = metrics1m.p99LatencyMs,
                metrics1m = metrics1m,
                metrics5m = runtimeRegistry.metrics(poolId, windowMs = 5 * 60_000L),
                metrics15m = runtimeRegistry.metrics(poolId, windowMs = 15 * 60_000L),
                healthyChannels = statuses["HEALTHY"] ?: 0,
                cooldownChannels = statuses["COOLDOWN"] ?: 0,
                degradedChannels = statuses["DEGRADED"] ?: 0,
                disabledChannels = statuses["DISABLED"] ?: 0,
                saturatedChannels = statuses["SATURATED"] ?: 0,
                halfOpenChannels = channels.count { it.breakerState == BreakerState.HALF_OPEN.name },
                failoverCount1m = runtimeRegistry.failoverCount(poolId, 60_000L),
                channels = channels,
            )
        }
    }

    private fun orderPoolCandidates(route: ResolvedRoute, level: PoolLevelConfig, candidates: List<PoolCandidate>): List<PoolCandidate> {
        val now = System.currentTimeMillis()
        val eligible = candidates.filter { candidate ->
            maybeRecoverRoute(candidate.keyState.key.keyId, candidate.target.model, now)
            val routeState = runtimeRegistry.routeSnapshot(candidate.keyState.key.keyId, candidate.target.model)
            candidate.keyState.status.get() != KeyStatus.DISABLED &&
                routeState.breakerState != BreakerState.OPEN &&
                !(routeState.breakerState == BreakerState.HALF_OPEN && routeState.probeInFlight >= HALF_OPEN_PROBE_CONCURRENCY) &&
                candidate.keyState.currentConcurrency.get() < candidate.keyState.key.maxConcurrency
        }
        if (eligible.isEmpty()) return emptyList()
        val tierState = runtimeRegistry.tierState(tierId(route, level))
        val totalWeight = eligible.sumOf { max(it.keyState.key.weight, 1) }
        val scored = eligible.map { candidate ->
            val base = tierState.currentWeights[candidate.keyState.key.keyId] ?: 0.0
            val nextWeight = base + max(candidate.keyState.key.weight, 1)
            val saturation = candidate.keyState.currentConcurrency.get().toDouble() /
                max(candidate.keyState.key.maxConcurrency, 1).toDouble()
            val routeState = runtimeRegistry.routeSnapshot(candidate.keyState.key.keyId, candidate.target.model)
            val breakerPenalty = when (routeState.breakerState) {
                BreakerState.CLOSED -> 0.0
                BreakerState.HALF_OPEN -> totalWeight.toDouble()
                BreakerState.OPEN -> totalWeight.toDouble() * 2
            }
            ScoredCandidate(candidate, nextWeight - saturation * totalWeight - breakerPenalty, nextWeight)
        }
        return scored.sortedWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenBy { tieDistance(scored.map { item -> item.candidate.keyState.key.keyId }.sorted(), tierState.tieCursor.get(), it.candidate.keyState.key.keyId) }
                .thenBy { it.candidate.keyState.key.keyId }
        ).map { it.candidate }
    }

    private data class ScoredCandidate(
        val candidate: PoolCandidate,
        val score: Double,
        val nextWeight: Double,
    )

    private fun chooseScoredCandidate(scored: List<ScoredCandidate>, cursor: Long): ScoredCandidate? {
        if (scored.isEmpty()) return null
        val orderedIds = scored.map { it.candidate.keyState.key.keyId }.sorted()
        return scored.sortedWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenBy { tieDistance(orderedIds, cursor, it.candidate.keyState.key.keyId) }
                .thenBy { it.candidate.keyState.key.keyId }
        ).firstOrNull()
    }

    private fun tieDistance(orderedIds: List<String>, cursor: Long, channelId: String): Int {
        if (orderedIds.isEmpty()) return 0
        val cursorIndex = Math.floorMod(cursor.toInt(), orderedIds.size)
        val candidateIndex = orderedIds.indexOf(channelId).coerceAtLeast(0)
        return Math.floorMod(candidateIndex - cursorIndex, orderedIds.size)
    }

    private fun scoreCandidate(
        route: ResolvedRoute,
        level: PoolLevelConfig,
        keyStates: List<UpstreamKeyState>,
        state: UpstreamKeyState,
    ): Double {
        val tierState = runtimeRegistry.tierState(tierId(route, level))
        val totalWeight = keyStates.sumOf { max(it.key.weight, 1) }
        val base = tierState.currentWeights[state.key.keyId] ?: 0.0
        val nextWeight = base + max(state.key.weight, 1)
        val saturation = state.currentConcurrency.get().toDouble() / max(state.key.maxConcurrency, 1).toDouble()
        return nextWeight - saturation * totalWeight
    }

    private fun tryAcquire(state: UpstreamKeyState): Boolean {
        while (true) {
            val observed = state.currentConcurrency.get()
            if (observed >= state.key.maxConcurrency) return false
            if (state.currentConcurrency.compareAndSet(observed, observed + 1)) {
                return true
            }
        }
    }

    private fun exclusionReason(state: UpstreamKeyState, target: AliasTargetConfig?): String {
        if (target == null) return "UNSUPPORTED_MODEL"
        val routeState = runtimeRegistry.routeSnapshot(state.key.keyId, target.model)
        return when {
            state.status.get() == KeyStatus.DISABLED -> KeyStatus.DISABLED.name
            routeState.breakerState == BreakerState.OPEN -> "BREAKER_OPEN"
            routeState.breakerState == BreakerState.HALF_OPEN && routeState.probeInFlight >= HALF_OPEN_PROBE_CONCURRENCY -> "PROBE_BUSY"
            state.currentConcurrency.get() >= state.key.maxConcurrency -> "SATURATED"
            else -> "INELIGIBLE"
        }
    }

    private fun maybeRecover(state: UpstreamKeyState, now: Long) {
        if (state.status.get() == KeyStatus.COOLDOWN && now >= state.cooldownUntilEpochMs) {
            state.status.set(KeyStatus.HEALTHY)
            state.cooldownUntilEpochMs = 0L
            syncSharedState(state)
        }
    }

    private fun maybeRecoverRoute(channelId: String, routeModelKey: String, now: Long) {
        val routeState = runtimeRegistry.routeState(channelId, routeModelKey)
        if (routeState.breakerState.get() == BreakerState.OPEN && now >= routeState.cooldownUntilEpochMs) {
            routeState.breakerState.set(BreakerState.HALF_OPEN)
            routeState.halfOpenSuccesses.set(0)
            routeState.probeInFlight.set(0)
        }
    }

    private fun cooldownBackoffMs(baseMs: Long, consecutiveFailures: Int, maxMs: Long): Long {
        val multiplier = 1L shl (consecutiveFailures - 1).coerceIn(0, 10)
        return (baseMs * multiplier).coerceAtMost(maxMs)
    }

    private fun failureKindFor(status: Int?): FailureKind = when (status) {
        401, 403 -> FailureKind.AUTH
        408, 429 -> FailureKind.RATE_LIMIT
        null -> FailureKind.NETWORK
        in 500..599 -> FailureKind.SERVER_5XX
        in 400..499 -> FailureKind.CLIENT
        else -> FailureKind.UNKNOWN
    }

    private fun openRouteBreaker(
        routeState: SharedRouteRuntimeState,
        cooldownMs: Long,
        failureKind: FailureKind,
        message: String?,
        status: Int?,
        now: Long,
        extendOnly: Boolean = false,
    ) {
        routeState.breakerState.set(BreakerState.OPEN)
        val cooldownUntil = now + cooldownMs
        if (!extendOnly || cooldownUntil > routeState.cooldownUntilEpochMs) {
            routeState.cooldownUntilEpochMs = cooldownUntil
        }
        routeState.halfOpenSuccesses.set(0)
        routeState.probeInFlight.set(0)
        recordRouteOpenFailure(routeState, failureKind, message, status, now)
    }

    private fun recordRouteOpenFailure(
        routeState: SharedRouteRuntimeState,
        failureKind: FailureKind,
        message: String?,
        status: Int?,
        now: Long,
    ) {
        routeState.failureScope = FailureScope.CHANNEL_MODEL
        routeState.failureKind = failureKind
        routeState.lastError = message ?: status?.toString()
        routeState.lastStatus = status
        routeState.lastAttemptAtEpochMs = now
    }

    private fun keepRouteClosed(
        routeState: SharedRouteRuntimeState,
        failureKind: FailureKind,
        message: String?,
        status: Int?,
        now: Long,
    ) {
        routeState.breakerState.set(BreakerState.CLOSED)
        routeState.cooldownUntilEpochMs = 0L
        routeState.halfOpenSuccesses.set(0)
        routeState.probeInFlight.set(0)
        routeState.failureScope = FailureScope.CHANNEL_MODEL
        routeState.failureKind = failureKind
        routeState.lastError = message ?: status?.toString()
        routeState.lastStatus = status
        routeState.lastAttemptAtEpochMs = now
    }

    private fun closeRouteBreaker(routeState: SharedRouteRuntimeState) {
        routeState.breakerState.set(BreakerState.CLOSED)
        routeState.cooldownUntilEpochMs = 0L
        routeState.consecutiveTransientFailures.set(0)
        routeState.halfOpenSuccesses.set(0)
        routeState.probeInFlight.set(0)
    }

    private fun effectiveStatus(state: UpstreamKeyState, maxConcurrency: Int, now: Long): String {
        if (state.status.get() == KeyStatus.DISABLED) return KeyStatus.DISABLED.name
        if (state.status.get() == KeyStatus.DEGRADED) return KeyStatus.DEGRADED.name
        if (state.currentConcurrency.get() >= maxConcurrency) return "SATURATED"
        return KeyStatus.HEALTHY.name
    }

    private fun routeEffectiveStatus(state: UpstreamKeyState, routeModelKey: String, maxConcurrency: Int, now: Long): String {
        if (state.status.get() == KeyStatus.DISABLED) return KeyStatus.DISABLED.name
        if (state.status.get() == KeyStatus.DEGRADED) return KeyStatus.DEGRADED.name
        maybeRecoverRoute(state.key.keyId, routeModelKey, now)
        val routeState = runtimeRegistry.routeSnapshot(state.key.keyId, routeModelKey)
        if (routeState.breakerState == BreakerState.OPEN) return KeyStatus.COOLDOWN.name
        if (state.currentConcurrency.get() >= maxConcurrency) return "SATURATED"
        return KeyStatus.HEALTHY.name
    }

    private fun firstMatchingTarget(key: PooledKeyConfig, targets: List<AliasTargetConfig>): AliasTargetConfig? =
        targets.firstOrNull { target ->
            (target.channelId == null || key.keyId == target.channelId) && supportsTargetModel(key, target.model)
        }

    private fun supportsTargetModel(key: PooledKeyConfig, targetModel: String): Boolean {
        if (key.supportedModels.isEmpty() && key.modelMap.isEmpty()) return true
        if (targetModel in key.supportedModels) return true
        return key.modelMap.values.any { it == targetModel }
    }

    private fun resolvePublicModel(key: PooledKeyConfig, targetModel: String): String {
        if (targetModel in key.supportedModels) return targetModel
        return key.modelMap.entries.firstOrNull { (_, upstreamModel) -> upstreamModel == targetModel }?.key ?: targetModel
    }

    private fun resolveUpstreamModel(key: PooledKeyConfig, targetModel: String, resolvedPublicModel: String): String {
        if (targetModel in key.modelMap.values) return targetModel
        return key.modelMap[resolvedPublicModel] ?: targetModel
    }

    private fun weightedRotate(levelId: String, states: List<UpstreamKeyState>): List<UpstreamKeyState> {
        if (states.isEmpty()) return states
        val expanded = states.flatMap { state -> List(state.key.weight.coerceAtLeast(1)) { state } }
        val cursor = cursors.computeIfAbsent(levelId) { AtomicInteger(0) }
        val start = cursor.getAndIncrement().floorMod(expanded.size)
        return (expanded.drop(start) + expanded.take(start)).distinct()
    }

    private fun providerFor(level: PoolLevelConfig, key: PooledKeyConfig): UpstreamProviderConfig = key.provider ?: level.provider

    private fun priorityForLevel(level: PoolLevelConfig): Int =
        level.levelId.takeIf { "-p" in it }
            ?.substringAfterLast("-p")
            ?.toIntOrNull()
            ?: level.levelIndex

    private fun tierId(route: ResolvedRoute, level: PoolLevelConfig): String = "${route.chain.chainId}:${route.routedModel}:${level.levelId}"

    private fun syncSharedState(state: UpstreamKeyState) {
        val shared = runtimeRegistry.channelState(state.key.keyId)
        shared.cooldownUntilEpochMs = state.cooldownUntilEpochMs
        shared.lastError = state.lastError
        shared.lastStatus = state.lastStatus
        shared.lastSelectedAt = state.lastSelectedAt
        shared.lastAttemptAtEpochMs = state.lastAttemptAtEpochMs
        shared.lastSuccessAtEpochMs = state.lastSuccessAtEpochMs
        shared.failureScope = state.failureScope
        shared.failureKind = state.failureKind
    }

    private fun Int.floorMod(mod: Int): Int = Math.floorMod(this, mod)
}
