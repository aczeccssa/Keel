package com.keel.samples.aigateway.airelay.usage

import com.keel.contract.ai.CostBreakdown
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UserDirectory
import com.keel.samples.aigateway.airelay.ModelPricing
import com.keel.samples.aigateway.airelay.ModelPricingTier

class ModelPricingRegistry(
    pricings: List<ModelPricing>
) {
    private val byModel = pricings.groupBy { it.model }

    fun get(model: String, variantKey: String? = null): ModelPricing {
        val exact = byModel[model]
        return when {
            variantKey != null -> exact?.firstOrNull { it.variantKey == variantKey }
            else -> null
        } ?: exact?.firstOrNull { it.variantKey == null }
            ?: exact?.firstOrNull()
            ?: byModel.entries.firstOrNull { model.startsWith(it.key) }?.value?.let { candidates ->
                candidates.firstOrNull { it.variantKey == variantKey } ?: candidates.firstOrNull { it.variantKey == null } ?: candidates.firstOrNull()
            }
            ?: ModelPricing(model, inputCostPerMTok = 1.0, outputCostPerMTok = 2.0)
    }
}

class CostCalculator(
    private val registry: ModelPricingRegistry,
    private val userDirectory: UserDirectory
) {
    fun creditMultiplier(model: String, variantKey: String? = null): Double? = registry.get(model, variantKey).creditMultiplier

    suspend fun calculate(model: String, usage: TokenUsage, userGroupId: String, variantKey: String? = null): CostBreakdown {
        val pricing = registry.get(model, variantKey)
        val multiplier = userDirectory.findGroup(userGroupId)?.costMultiplier ?: 1.0
        val inputCost = costForInput(pricing, usage)
        val outputCost = costForOutput(pricing, usage)
        val cacheWrite = costForCacheWrite(pricing, usage)
        val cacheRead = costForCacheRead(pricing, usage)
        val total = (inputCost + outputCost + cacheWrite + cacheRead) * multiplier
        val cacheHitRate = when {
            usage.cacheReadInputTokens > 0 -> usage.cacheReadInputTokens.toDouble() /
                (usage.promptTokens + usage.cacheCreationInputTokens + usage.cacheReadInputTokens).coerceAtLeast(1)
            usage.cachedPromptTokens > 0 -> usage.cachedPromptTokens.toDouble() / usage.promptTokens.coerceAtLeast(1)
            else -> null
        }
        return CostBreakdown(
            inputCostUsd = inputCost * multiplier,
            outputCostUsd = outputCost * multiplier,
            cacheWriteCostUsd = cacheWrite * multiplier,
            cacheReadCostUsd = cacheRead * multiplier,
            totalCostUsd = total,
            cacheHitRate = cacheHitRate
        )
    }

    private fun costForInput(pricing: ModelPricing, usage: TokenUsage): Double {
        if (pricing.tiers.isNotEmpty()) {
            val tier = tierFor(pricing.tiers, usage.promptTokens.toLong())
            return usage.promptTokens * tier.inputCostPerUnit / tier.billingUnitTokens.coerceAtLeast(1).toDouble()
        }
        val uncachedInput = (usage.promptTokens - usage.cachedPromptTokens).coerceAtLeast(0)
        val cachedDiscount = pricing.cachedInputDiscount ?: 0.0
        val unit = pricing.billingUnitTokens.coerceAtLeast(1).toDouble()
        return (uncachedInput * pricing.inputCostPerMTok / unit) +
            (usage.cachedPromptTokens * pricing.inputCostPerMTok * (1.0 - cachedDiscount) / unit)
    }

    private fun costForOutput(pricing: ModelPricing, usage: TokenUsage): Double {
        if (pricing.tiers.isNotEmpty()) {
            val tier = tierFor(pricing.tiers, usage.completionTokens.toLong())
            val reasoningRate = tier.reasoningOutputCostPerUnit ?: tier.outputCostPerUnit
            val normalOutput = (usage.completionTokens - usage.reasoningTokens).coerceAtLeast(0)
            val unit = tier.billingUnitTokens.coerceAtLeast(1).toDouble()
            return (normalOutput * tier.outputCostPerUnit / unit) +
                (usage.reasoningTokens * reasoningRate / unit)
        }
        val normalOutput = (usage.completionTokens - usage.reasoningTokens).coerceAtLeast(0)
        val unit = pricing.billingUnitTokens.coerceAtLeast(1).toDouble()
        return (normalOutput * pricing.outputCostPerMTok / unit) +
            (usage.reasoningTokens * (pricing.reasoningOutputCostPerMTok ?: pricing.outputCostPerMTok) / unit)
    }

    private fun costForCacheWrite(pricing: ModelPricing, usage: TokenUsage): Double {
        if (pricing.tiers.isNotEmpty()) {
            val tier = tierFor(pricing.tiers, usage.cacheCreationInputTokens.toLong())
            return usage.cacheCreationInputTokens * (tier.cacheCreationCostPerUnit ?: tier.inputCostPerUnit) / tier.billingUnitTokens.coerceAtLeast(1).toDouble()
        }
        return usage.cacheCreationInputTokens * (pricing.cacheCreationCostPerMTok ?: pricing.inputCostPerMTok) / pricing.billingUnitTokens.coerceAtLeast(1).toDouble()
    }

    private fun costForCacheRead(pricing: ModelPricing, usage: TokenUsage): Double {
        if (pricing.tiers.isNotEmpty()) {
            val tier = tierFor(pricing.tiers, usage.cacheReadInputTokens.toLong())
            return usage.cacheReadInputTokens * (tier.cacheReadCostPerUnit ?: tier.inputCostPerUnit) / tier.billingUnitTokens.coerceAtLeast(1).toDouble()
        }
        return usage.cacheReadInputTokens * (pricing.cacheReadCostPerMTok ?: pricing.inputCostPerMTok) / pricing.billingUnitTokens.coerceAtLeast(1).toDouble()
    }

    private fun tierFor(tiers: List<ModelPricingTier>, tokenCount: Long): ModelPricingTier {
        return tiers.firstOrNull { tier ->
            tokenCount >= tier.startTokensInclusive &&
                (tier.endTokensExclusive == null || tokenCount < tier.endTokensExclusive)
        } ?: tiers.first()
    }
}

class TokenEstimator {
    fun estimate(text: String): Int = (text.length / 4).coerceAtLeast(1)
}

class CreditChargeCalculator {
    fun calculate(
        usage: TokenUsage,
        modelCreditMultiplier: Double?,
        aliasCreditMultiplier: Double?,
    ): Long {
        val billableTokens = usage.promptTokens +
            usage.completionTokens +
            usage.cacheReadInputTokens +
            usage.cacheCreationInputTokens
        if (billableTokens <= 0) return 0L
        val baseCredits = when {
            billableTokens <= 4_000 -> 1L
            billableTokens <= 32_000 -> 2L
            billableTokens <= 128_000 -> 4L
            billableTokens <= 512_000 -> 8L
            else -> 16L
        }
        val multiplier = aliasCreditMultiplier ?: modelCreditMultiplier ?: 1.0
        return kotlin.math.ceil(baseCredits * multiplier).toLong().coerceAtLeast(1L)
    }
}
