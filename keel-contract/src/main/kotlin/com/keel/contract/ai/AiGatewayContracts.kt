package com.keel.contract.ai

import kotlinx.serialization.Serializable

interface UserDirectory {
    suspend fun findById(userId: String): UserSummary?
    suspend fun findGroup(groupId: String): UserGroupSummary?
}

interface JwtPrincipalVerifier {
    suspend fun verifyAuthorizationHeader(authHeader: String?): AiPrincipal?
}

@Serializable
data class AiPrincipal(
    val userId: String,
    val email: String,
    val role: String,
    val groupId: String
)

@Serializable
data class UserSummary(
    val userId: String,
    val email: String,
    val displayName: String,
    val role: String,
    val groupId: String,
    val status: String
)

@Serializable
data class UserGroupSummary(
    val groupId: String,
    val name: String,
    val costMultiplier: Double,
    val defaultRpm: Int?,
    val defaultTpm: Int?,
    val defaultBudgetUsd: Double
)

interface ApiKeyVerifier {
    suspend fun verify(rawKey: String, clientIp: String?): VerifiedApiKey
}

@Serializable
data class VerifiedApiKey(
    val keyId: String,
    val userId: String,
    val userGroupId: String,
    val routingGroupId: String = "default",
    val allowedModels: List<String>,
    val rpmLimit: Int?,
    val tpmLimit: Int?,
    val remainingBudgetUsd: Double
)

class InvalidApiKeyException(
    val reason: String
) : RuntimeException(reason)

class QuotaExceededException(
    val keyId: String
) : RuntimeException("Quota exceeded for API key $keyId")

@Serializable
enum class UsageSource { PROVIDER, ESTIMATED, NONE }

@Serializable
enum class RequestOutcome { SUCCESS, ERROR }

interface UsageRecorder {
    suspend fun record(record: UsageRecordInput)
    suspend fun snapshot(): UsageSnapshot
}

@Serializable
data class UsageRecordInput(
    val keyId: String,
    val userId: String,
    val userGroupId: String,
    val clientProtocol: String,
    val upstreamProtocol: String,
    val model: String,
    val provider: String,
    val poolLevelId: String?,
    val upstreamKeyId: String?,
    val routingGroupId: String? = null,
    val usage: TokenUsage,
    val cost: CostBreakdown,
    val latencyMs: Long,
    val status: Int,
    val errorCode: String?,
    val streamed: Boolean,
    val failoverCount: Int,
    val transportStatus: Int = status,
    val outcome: RequestOutcome = if (status >= 400) RequestOutcome.ERROR else RequestOutcome.SUCCESS,
    val usageSource: UsageSource = if (usage.totalTokens > 0 || usage.cacheCreationInputTokens > 0 || usage.cacheReadInputTokens > 0) UsageSource.PROVIDER else UsageSource.NONE,
    val errorDetail: String? = null,
)

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val cachedPromptTokens: Int = 0,
    val reasoningTokens: Int = 0
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

@Serializable
data class CostBreakdown(
    val inputCostUsd: Double = 0.0,
    val outputCostUsd: Double = 0.0,
    val cacheWriteCostUsd: Double = 0.0,
    val cacheReadCostUsd: Double = 0.0,
    val totalCostUsd: Double = 0.0,
    val cacheHitRate: Double? = null
)

@Serializable
data class UsageSnapshot(
    val totalRequests: Long,
    val totalCostUsd: Double,
    val totalTokens: Long,
    val recentRequests: List<UsageRecordView>,
    val topModels: List<ModelUsageSummary>,
    val topUsers: List<UserUsageSummary>
)

@Serializable
data class CostSummary(
    val last1hUsd: Double = 0.0,
    val last24hUsd: Double = 0.0,
    val last7dUsd: Double = 0.0,
    val totalRequests: Long = 0,
    val avgLatencyMs: Long = 0,
    val errorRate: Double = 0.0
)

@Serializable
data class UsageRecordView(
    val recordId: String,
    val requestId: String? = null,
    val userId: String,
    val keyId: String,
    val groupId: String? = null,
    val routingGroupId: String? = null,
    val routingGroupName: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val model: String,
    val provider: String,
    val status: Int,
    val transportStatus: Int = status,
    val outcome: String = if (status >= 400) "ERROR" else "SUCCESS",
    val errorCode: String? = null,
    val errorDetail: String? = null,
    val upstreamKeyId: String? = null,
    val poolLevelId: String? = null,
    val streamed: Boolean = false,
    val failoverCount: Int = 0,
    val usage: TokenUsage,
    val cost: CostBreakdown,
    val totalTokens: Int,
    val totalCostUsd: Double,
    val cacheHitRate: Double? = null,
    val latencyMs: Long,
    val createdAt: String
)

@Serializable
data class ModelUsageSummary(
    val model: String,
    val requests: Long,
    val totalTokens: Long,
    val totalCostUsd: Double
)

@Serializable
data class UserUsageSummary(
    val userId: String,
    val requests: Long,
    val totalTokens: Long,
    val totalCostUsd: Double
)

interface RateLimitGate {
    suspend fun tryAcquire(context: RateLimitContext): RateLimitDecision
}

@Serializable
data class RateLimitContext(
    val ip: String?,
    val userId: String?,
    val keyId: String?,
    val model: String?,
    val path: String,
    val method: String
)

@Serializable
sealed interface RateLimitDecision {
    @Serializable
    data class Allowed(
        val limit: Int,
        val remaining: Int,
        val resetAtEpochMs: Long
    ) : RateLimitDecision

    @Serializable
    data class Rejected(
        val reason: String,
        val limit: Int,
        val retryAfterSeconds: Long,
        val ruleId: String
    ) : RateLimitDecision
}

interface PoolChainSnapshotProvider {
    fun snapshot(): PoolChainSnapshot
}

@Serializable
data class PoolChainSnapshot(
    val chains: List<PoolChainHealth>
)

@Serializable
data class PoolChainHealth(
    val chainId: String,
    val modelAliases: List<String>,
    val levels: List<PoolLevelHealth>
)

@Serializable
data class PoolLevelHealth(
    val levelId: String,
    val levelIndex: Int,
    val providerId: String,
    val protocol: String,
    val healthyKeys: Int,
    val cooldownKeys: Int,
    val degradedKeys: Int,
    val disabledKeys: Int,
    val keys: List<PoolKeyHealth>
)

@Serializable
data class PoolKeyHealth(
    val keyId: String,
    val status: String,
    val totalRequests: Long,
    val totalFailures: Long,
    val currentConcurrency: Int,
    val maxConcurrency: Int,
    val cooldownUntilEpochMs: Long?,
    val lastError: String?
)

interface RateLimitSnapshotProvider {
    fun snapshot(): RateLimitSnapshot
}

@Serializable
data class RateLimitSnapshot(
    val ruleCount: Int,
    val bucketCount: Int,
    val totalAllowed: Long,
    val totalRejected: Long,
    val topBuckets: List<RateLimitBucketView>,
    val recentRejections: List<RateLimitRejectionView> = emptyList()
)

@Serializable
data class RateLimitBucketView(
    val ruleId: String,
    val dimension: String,
    val value: String,
    val capacity: Int,
    val remainingTokens: Int,
    val totalAllowed: Long,
    val totalRejected: Long,
    val resetAtEpochMs: Long
)

@Serializable
data class RateLimitRejectionView(
    val ruleId: String,
    val dimension: String,
    val value: String,
    val reason: String,
    val retryAfterSeconds: Long,
    val createdAtEpochMs: Long
)
