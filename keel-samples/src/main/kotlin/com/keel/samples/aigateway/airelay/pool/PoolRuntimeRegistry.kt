package com.keel.samples.aigateway.airelay.pool

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
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
    val cooldownCount: AtomicLong = AtomicLong(0),
    @Volatile var cooldownUntilEpochMs: Long = 0L,
    @Volatile var lastError: String? = null,
    @Volatile var lastStatus: Int? = null,
    @Volatile var lastSelectedAt: Long? = null,
    @Volatile var lastAttemptAtEpochMs: Long? = null,
    @Volatile var lastSuccessAtEpochMs: Long? = null,
    @Volatile var failureScope: FailureScope? = null,
    @Volatile var failureKind: FailureKind? = null,
)

data class SharedRouteRuntimeState(
    val breakerState: AtomicReferenceBreakerState = AtomicReferenceBreakerState(BreakerState.CLOSED),
    val consecutiveTransientFailures: AtomicInteger = AtomicInteger(0),
    val halfOpenSuccesses: AtomicInteger = AtomicInteger(0),
    val probeInFlight: AtomicInteger = AtomicInteger(0),
    val cooldownCount: AtomicLong = AtomicLong(0),
    @Volatile var cooldownUntilEpochMs: Long = 0L,
    @Volatile var lastError: String? = null,
    @Volatile var lastStatus: Int? = null,
    @Volatile var lastSelectedAt: Long? = null,
    @Volatile var lastAttemptAtEpochMs: Long? = null,
    @Volatile var lastSuccessAtEpochMs: Long? = null,
    @Volatile var failureScope: FailureScope? = null,
    @Volatile var failureKind: FailureKind? = null,
)

data class RouteStateSnapshot(
    val breakerState: BreakerState,
    val cooldownUntilEpochMs: Long,
    val cooldownCount: Long,
    val consecutiveTransientFailures: Int,
    val halfOpenSuccesses: Int,
    val probeInFlight: Int,
    val lastError: String?,
    val lastStatus: Int?,
    val lastSelectedAt: Long?,
    val lastAttemptAtEpochMs: Long?,
    val lastSuccessAtEpochMs: Long?,
    val failureScope: FailureScope?,
    val failureKind: FailureKind?,
)

private data class RuntimeMetricEvent(
    val timestampMs: Long,
    val routeKey: String,
    val success: Boolean,
    val latencyMs: Long,
)

private data class MutableTraceAttempt(
    val channelId: String,
    val channelName: String,
    val modelKey: String,
    val resolvedModel: String,
    val upstreamModel: String,
    val priority: Int,
    val selectedAtEpochMs: Long,
    var outcome: String = "PENDING",
    var breakerState: String? = null,
    var failureScope: String? = null,
    var failureKind: String? = null,
    var status: Int? = null,
    var reason: String? = null,
    var latencyMs: Long? = null,
)

private data class MutableRequestTrace(
    val requestId: String,
    val groupId: String,
    val requestedModel: String,
    val routingPolicy: String,
    val attempts: MutableList<MutableTraceAttempt> = mutableListOf(),
    var selectedChannelId: String? = null,
    var failoverCount: Int = 0,
    var outcome: String = "PENDING",
    var completedAtEpochMs: Long? = null,
)

class TierRuntimeState {
    private val lock = ReentrantLock()
    val tieCursor: AtomicLong = AtomicLong(0)
    val currentWeights: MutableMap<String, Double> = ConcurrentHashMap()

    fun <T> withLock(action: () -> T): T = lock.withLock(action)
}

class AtomicReferenceBreakerState(initial: BreakerState) {
    @Volatile private var value: BreakerState = initial
    fun get(): BreakerState = value
    fun set(next: BreakerState) {
        value = next
    }
}

class PoolRuntimeRegistry {
    private val channelStates = ConcurrentHashMap<String, SharedChannelRuntimeState>()
    private val routeStates = ConcurrentHashMap<String, SharedRouteRuntimeState>()
    private val tierStates = ConcurrentHashMap<String, TierRuntimeState>()
    private val metricEvents = ConcurrentHashMap<String, ConcurrentLinkedDeque<RuntimeMetricEvent>>()
    private val failoverEvents = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val traces = ConcurrentHashMap<String, MutableRequestTrace>()
    private val traceOrder = ConcurrentLinkedDeque<String>()

    fun channelState(channelId: String): SharedChannelRuntimeState =
        channelStates.computeIfAbsent(channelId) { SharedChannelRuntimeState() }

    fun routeState(channelId: String, routeModelKey: String): SharedRouteRuntimeState =
        routeStates.computeIfAbsent(routeKey(channelId, routeModelKey)) { SharedRouteRuntimeState() }

    fun routeSnapshot(channelId: String, routeModelKey: String): RouteStateSnapshot {
        val state = routeState(channelId, routeModelKey)
        return RouteStateSnapshot(
            breakerState = state.breakerState.get(),
            cooldownUntilEpochMs = state.cooldownUntilEpochMs,
            cooldownCount = state.cooldownCount.get(),
            consecutiveTransientFailures = state.consecutiveTransientFailures.get(),
            halfOpenSuccesses = state.halfOpenSuccesses.get(),
            probeInFlight = state.probeInFlight.get(),
            lastError = state.lastError,
            lastStatus = state.lastStatus,
            lastSelectedAt = state.lastSelectedAt,
            lastAttemptAtEpochMs = state.lastAttemptAtEpochMs,
            lastSuccessAtEpochMs = state.lastSuccessAtEpochMs,
            failureScope = state.failureScope,
            failureKind = state.failureKind,
        )
    }

    fun tierState(tierId: String): TierRuntimeState =
        tierStates.computeIfAbsent(tierId) { TierRuntimeState() }

    fun beginTrace(requestId: String, groupId: String, requestedModel: String, routingPolicy: String) {
        traces.computeIfAbsent(requestId) {
            traceOrder.addLast(requestId)
            pruneTraces()
            MutableRequestTrace(requestId, groupId, requestedModel, routingPolicy)
        }
    }

    fun recordSelection(requestId: String?, selection: PoolSelection) {
        if (requestId == null) return
        val trace = traces[requestId] ?: return
        synchronized(trace) {
            trace.attempts += MutableTraceAttempt(
                channelId = selection.keyState.key.keyId,
                channelName = selection.provider.providerId,
                modelKey = selection.routeModelKey,
                resolvedModel = selection.resolvedModel,
                upstreamModel = selection.upstreamModel,
                priority = selection.priority,
                selectedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    fun recordOutcome(selection: PoolSelection, success: Boolean, status: Int?, reason: String?, latencyMs: Long) {
        val now = System.currentTimeMillis()
        metricEvents.computeIfAbsent(selection.poolId) { ConcurrentLinkedDeque() }
            .addLast(RuntimeMetricEvent(now, routeKey(selection.keyState.key.keyId, selection.routeModelKey), success, latencyMs.coerceAtLeast(0)))
        pruneMetrics(selection.poolId, now)
        val trace = selection.traceId?.let(traces::get) ?: return
        synchronized(trace) {
            val attempt = trace.attempts.lastOrNull {
                it.channelId == selection.keyState.key.keyId &&
                    it.modelKey == selection.routeModelKey &&
                    it.outcome == "PENDING"
            }
            if (attempt != null) {
                val routeState = routeState(selection.keyState.key.keyId, selection.routeModelKey)
                attempt.outcome = if (success) "SUCCESS" else "FAILED"
                attempt.breakerState = routeState.breakerState.get().name
                attempt.failureScope = routeState.failureScope?.name
                attempt.failureKind = routeState.failureKind?.name
                attempt.status = status
                attempt.reason = reason
                attempt.latencyMs = latencyMs
            }
            if (success) {
                trace.selectedChannelId = selection.keyState.key.keyId
                trace.outcome = "SUCCESS"
                trace.completedAtEpochMs = now
            }
        }
    }

    fun recordFailover(selection: PoolSelection) {
        val now = System.currentTimeMillis()
        failoverEvents.computeIfAbsent(selection.poolId) { ConcurrentLinkedDeque() }.addLast(now)
        val trace = selection.traceId?.let(traces::get) ?: return
        synchronized(trace) { trace.failoverCount += 1 }
    }

    fun completeTrace(requestId: String, outcome: String) {
        val trace = traces[requestId] ?: return
        synchronized(trace) {
            trace.outcome = outcome
            trace.completedAtEpochMs = System.currentTimeMillis()
        }
    }

    fun trace(requestId: String?, groupId: String, requestedModel: String): PoolRequestTraceView? {
        val trace = if (requestId != null) {
            traces[requestId]
        } else {
            traceOrder.toList().asReversed().asSequence().mapNotNull(traces::get)
                .firstOrNull { it.groupId == groupId && it.requestedModel == requestedModel }
        } ?: return null
        synchronized(trace) {
            return PoolRequestTraceView(
                requestId = trace.requestId,
                groupId = trace.groupId,
                requestedModel = trace.requestedModel,
                routingPolicy = trace.routingPolicy,
                attempts = trace.attempts.map {
                    PoolRequestAttemptView(
                        channelId = it.channelId,
                        channelName = it.channelName,
                        modelKey = it.modelKey,
                        resolvedModel = it.resolvedModel,
                        upstreamModel = it.upstreamModel,
                        priority = it.priority,
                        outcome = it.outcome,
                        breakerState = it.breakerState,
                        failureScope = it.failureScope,
                        failureKind = it.failureKind,
                        status = it.status,
                        reason = it.reason,
                        latencyMs = it.latencyMs,
                        selectedAtEpochMs = it.selectedAtEpochMs,
                    )
                },
                selectedChannelId = trace.selectedChannelId,
                failoverCount = trace.failoverCount,
                outcome = trace.outcome,
                completedAtEpochMs = trace.completedAtEpochMs,
            )
        }
    }

    fun metrics(poolId: String, routeKey: String? = null, windowMs: Long): RuntimeWindowMetrics {
        val now = System.currentTimeMillis()
        pruneMetrics(poolId, now)
        val cutoff = now - windowMs
        val events = metricEvents[poolId].orEmpty().filter { it.timestampMs >= cutoff && (routeKey == null || it.routeKey == routeKey) }
        if (events.isEmpty()) return RuntimeWindowMetrics()
        val failures = events.count { !it.success }.toLong()
        val latencies = events.map { it.latencyMs }.sorted()
        return RuntimeWindowMetrics(
            selectedRequests = events.size.toLong(),
            successRequests = events.count { it.success }.toLong(),
            failedRequests = failures,
            errorRate = failures.toDouble() / events.size.toDouble(),
            p50LatencyMs = percentile(latencies, 0.50),
            p95LatencyMs = percentile(latencies, 0.95),
            p99LatencyMs = percentile(latencies, 0.99),
        )
    }

    fun failoverCount(poolId: String, windowMs: Long): Long {
        val now = System.currentTimeMillis()
        val events = failoverEvents[poolId] ?: return 0
        val cutoff = now - 15 * 60_000L
        while (events.peekFirst()?.let { it < cutoff } == true) events.pollFirst()
        return events.count { it >= now - windowMs }.toLong()
    }

    fun resetRouteState(channelId: String, routeModelKey: String) {
        val state = routeState(channelId, routeModelKey)
        state.breakerState.set(BreakerState.CLOSED)
        state.consecutiveTransientFailures.set(0)
        state.halfOpenSuccesses.set(0)
        state.probeInFlight.set(0)
        state.cooldownUntilEpochMs = 0L
        state.lastError = null
        state.lastStatus = null
        state.failureScope = null
        state.failureKind = null
    }

    private fun pruneMetrics(poolId: String, now: Long) {
        val events = metricEvents[poolId] ?: return
        val cutoff = now - 15 * 60_000L
        while (events.peekFirst()?.timestampMs?.let { it < cutoff } == true) events.pollFirst()
    }

    private fun pruneTraces() {
        while (traceOrder.size > 500) traceOrder.pollFirst()?.let(traces::remove)
    }

    private fun percentile(values: List<Long>, percentile: Double): Long {
        if (values.isEmpty()) return 0
        val index = kotlin.math.ceil(values.size * percentile).toInt().coerceIn(1, values.size) - 1
        return values[index]
    }

    companion object {
        fun routeKey(channelId: String, routeModelKey: String): String = "$channelId::$routeModelKey"
    }
}
