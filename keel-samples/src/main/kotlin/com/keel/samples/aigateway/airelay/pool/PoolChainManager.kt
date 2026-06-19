package com.keel.samples.aigateway.airelay.pool

import com.keel.contract.ai.PoolChainHealth
import com.keel.contract.ai.PoolChainSnapshot
import com.keel.contract.ai.PoolChainSnapshotProvider
import com.keel.contract.ai.PoolKeyHealth
import com.keel.contract.ai.PoolLevelHealth
import com.keel.samples.aigateway.airelay.GroupExposureMode
import com.keel.samples.aigateway.airelay.modelVariantSemantics
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    @Volatile var lastError: String? = null
)

class AtomicReferenceStatus(initial: KeyStatus) {
    @Volatile private var value: KeyStatus = initial
    fun get(): KeyStatus = value
    fun set(next: KeyStatus) { value = next }
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

class PoolChainManager(
    chains: List<PoolChainConfig>
) : PoolChainSnapshotProvider {
    private val allChains = chains
    private val chainByGroup = chains.associateBy { it.chainId }.toMutableMap().apply {
        if (DEFAULT_ROUTING_GROUP_ID !in this) {
            this["default-chain"]?.let { put(DEFAULT_ROUTING_GROUP_ID, it) }
        }
    }
    private val states = chains.associateWith { chain ->
        chain.levels.sortedBy { it.levelIndex }.associateWith { level ->
            level.keys.map { key -> UpstreamKeyState(chain, level, key) }
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

    private fun resolveChainForModel(groupId: String, model: String): PoolChainConfig {
        val exact = chainByGroup[groupId]
            ?: throw PluginApiException(404, "No routing group $groupId")
        if (resolveRequestedModel(exact, model) != null) return exact
        if (groupId == DEFAULT_ROUTING_GROUP_ID) {
            allChains.firstOrNull { resolveRequestedModel(it, model) != null }?.let { return it }
        }
        return exact
    }

    fun selectCandidates(groupId: String, model: String): List<PoolSelection> {
        val chain = resolve(groupId, model)
        val now = System.currentTimeMillis()
        val routedModel = resolveRequestedModel(chain, model)
            ?: throw PluginApiException(404, "Model $model is not available in group $groupId")
        val matchedAlias = resolveMatchedAlias(chain, model, routedModel)
        val targetModels = resolveTargetModels(chain, model, routedModel, matchedAlias)
        return targetModels.flatMap { target ->
            states.getValue(chain).entries.sortedBy { it.key.levelIndex }.flatMap { (level, keyStates) ->
                keyStates.forEach { maybeRecover(it, now) }
                val available = keyStates.filter {
                    it.status.get() == KeyStatus.HEALTHY &&
                        it.currentConcurrency.get() < it.key.maxConcurrency &&
                        (target.channelId == null || it.key.keyId == target.channelId) &&
                        supportsTargetModel(it.key, target.model)
                }
                weightedRotate(level.levelId, available).map {
                    val resolvedModel = resolvePublicModel(it.key, target.model)
                    PoolSelection(
                        chain = chain,
                        level = level,
                        provider = providerFor(level, it.key),
                        keyState = it,
                        resolvedModel = resolvedModel,
                        upstreamModel = resolveUpstreamModel(it.key, target.model, resolvedModel),
                        requestedModel = model,
                        matchedAliasName = matchedAlias?.aliasName,
                        matchedAliasCreditMultiplier = matchedAlias?.creditMultiplier,
                    )
                }
            }
        }
    }

    fun explainAvailability(groupId: String, model: String): String = runCatching {
        val chain = resolveChainForModel(groupId, model)
        val routedModel = resolveRequestedModel(chain, model)
        val matchedAlias = routedModel?.let { resolveMatchedAlias(chain, model, it) }
        val targets = routedModel?.let { resolveTargetModels(chain, model, it, matchedAlias) }.orEmpty()
        val now = System.currentTimeMillis()
        val levels = states.getValue(chain).entries
            .sortedBy { it.key.levelIndex }
            .joinToString("; ") { (level, keyStates) ->
                keyStates.forEach { maybeRecover(it, now) }
                val keySummary = keyStates.joinToString(", ") { state ->
                    val provider = providerFor(level, state.key)
                    val targetMatch = if (targets.isEmpty()) {
                        "none"
                    } else {
                        targets.joinToString("|") { target ->
                            val channelOk = target.channelId == null || state.key.keyId == target.channelId
                            val modelOk = supportsTargetModel(state.key, target.model)
                            "${target.model}@${target.channelId ?: "*"}(channel=$channelOk,model=$modelOk)"
                        }
                    }
                    "${state.key.keyId}[status=${state.status.get()},cc=${state.currentConcurrency.get()}/${state.key.maxConcurrency},provider=${provider.protocol},target=$targetMatch,lastError=${state.lastError ?: "-"}]"
                }
                "${level.levelId}{$keySummary}"
            }
        val targetSummary = targets.joinToString(",") { "${it.model}@${it.channelId ?: "*"}" }.ifBlank { "-" }
        "group=$groupId chain=${chain.chainId} requested=$model routed=${routedModel ?: "-"} alias=${matchedAlias?.aliasName ?: "-"} targets=$targetSummary levels=$levels"
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
        matchedAlias: com.keel.samples.aigateway.airelay.AliasRouteConfig?,
    ): List<com.keel.samples.aigateway.airelay.AliasTargetConfig> {
        if (matchedAlias != null) return matchedAlias.orderedTargets()
        return when (chain.exposureMode.normalized()) {
            GroupExposureMode.ALIASES_ONLY -> throw PluginApiException(404, "Model $requestedModel is not available in group ${chain.chainId}")
            GroupExposureMode.ALIASES_AND_MODELS,
            GroupExposureMode.ALL_MODELS -> listOf(com.keel.samples.aigateway.airelay.AliasTargetConfig(routedModel))
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

    fun markSuccess(selection: PoolSelection) {
        selection.keyState.totalRequests.incrementAndGet()
        selection.keyState.consecutiveFailures.set(0)
        selection.keyState.lastError = null
        if (selection.keyState.status.get() == KeyStatus.DEGRADED) selection.keyState.status.set(KeyStatus.HEALTHY)
    }

    fun markFailure(selection: PoolSelection, status: Int?, message: String?, retryAfterSeconds: Long? = null) {
        val state = selection.keyState
        state.totalFailures.incrementAndGet()
        val consecutiveFailures = state.consecutiveFailures.incrementAndGet()
        state.lastError = message ?: status?.toString()
        when (status) {
            401, 403 -> state.status.set(KeyStatus.DISABLED)
            429 -> {
                state.status.set(KeyStatus.COOLDOWN)
                state.cooldownUntilEpochMs = System.currentTimeMillis() +
                    (retryAfterSeconds?.times(1000) ?: cooldownBackoffMs(baseMs = 30_000L, consecutiveFailures, selection.level.cooldownMs))
            }
            in 500..599, null -> {
                state.status.set(KeyStatus.COOLDOWN)
                val baseMs = if (status == null) 30_000L else 10_000L
                state.cooldownUntilEpochMs = System.currentTimeMillis() + cooldownBackoffMs(baseMs, consecutiveFailures, selection.level.cooldownMs)
            }
            else -> state.status.set(KeyStatus.DEGRADED)
        }
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
        return true
    }

    override fun snapshot(): PoolChainSnapshot {
        return PoolChainSnapshot(
            chains = states.map { (chain, levels) ->
                PoolChainHealth(
                    chainId = chain.chainId,
                    modelAliases = chain.modelAliases,
                    levels = levels.entries.sortedBy { it.key.levelIndex }.map { (level, keyStates) ->
                        val providers = keyStates.map { providerFor(level, it.key) }
                        val providerId = providers.map { it.providerId }.distinct().joinToString("+")
                        val protocol = providers.map { it.protocol.name }.distinct().joinToString("+")
                        PoolLevelHealth(
                            levelId = level.levelId,
                            levelIndex = level.levelIndex,
                            providerId = providerId.ifBlank { level.provider.providerId },
                            protocol = protocol.ifBlank { level.provider.protocol.name },
                            healthyKeys = keyStates.count { it.status.get() == KeyStatus.HEALTHY },
                            cooldownKeys = keyStates.count { it.status.get() == KeyStatus.COOLDOWN },
                            degradedKeys = keyStates.count { it.status.get() == KeyStatus.DEGRADED },
                            disabledKeys = keyStates.count { it.status.get() == KeyStatus.DISABLED },
                            keys = keyStates.map { state ->
                                PoolKeyHealth(
                                keyId = state.key.keyId,
                                status = state.status.get().name,
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

    private fun maybeRecover(state: UpstreamKeyState, now: Long) {
        if (state.status.get() == KeyStatus.COOLDOWN && now >= state.cooldownUntilEpochMs) {
            state.status.set(KeyStatus.HEALTHY)
            state.cooldownUntilEpochMs = 0L
        }
    }

    private fun cooldownBackoffMs(baseMs: Long, consecutiveFailures: Int, maxMs: Long): Long {
        val multiplier = 1L shl (consecutiveFailures - 1).coerceIn(0, 10)
        return (baseMs * multiplier).coerceAtMost(maxMs)
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

    private fun Int.floorMod(mod: Int): Int = Math.floorMod(this, mod)
}
