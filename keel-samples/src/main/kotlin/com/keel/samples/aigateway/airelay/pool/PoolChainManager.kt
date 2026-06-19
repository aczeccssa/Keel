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

enum class KeyStatus { HEALTHY, COOLDOWN, DEGRADED, DISABLED }

data class UpstreamKeyState(
    val chain: PoolChainConfig,
    val level: PoolLevelConfig,
    val key: PooledKeyConfig,
    val status: AtomicReferenceStatus = AtomicReferenceStatus(KeyStatus.HEALTHY),
    val currentConcurrency: AtomicInteger = AtomicInteger(0),
    val totalRequests: AtomicLong = AtomicLong(0),
    val totalFailures: AtomicLong = AtomicLong(0),
    val consecutiveFailures: AtomicInteger = AtomicInteger(0),
    @Volatile var cooldownUntilEpochMs: Long = 0L,
    @Volatile var lastError: String? = null,
    @Volatile var lastSelectedAt: Long? = null,
)

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
    val resolvedModel: String,
    val upstreamModel: String,
    val requestedModel: String,
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
    private val runtimeRegistry: PoolRuntimeRegistry = PoolRuntimeRegistry(),
) : PoolChainSnapshotProvider {
    private val allChains = chains
    private val chainByGroup = chains.associateBy { it.chainId }.toMutableMap().apply {
        if (DEFAULT_ROUTING_GROUP_ID !in this) {
            this["default-chain"]?.let { put(DEFAULT_ROUTING_GROUP_ID, it) }
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
                    cooldownUntilEpochMs = shared.cooldownUntilEpochMs,
                    lastError = shared.lastError,
                    lastSelectedAt = shared.lastSelectedAt,
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

    fun acquire(groupId: String, model: String, excludedKeyIds: Set<String> = emptySet()): PoolLease? {
        val route = resolveRoute(groupId, model)
        return when (route.routingPolicy) {
            AliasRoutingPolicy.POOL_BALANCE -> acquirePoolBalance(route, excludedKeyIds)
            AliasRoutingPolicy.ORDERED_FAILOVER -> acquireOrderedFailover(route, excludedKeyIds)
        }
    }

    fun selectCandidates(groupId: String, model: String): List<PoolSelection> {
        val route = resolveRoute(groupId, model)
        return when (route.routingPolicy) {
            AliasRoutingPolicy.POOL_BALANCE -> previewPoolBalance(route)
            AliasRoutingPolicy.ORDERED_FAILOVER -> previewOrderedFailover(route)
        }
    }

    fun explainSelection(groupId: String, model: String): PoolExplainResponse {
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
                val effectiveStatus = effectiveStatus(state, state.key.maxConcurrency, now)
                val provider = providerFor(level, state.key)
                val base = PoolExplainCandidateView(
                    channelId = state.key.keyId,
                    channelName = provider.providerId,
                    priority = priorityForLevel(level),
                    weight = state.key.weight,
                    effectiveStatus = effectiveStatus,
                    currentConcurrency = state.currentConcurrency.get(),
                    maxConcurrency = state.key.maxConcurrency,
                    score = ordered.firstOrNull { it.keyState.key.keyId == state.key.keyId }?.let {
                        scoreCandidate(level, route.requestedModel, ordered.map { candidate -> candidate.keyState }, state)
                    },
                    targetModel = target?.model ?: route.targets.firstOrNull()?.model.orEmpty(),
                    resolvedModel = target?.let { resolvePublicModel(state.key, it.model) } ?: route.routedModel,
                    upstreamModel = target?.let {
                        resolveUpstreamModel(state.key, it.model, resolvePublicModel(state.key, it.model))
                    } ?: route.routedModel,
                )
                if (state.status.get() == KeyStatus.HEALTHY && state.currentConcurrency.get() < state.key.maxConcurrency && target != null) {
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
            priorityTiers = tiers,
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
                    resolvedModel = chain.modelAliases.firstOrNull().orEmpty(),
                    upstreamModel = chain.modelAliases.firstOrNull().orEmpty(),
                    requestedModel = chain.modelAliases.firstOrNull().orEmpty(),
                )
            }
        }
    }

    fun markSuccess(selection: PoolSelection) {
        selection.keyState.consecutiveFailures.set(0)
        selection.keyState.lastError = null
        if (selection.keyState.status.get() == KeyStatus.DEGRADED) {
            selection.keyState.status.set(KeyStatus.HEALTHY)
        }
        syncSharedState(selection.keyState)
    }

    fun markFailure(selection: PoolSelection, status: Int?, message: String?, retryAfterSeconds: Long? = null) {
        val state = selection.keyState
        state.totalFailures.incrementAndGet()
        val consecutiveFailures = state.consecutiveFailures.incrementAndGet()
        state.lastError = message ?: status?.toString()
        when (status) {
            401, 403 -> state.status.set(KeyStatus.DISABLED)
            408, 429 -> {
                state.status.set(KeyStatus.COOLDOWN)
                state.cooldownUntilEpochMs = System.currentTimeMillis() +
                    (retryAfterSeconds?.times(1000)
                        ?: cooldownBackoffMs(30_000L, consecutiveFailures, selection.level.cooldownMs))
            }
            in 500..599, null -> {
                state.status.set(KeyStatus.COOLDOWN)
                val baseMs = if (status == null) 30_000L else 10_000L
                state.cooldownUntilEpochMs = System.currentTimeMillis() + cooldownBackoffMs(baseMs, consecutiveFailures, selection.level.cooldownMs)
            }
            in 400..499 -> {
                if (state.status.get() == KeyStatus.COOLDOWN && System.currentTimeMillis() >= state.cooldownUntilEpochMs) {
                    state.status.set(KeyStatus.HEALTHY)
                    state.cooldownUntilEpochMs = 0L
                }
            }
            else -> state.status.set(KeyStatus.DEGRADED)
        }
        syncSharedState(state)
    }

    fun reset(chainId: String, keyId: String): Boolean {
        val state = states.entries.firstOrNull { it.key.chainId == chainId }
            ?.value
            ?.values
            ?.flatten()
            ?.firstOrNull { it.key.keyId == keyId }
            ?: return false
        state.status.set(KeyStatus.HEALTHY)
        state.consecutiveFailures.set(0)
        state.cooldownUntilEpochMs = 0L
        state.lastError = null
        syncSharedState(state)
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
                            cooldownKeys = keyStates.count { it.status.get() == KeyStatus.COOLDOWN },
                            degradedKeys = keyStates.count { it.status.get() == KeyStatus.DEGRADED },
                            disabledKeys = keyStates.count { effectiveStatus(it, it.key.maxConcurrency, now) == "SATURATED" || it.status.get() == KeyStatus.DISABLED },
                            keys = keyStates.map { state ->
                                PoolKeyHealth(
                                    keyId = state.key.keyId,
                                    status = effectiveStatus(state, state.key.maxConcurrency, now),
                                    totalRequests = state.totalRequests.get(),
                                    totalFailures = state.totalFailures.get(),
                                    currentConcurrency = state.currentConcurrency.get(),
                                    maxConcurrency = state.key.maxConcurrency,
                                    cooldownUntilEpochMs = state.cooldownUntilEpochMs.takeIf { it > 0L },
                                    lastError = state.lastError
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
                    it.status.get() == KeyStatus.HEALTHY &&
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
                        resolvedModel = resolvedModel,
                        upstreamModel = resolveUpstreamModel(state.key, target.model, resolvedModel),
                        requestedModel = route.requestedModel,
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
                    resolvedModel = resolvedModel,
                    upstreamModel = resolveUpstreamModel(candidate.keyState.key, candidate.target.model, resolvedModel),
                    requestedModel = route.requestedModel,
                    matchedAliasName = route.matchedAlias?.aliasName,
                    matchedAliasCreditMultiplier = route.matchedAlias?.creditMultiplier,
                )
            }
        }
    }

    private fun acquireOrderedFailover(route: ResolvedRoute, excludedKeyIds: Set<String>): PoolLease? {
        previewOrderedFailover(route).forEach { selection ->
            if (selection.keyState.key.keyId in excludedKeyIds) return@forEach
            if (tryAcquire(selection.keyState)) {
                selection.keyState.totalRequests.incrementAndGet()
                selection.keyState.lastSelectedAt = System.currentTimeMillis()
                syncSharedState(selection.keyState)
                return PoolLease(selection) {
                    selection.keyState.currentConcurrency.decrementAndGet()
                    syncSharedState(selection.keyState)
                }
            }
        }
        return null
    }

    private fun acquirePoolBalance(route: ResolvedRoute, excludedKeyIds: Set<String>): PoolLease? {
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
                val active = candidates.filter {
                    it.keyState.status.get() == KeyStatus.HEALTHY &&
                        it.keyState.currentConcurrency.get() < it.keyState.key.maxConcurrency
                }.toMutableList()
                if (active.isEmpty()) return@withLock
                while (active.isNotEmpty() && lease == null) {
                    val totalWeight = active.sumOf { max(it.keyState.key.weight, 1) }
                    val scored = active.map { candidate ->
                        val keyId = candidate.keyState.key.keyId
                        val base = tierState.currentWeights[keyId] ?: 0.0
                        val nextWeight = base + max(candidate.keyState.key.weight, 1)
                        val saturation = candidate.keyState.currentConcurrency.get().toDouble() /
                            max(candidate.keyState.key.maxConcurrency, 1).toDouble()
                        ScoredCandidate(candidate, nextWeight - saturation * totalWeight, nextWeight)
                    }
                    val selected = chooseScoredCandidate(scored, tierState.tieCursor.get())
                    if (selected != null && tryAcquire(selected.candidate.keyState)) {
                        val selectedKeyId = selected.candidate.keyState.key.keyId
                        scored.forEach { score ->
                            val keyId = score.candidate.keyState.key.keyId
                            tierState.currentWeights[keyId] = if (keyId == selectedKeyId) {
                                score.nextWeight - totalWeight
                            } else {
                                score.nextWeight
                            }
                        }
                        tierState.tieCursor.incrementAndGet()
                        val keyState = selected.candidate.keyState
                        keyState.totalRequests.incrementAndGet()
                        keyState.lastSelectedAt = System.currentTimeMillis()
                        syncSharedState(keyState)
                        val resolvedModel = resolvePublicModel(keyState.key, selected.candidate.target.model)
                        val selection = PoolSelection(
                            chain = route.chain,
                            level = level,
                            provider = providerFor(level, keyState.key),
                            keyState = keyState,
                            resolvedModel = resolvedModel,
                            upstreamModel = resolveUpstreamModel(keyState.key, selected.candidate.target.model, resolvedModel),
                            requestedModel = route.requestedModel,
                            matchedAliasName = route.matchedAlias?.aliasName,
                            matchedAliasCreditMultiplier = route.matchedAlias?.creditMultiplier,
                        )
                        lease = PoolLease(selection) {
                            keyState.currentConcurrency.decrementAndGet()
                            syncSharedState(keyState)
                        }
                    } else {
                        active.removeAll { it.keyState.key.keyId == selected?.candidate?.keyState?.key?.keyId }
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
                PoolChannelRuntimeView(
                    channelId = candidate.keyState.key.keyId,
                    channelName = provider.providerId,
                    effectiveStatus = effectiveStatus(candidate.keyState, candidate.keyState.key.maxConcurrency, now),
                    priority = priorityForLevel(level),
                    weight = candidate.keyState.key.weight,
                    currentConcurrency = candidate.keyState.currentConcurrency.get(),
                    maxConcurrency = candidate.keyState.key.maxConcurrency,
                    totalRequests = candidate.keyState.totalRequests.get(),
                    totalFailures = candidate.keyState.totalFailures.get(),
                    cooldownUntilEpochMs = candidate.keyState.cooldownUntilEpochMs.takeIf { it > 0L },
                    lastError = candidate.keyState.lastError,
                    lastSelectedAt = candidate.keyState.lastSelectedAt,
                )
            }
            PoolView(
                groupId = groupId,
                aliasOrModel = aliasOrModel,
                routingPolicy = route.routingPolicy.name,
                priority = priorityForLevel(level),
                totalInflight = channels.sumOf { it.currentConcurrency },
                channels = channels,
            )
        }
    }

    private fun orderPoolCandidates(route: ResolvedRoute, level: PoolLevelConfig, candidates: List<PoolCandidate>): List<PoolCandidate> {
        val eligible = candidates.filter {
            it.keyState.status.get() == KeyStatus.HEALTHY &&
                it.keyState.currentConcurrency.get() < it.keyState.key.maxConcurrency
        }
        if (eligible.isEmpty()) return emptyList()
        val tierState = runtimeRegistry.tierState(tierId(route, level))
        val totalWeight = eligible.sumOf { max(it.keyState.key.weight, 1) }
        val scored = eligible.map { candidate ->
            val base = tierState.currentWeights[candidate.keyState.key.keyId] ?: 0.0
            val nextWeight = base + max(candidate.keyState.key.weight, 1)
            val saturation = candidate.keyState.currentConcurrency.get().toDouble() /
                max(candidate.keyState.key.maxConcurrency, 1).toDouble()
            ScoredCandidate(candidate, nextWeight - saturation * totalWeight, nextWeight)
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
        level: PoolLevelConfig,
        routeKey: String,
        keyStates: List<UpstreamKeyState>,
        state: UpstreamKeyState,
    ): Double {
        val tierState = runtimeRegistry.tierState("${level.levelId}:$routeKey")
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

    private fun exclusionReason(state: UpstreamKeyState, target: AliasTargetConfig?): String = when {
        target == null -> "UNSUPPORTED_MODEL"
        state.status.get() != KeyStatus.HEALTHY -> state.status.get().name
        state.currentConcurrency.get() >= state.key.maxConcurrency -> "SATURATED"
        else -> "INELIGIBLE"
    }

    private fun maybeRecover(state: UpstreamKeyState, now: Long) {
        if (state.status.get() == KeyStatus.COOLDOWN && now >= state.cooldownUntilEpochMs) {
            state.status.set(KeyStatus.HEALTHY)
            state.cooldownUntilEpochMs = 0L
            syncSharedState(state)
        }
    }

    private fun cooldownBackoffMs(baseMs: Long, consecutiveFailures: Int, maxMs: Long): Long {
        val multiplier = 1L shl (consecutiveFailures - 1).coerceIn(0, 10)
        return (baseMs * multiplier).coerceAtMost(maxMs)
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

    private fun effectiveStatus(state: UpstreamKeyState, maxConcurrency: Int, now: Long): String {
        maybeRecover(state, now)
        if (state.status.get() == KeyStatus.HEALTHY && state.currentConcurrency.get() >= maxConcurrency) {
            return "SATURATED"
        }
        return state.status.get().name
    }

    private fun providerFor(level: PoolLevelConfig, key: PooledKeyConfig): UpstreamProviderConfig = key.provider ?: level.provider

    private fun priorityForLevel(level: PoolLevelConfig): Int = level.levelId.substringAfterLast("-p", "0").toIntOrNull() ?: 0

    private fun tierId(route: ResolvedRoute, level: PoolLevelConfig): String = "${route.chain.chainId}:${route.routedModel}:${level.levelId}"

    private fun syncSharedState(state: UpstreamKeyState) {
        val shared = runtimeRegistry.channelState(state.key.keyId)
        shared.cooldownUntilEpochMs = state.cooldownUntilEpochMs
        shared.lastError = state.lastError
        shared.lastSelectedAt = state.lastSelectedAt
    }

    private fun Int.floorMod(mod: Int): Int = Math.floorMod(this, mod)
}
