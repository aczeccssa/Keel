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
    @Volatile var lastSelectedAt: Long? = null,
)

private data class RuntimeMetricEvent(
    val timestampMs: Long,
    val channelId: String,
    val success: Boolean,
    val latencyMs: Long,
)

private data class MutableTraceAttempt(
    val channelId: String,
    val priority: Int,
    val selectedAtEpochMs: Long,
    var outcome: String = "PENDING",
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

class PoolRuntimeRegistry {
    private val channelStates = ConcurrentHashMap<String, SharedChannelRuntimeState>()
    private val tierStates = ConcurrentHashMap<String, TierRuntimeState>()
    private val metricEvents = ConcurrentHashMap<String, ConcurrentLinkedDeque<RuntimeMetricEvent>>()
    private val failoverEvents = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val traces = ConcurrentHashMap<String, MutableRequestTrace>()
    private val traceOrder = ConcurrentLinkedDeque<String>()

    fun channelState(channelId: String): SharedChannelRuntimeState =
        channelStates.computeIfAbsent(channelId) { SharedChannelRuntimeState() }

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
                priority = selection.priority,
                selectedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    fun recordOutcome(selection: PoolSelection, success: Boolean, status: Int?, reason: String?, latencyMs: Long) {
        val now = System.currentTimeMillis()
        metricEvents.computeIfAbsent(selection.poolId) { ConcurrentLinkedDeque() }
            .addLast(RuntimeMetricEvent(now, selection.keyState.key.keyId, success, latencyMs.coerceAtLeast(0)))
        pruneMetrics(selection.poolId, now)
        val trace = selection.traceId?.let(traces::get) ?: return
        synchronized(trace) {
            val attempt = trace.attempts.lastOrNull { it.channelId == selection.keyState.key.keyId && it.outcome == "PENDING" }
            if (attempt != null) {
                attempt.outcome = if (success) "SUCCESS" else "FAILED"
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
                    PoolRequestAttemptView(it.channelId, it.priority, it.outcome, it.status, it.reason, it.latencyMs, it.selectedAtEpochMs)
                },
                selectedChannelId = trace.selectedChannelId,
                failoverCount = trace.failoverCount,
                outcome = trace.outcome,
                completedAtEpochMs = trace.completedAtEpochMs,
            )
        }
    }

    fun metrics(poolId: String, channelId: String? = null, windowMs: Long): RuntimeWindowMetrics {
        val now = System.currentTimeMillis()
        pruneMetrics(poolId, now)
        val cutoff = now - windowMs
        val events = metricEvents[poolId].orEmpty().filter { it.timestampMs >= cutoff && (channelId == null || it.channelId == channelId) }
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
}
