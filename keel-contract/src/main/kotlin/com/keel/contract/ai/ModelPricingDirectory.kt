package com.keel.contract.ai

import kotlinx.serialization.Serializable

/**
 * Read-only directory of per-model pricing.
 *
 * The airelay plugin publishes this contract and the customer-portal plugin
 * consumes it to render the "Rates" page for end customers. This indirection
 * preserves plugin isolation: the customer portal never imports the airelay
 * classpath, only this interface.
 */
interface ModelPricingDirectory {
    fun listActive(): List<ModelPricingSummary>
}

@Serializable
data class ModelPricingSummary(
    val model: String,
    val variantKey: String? = null,
    val label: String? = null,
    val billingUnitTokens: Long = 1_000_000,
    val tiers: List<ModelPricingTierSummary> = emptyList(),
    val inputCostPerMTok: Double,
    val outputCostPerMTok: Double,
    val cacheCreationCostPerMTok: Double?,
    val cacheReadCostPerMTok: Double?,
    val cachedInputDiscount: Double?,
    val reasoningOutputCostPerMTok: Double?,
    val creditMultiplier: Double? = null,
)

@Serializable
data class ModelPricingTierSummary(
    val startTokensInclusive: Long = 0,
    val endTokensExclusive: Long? = null,
    val billingUnitTokens: Long = 1_000_000,
    val inputCostPerUnit: Double = 0.0,
    val outputCostPerUnit: Double = 0.0,
    val cacheCreationCostPerUnit: Double? = null,
    val cacheReadCostPerUnit: Double? = null,
    val reasoningOutputCostPerUnit: Double? = null,
)
