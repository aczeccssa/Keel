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
)

class PoolChainManager(
    chains: List<PoolChainConfig>
) : PoolChainSnapshotProvider {
    private val chainByGroup = chains.associateBy { it.chainId }
    private val states = chains.associateWith { chain ->
        chain.levels.sortedBy { it.levelIndex }.associateWith { level ->
            level.keys.map { key -> UpstreamKeyState(chain, level, key) }
        }
    }
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    fun resolve(groupId: String, model: String): PoolChainConfig {
        val chain = chainByGroup[groupId]
            ?: throw PluginApiException(404, "No routing group $groupId")
        if (resolveRequestedModel(chain, model) == null) {
            throw PluginApiException(404, "Model $model is not available in group $groupId")
        }
        return chain
    }

    fun selectCandidates(groupId: String, model: String): List<PoolSelection> {
        val chain = resolve(groupId, model)
        val now = System.currentTimeMillis()
        val routedModel = resolveRequestedModel(chain, model)
            ?: throw PluginApiException(404, "Model $model is not available in group $groupId")
        val targetModels = resolveTargetModels(chain, model, routedModel)
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
                    )
                }
            }
        }
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

    private fun resolveTargetModels(
        chain: PoolChainConfig,
        requestedModel: String,
        routedModel: String,
    ): List<com.keel.samples.aigateway.airelay.AliasTargetConfig> {
        val alias = chain.aliasRoutes.firstOrNull { it.enabled && it.aliasName == requestedModel }
        if (alias != null) return alias.orderedTargets()
        if (routedModel != requestedModel) {
            val fallbackAlias = chain.aliasRoutes.firstOrNull { it.enabled && it.aliasName == routedModel }
            if (fallbackAlias != null) return fallbackAlias.orderedTargets()
        }
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
        selection.keyState.lastError = null
        if (selection.keyState.status.get() == KeyStatus.DEGRADED) selection.keyState.status.set(KeyStatus.HEALTHY)
    }

    fun markFailure(selection: PoolSelection, status: Int?, message: String?, retryAfterSeconds: Long? = null) {
        val state = selection.keyState
        state.totalFailures.incrementAndGet()
        state.lastError = message ?: status?.toString()
        when (status) {
            401, 403 -> state.status.set(KeyStatus.DISABLED)
            429 -> {
                state.status.set(KeyStatus.COOLDOWN)
                state.cooldownUntilEpochMs = System.currentTimeMillis() + (retryAfterSeconds?.times(1000) ?: selection.level.cooldownMs)
            }
            in 500..599, null -> {
                state.status.set(KeyStatus.COOLDOWN)
                state.cooldownUntilEpochMs = System.currentTimeMillis() + selection.level.cooldownMs
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
