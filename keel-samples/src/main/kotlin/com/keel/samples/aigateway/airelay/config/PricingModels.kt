package com.keel.samples.aigateway.airelay.config

import kotlinx.serialization.Serializable

@Serializable
data class PricingView(
    val pricingId: String,
    val model: String,
    val variantKey: String? = null,
    val label: String? = null,
    val billingUnitTokens: Long = 1_000_000,
    val tiers: List<PricingTierView> = emptyList(),
    val inputCostPerMTok: Double,
    val outputCostPerMTok: Double,
    val cacheCreationCostPerMTok: Double?,
    val cacheReadCostPerMTok: Double?,
    val cachedInputDiscount: Double?,
    val reasoningOutputCostPerMTok: Double?,
    val creditMultiplier: Double? = null,
    val notes: String? = null,
)

@Serializable
data class PricingTierView(
    val startTokensInclusive: Long = 0,
    val endTokensExclusive: Long? = null,
    val billingUnitTokens: Long = 1_000_000,
    val inputCostPerUnit: Double = 0.0,
    val outputCostPerUnit: Double = 0.0,
    val cacheCreationCostPerUnit: Double? = null,
    val cacheReadCostPerUnit: Double? = null,
    val reasoningOutputCostPerUnit: Double? = null,
)

@Serializable
data class UpsertPricingRequest(
    val model: String,
    val variantKey: String? = null,
    val label: String? = null,
    val billingUnitTokens: Long = 1_000_000,
    val tiers: List<UpsertPricingTierRequest> = emptyList(),
    val inputCostPerMTok: Double = 0.0,
    val outputCostPerMTok: Double = 0.0,
    val cacheCreationCostPerMTok: Double? = null,
    val cacheReadCostPerMTok: Double? = null,
    val cachedInputDiscount: Double? = null,
    val reasoningOutputCostPerMTok: Double? = null,
    val creditMultiplier: Double? = null,
    val notes: String? = null,
)

@Serializable
data class UpsertPricingTierRequest(
    val startTokensInclusive: Long = 0,
    val endTokensExclusive: Long? = null,
    val billingUnitTokens: Long = 1_000_000,
    val inputCostPerUnit: Double = 0.0,
    val outputCostPerUnit: Double = 0.0,
    val cacheCreationCostPerUnit: Double? = null,
    val cacheReadCostPerUnit: Double? = null,
    val reasoningOutputCostPerUnit: Double? = null,
)

@Serializable
data class PricingListResponse(val pricings: List<PricingView>)

@Serializable
data class DeletePricingResponse(val message: String)

@Serializable
data class NavCountsResponse(
    val customers: Long = 0,
    val redemptionCodes: Long = 0,
    val apiKeys: Long = 0,
)
