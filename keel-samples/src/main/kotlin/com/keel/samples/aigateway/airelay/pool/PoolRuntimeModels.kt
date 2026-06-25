package com.keel.samples.aigateway.airelay.pool

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

data class PoolLease(
    val selection: PoolSelection,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) {
            releaseAction()
        }
    }
}

@Serializable
data class PoolExplainCandidateView(
    val channelId: String,
    val channelName: String,
    val priority: Int,
    val weight: Int,
    val effectiveStatus: String,
    val breakerState: String = "CLOSED",
    val failureScope: String? = null,
    val failureKind: String? = null,
    val currentConcurrency: Int,
    val maxConcurrency: Int,
    val score: Double?,
    val probeEligible: Boolean = false,
    val targetModel: String,
    val resolvedModel: String,
    val upstreamModel: String,
    val cooldownUntilEpochMs: Long? = null,
    val cooldownRemainingMs: Long? = null,
    val lastStatus: Int? = null,
    val lastError: String? = null,
    val lastAttemptAtEpochMs: Long? = null,
    val lastSuccessAtEpochMs: Long? = null,
    val reason: String? = null,
)

@Serializable
data class PoolExplainTierView(
    val priority: Int,
    val eligible: List<PoolExplainCandidateView>,
    val ineligible: List<PoolExplainCandidateView>,
)

@Serializable
data class PoolExplainResponse(
    val groupId: String,
    val requestedModel: String,
    val routedModel: String?,
    val routingPolicy: String,
    val selectedChannelId: String?,
    val result: String = if (selectedChannelId != null) "SELECTED" else "EXHAUSTED",
    val priorityTiers: List<PoolExplainTierView>,
    val requestTrace: PoolRequestTraceView? = null,
)

@Serializable
data class PoolRequestAttemptView(
    val channelId: String,
    val channelName: String,
    val modelKey: String,
    val resolvedModel: String,
    val upstreamModel: String,
    val priority: Int,
    val outcome: String,
    val breakerState: String? = null,
    val failureScope: String? = null,
    val failureKind: String? = null,
    val status: Int? = null,
    val reason: String? = null,
    val latencyMs: Long? = null,
    val selectedAtEpochMs: Long,
)

@Serializable
data class PoolRequestTraceView(
    val requestId: String,
    val groupId: String,
    val requestedModel: String,
    val routingPolicy: String,
    val attempts: List<PoolRequestAttemptView>,
    val selectedChannelId: String? = null,
    val failoverCount: Int = 0,
    val outcome: String = "PENDING",
    val completedAtEpochMs: Long? = null,
)

@Serializable
data class RuntimeWindowMetrics(
    val selectedRequests: Long = 0,
    val successRequests: Long = 0,
    val failedRequests: Long = 0,
    val errorRate: Double = 0.0,
    val p50LatencyMs: Long = 0,
    val p95LatencyMs: Long = 0,
    val p99LatencyMs: Long = 0,
)

@Serializable
data class PoolChannelRuntimeView(
    val channelId: String,
    val channelName: String,
    val routeModelKey: String,
    val effectiveStatus: String,
    val breakerState: String = "CLOSED",
    val failureScope: String? = null,
    val failureKind: String? = null,
    val priority: Int,
    val weight: Int,
    val currentConcurrency: Int,
    val maxConcurrency: Int,
    val saturation: Double = 0.0,
    val totalRequests: Long,
    val totalFailures: Long,
    val selectedRequests1m: Long = 0,
    val successRequests1m: Long = 0,
    val failedRequests1m: Long = 0,
    val errorRate1m: Double = 0.0,
    val p95Latency1m: Long = 0,
    val p99Latency1m: Long = 0,
    val metrics1m: RuntimeWindowMetrics = RuntimeWindowMetrics(),
    val metrics5m: RuntimeWindowMetrics = RuntimeWindowMetrics(),
    val metrics15m: RuntimeWindowMetrics = RuntimeWindowMetrics(),
    val trafficShare1m: Double = 0.0,
    val expectedShare: Double = 0.0,
    val shareDeviation: Double = 0.0,
    val cooldownCount: Long = 0,
    val cooldownUntilEpochMs: Long? = null,
    val cooldownRemainingMs: Long? = null,
    val lastStatus: Int? = null,
    val lastError: String? = null,
    val lastSelectedAt: Long? = null,
    val lastAttemptAtEpochMs: Long? = null,
    val lastSuccessAtEpochMs: Long? = null,
    val probeEligible: Boolean = false,
)

@Serializable
data class PoolView(
    val groupId: String,
    val aliasOrModel: String,
    val routingPolicy: String,
    val schedulerPolicy: String = "WEIGHTED_LEAST_LOAD",
    val priority: Int,
    val totalInflight: Int,
    val totalRequests1m: Long = 0,
    val errorRate1m: Double = 0.0,
    val p95Latency1m: Long = 0,
    val p99Latency1m: Long = 0,
    val metrics1m: RuntimeWindowMetrics = RuntimeWindowMetrics(),
    val metrics5m: RuntimeWindowMetrics = RuntimeWindowMetrics(),
    val metrics15m: RuntimeWindowMetrics = RuntimeWindowMetrics(),
    val healthyChannels: Int = 0,
    val cooldownChannels: Int = 0,
    val degradedChannels: Int = 0,
    val disabledChannels: Int = 0,
    val saturatedChannels: Int = 0,
    val halfOpenChannels: Int = 0,
    val failoverCount1m: Long = 0,
    val channels: List<PoolChannelRuntimeView>,
)
