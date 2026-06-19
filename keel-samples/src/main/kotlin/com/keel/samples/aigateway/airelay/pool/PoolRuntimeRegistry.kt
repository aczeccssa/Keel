package com.keel.samples.aigateway.airelay.pool

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SharedChannelRuntimeState(
    val status: AtomicReferenceStatus = AtomicReferenceStatus(KeyStatus.HEALTHY),
    val currentConcurrency: AtomicInteger = AtomicInteger(0),
    val totalRequests: AtomicLong = AtomicLong(0),
    val totalFailures: AtomicLong = AtomicLong(0),
    val consecutiveFailures: AtomicInteger = AtomicInteger(0),
    @Volatile var cooldownUntilEpochMs: Long = 0L,
    @Volatile var lastError: String? = null,
    @Volatile var lastSelectedAt: Long? = null,
)

class TierRuntimeState {
    private val lock = ReentrantLock()
    val tieCursor: AtomicLong = AtomicLong(0)
    val currentWeights: MutableMap<String, Double> = ConcurrentHashMap()

    fun <T> withLock(action: () -> T): T = lock.withLock(action)
}

class PoolRuntimeRegistry {
    private val channelStates = ConcurrentHashMap<String, SharedChannelRuntimeState>()
    private val tierStates = ConcurrentHashMap<String, TierRuntimeState>()

    fun channelState(channelId: String): SharedChannelRuntimeState =
        channelStates.computeIfAbsent(channelId) { SharedChannelRuntimeState() }

    fun tierState(tierId: String): TierRuntimeState =
        tierStates.computeIfAbsent(tierId) { TierRuntimeState() }
}
