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
    val currentConcurrency: Int,
    val maxConcurrency: Int,
    val score: Double?,
    val targetModel: String,
    val resolvedModel: String,
    val upstreamModel: String,
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
    val priorityTiers: List<PoolExplainTierView>,
)

@Serializable
data class PoolChannelRuntimeView(
    val channelId: String,
    val channelName: String,
    val effectiveStatus: String,
    val priority: Int,
    val weight: Int,
    val currentConcurrency: Int,
    val maxConcurrency: Int,
    val totalRequests: Long,
    val totalFailures: Long,
    val cooldownUntilEpochMs: Long? = null,
    val lastError: String? = null,
    val lastSelectedAt: Long? = null,
)

@Serializable
data class PoolView(
    val groupId: String,
    val aliasOrModel: String,
    val routingPolicy: String,
    val schedulerPolicy: String = "WEIGHTED_LEAST_LOAD",
    val priority: Int,
    val totalInflight: Int,
    val channels: List<PoolChannelRuntimeView>,
)
