package com.keel.samples.aigateway.riskcontrol

import kotlinx.serialization.Serializable

@Serializable
data class RateLimitRuleView(
    val ruleId: String,
    val name: String,
    val dimension: String,
    val pathPattern: String,
    val methods: Set<String>,
    val capacity: Int,
    val refillRatePerSec: Double,
    val priority: Int,
    val enabled: Boolean
)

@Serializable
data class RateLimitRuleListResponse(
    val rules: List<RateLimitRuleView>,
    val total: Int
)

@Serializable
data class UpsertRateLimitRuleRequest(
    val ruleId: String? = null,
    val name: String,
    val dimension: String,
    val pathPattern: String = "/v1/*",
    val methods: Set<String> = setOf("POST"),
    val capacity: Int,
    val refillRatePerSec: Double,
    val priority: Int = 0,
    val enabled: Boolean = true
)

@Serializable
data class ResetRateLimitResponse(
    val message: String,
    val removedBuckets: Int = 0
)

enum class RiskRateLimitDimension {
    IP,
    USER,
    API_KEY,
    MODEL,
    GLOBAL;

    companion object {
        fun parse(value: String): RiskRateLimitDimension = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw com.keel.kernel.plugin.PluginApiException(400, "Unsupported rate limit dimension")
    }
}

data class RateLimitRule(
    val ruleId: String,
    val name: String,
    val dimension: RiskRateLimitDimension,
    val pathPattern: String,
    val methods: Set<String>,
    val capacity: Int,
    val refillRatePerSec: Double,
    val priority: Int = 0,
    val enabled: Boolean = true
) {
    fun toView(): RateLimitRuleView = RateLimitRuleView(
        ruleId = ruleId,
        name = name,
        dimension = dimension.name,
        pathPattern = pathPattern,
        methods = methods,
        capacity = capacity,
        refillRatePerSec = refillRatePerSec,
        priority = priority,
        enabled = enabled
    )
}

data class BucketKey(
    val ruleId: String,
    val dimension: RiskRateLimitDimension,
    val value: String
) {
    fun cacheKey(): String = "$ruleId:${dimension.name}:$value"
}
