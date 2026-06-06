package com.keel.samples.observability

import com.keel.contract.ai.CostSummary
import com.keel.contract.ai.PoolChainSnapshot
import com.keel.contract.ai.RateLimitSnapshot
import com.keel.contract.ai.UsageSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class AiGatewaySnapshot(
    val costSummary: CostSummary = CostSummary(),
    val poolHealth: PoolChainSnapshot? = null,
    val rateLimitSnapshot: RateLimitSnapshot? = null,
    val usage: UsageSnapshot? = null
)
