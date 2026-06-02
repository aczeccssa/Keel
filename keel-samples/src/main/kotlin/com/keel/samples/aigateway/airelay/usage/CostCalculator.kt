package com.keel.samples.aigateway.airelay.usage

import com.keel.contract.ai.CostBreakdown
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UserDirectory
import com.keel.samples.aigateway.airelay.ModelPricing

class ModelPricingRegistry(
    pricings: List<ModelPricing>
) {
    private val byModel = pricings.associateBy { it.model }

    fun get(model: String): ModelPricing = byModel[model]
        ?: byModel.entries.firstOrNull { model.startsWith(it.key) }?.value
        ?: ModelPricing(model, inputCostPerMTok = 1.0, outputCostPerMTok = 2.0)
}

class CostCalculator(
    private val registry: ModelPricingRegistry,
    private val userDirectory: UserDirectory
) {
    suspend fun calculate(model: String, usage: TokenUsage, userGroupId: String): CostBreakdown {
        val pricing = registry.get(model)
        val multiplier = userDirectory.findGroup(userGroupId)?.costMultiplier ?: 1.0
        val uncachedInput = (usage.promptTokens - usage.cachedPromptTokens).coerceAtLeast(0)
        val cachedDiscount = pricing.cachedInputDiscount ?: 0.0
        val inputCost = (uncachedInput * pricing.inputCostPerMTok / 1_000_000.0) +
            (usage.cachedPromptTokens * pricing.inputCostPerMTok * (1.0 - cachedDiscount) / 1_000_000.0)
        val normalOutput = (usage.completionTokens - usage.reasoningTokens).coerceAtLeast(0)
        val outputCost = (normalOutput * pricing.outputCostPerMTok / 1_000_000.0) +
            (usage.reasoningTokens * (pricing.reasoningOutputCostPerMTok ?: pricing.outputCostPerMTok) / 1_000_000.0)
        val cacheWrite = usage.cacheCreationInputTokens * (pricing.cacheCreationCostPerMTok ?: pricing.inputCostPerMTok) / 1_000_000.0
        val cacheRead = usage.cacheReadInputTokens * (pricing.cacheReadCostPerMTok ?: pricing.inputCostPerMTok) / 1_000_000.0
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
}

class TokenEstimator {
    fun estimate(text: String): Int = (text.length / 4).coerceAtLeast(1)
}
