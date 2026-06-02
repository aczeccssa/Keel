package com.keel.samples.aigateway.airelay.pool

import com.keel.contract.ai.PoolChainHealth
import com.keel.contract.ai.PoolChainSnapshot
import com.keel.contract.ai.PoolChainSnapshotProvider
import com.keel.contract.ai.PoolKeyHealth
import com.keel.contract.ai.PoolLevelHealth
import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    val keyState: UpstreamKeyState
)

class PoolChainManager(
    chains: List<PoolChainConfig>
) : PoolChainSnapshotProvider {
    private val chainByModel = chains.flatMap { chain -> chain.modelAliases.map { it to chain } }.toMap()
    private val states = chains.associateWith { chain ->
        chain.levels.sortedBy { it.levelIndex }.associateWith { level ->
            level.keys.map { key -> UpstreamKeyState(chain, level, key) }
        }
    }
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    fun resolve(model: String): PoolChainConfig = chainByModel[model]
        ?: throw com.keel.kernel.plugin.PluginApiException(404, "No pool chain for model $model")

    fun selectCandidates(model: String): List<PoolSelection> {
        val chain = resolve(model)
        val now = System.currentTimeMillis()
        return states.getValue(chain).entries.sortedBy { it.key.levelIndex }.flatMap { (level, keyStates) ->
            keyStates.forEach { maybeRecover(it, now) }
            val available = keyStates.filter {
                it.status.get() == KeyStatus.HEALTHY &&
                    it.currentConcurrency.get() < it.key.maxConcurrency &&
                    (it.key.supportedModels.isEmpty() || model in it.key.supportedModels)
            }
            val ordered = rotate(level.levelId, available)
            ordered.map { PoolSelection(chain, level, it) }
        }
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
                        PoolLevelHealth(
                            levelId = level.levelId,
                            levelIndex = level.levelIndex,
                            providerId = level.provider.providerId,
                            protocol = level.provider.protocol.name,
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

    private fun rotate(levelId: String, states: List<UpstreamKeyState>): List<UpstreamKeyState> {
        if (states.isEmpty()) return states
        val cursor = cursors.computeIfAbsent(levelId) { AtomicInteger(0) }
        val start = cursor.getAndIncrement().floorMod(states.size)
        return states.drop(start) + states.take(start)
    }

    private fun Int.floorMod(mod: Int): Int = Math.floorMod(this, mod)
}
