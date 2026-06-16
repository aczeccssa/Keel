package com.keel.samples.aigateway.token

import com.keel.contract.ai.CostBreakdown
import com.keel.contract.ai.TokenUsage
import kotlinx.serialization.Serializable

@Serializable
data class CreateApiKeyRequest(
    val displayName: String,
    val groupId: String = "default",
    val allowedModels: List<String> = emptyList(),
    val allowedIps: List<String> = emptyList(),
    val maxBudgetUsd: Double = 10.0,
    val budgetDurationDays: Int = 30,
    val rpmLimit: Int? = null,
    val tpmLimit: Int? = null,
    val expiresInDays: Int? = null
)

@Serializable
data class UpdateApiKeyRequest(
    val displayName: String? = null,
    val groupId: String? = null,
    val allowedModels: List<String>? = null,
    val allowedIps: List<String>? = null,
    val maxBudgetUsd: Double? = null,
    val rpmLimit: Int? = null,
    val tpmLimit: Int? = null,
    val status: String? = null
)

@Serializable
data class ApiKeyCreatedResponse(
    val key: ApiKeyView,
    val rawKey: String
)

@Serializable
data class ApiKeyView(
    val keyId: String,
    val keyPrefix: String,
    val userId: String,
    val displayName: String,
    val groupId: String = "default",
    val maxBudgetUsd: Double,
    val currentSpendUsd: Double,
    val remainingBudgetUsd: Double,
    val rpmLimit: Int?,
    val tpmLimit: Int?,
    val allowedModels: List<String>,
    val allowedIps: List<String>,
    val status: String,
    val expiresAt: String?
)

@Serializable
data class ApiKeyListResponse(
    val keys: List<ApiKeyView>,
    val total: Int
)

@Serializable
data class TempBudgetRequest(
    val amountUsd: Double,
    val expiresInDays: Int = 7
)

@Serializable
data class UsageListResponse(
    val records: List<TokenUsageRecordView>,
    val total: Int
)

@Serializable
data class TokenUsageRecordView(
    val recordId: String,
    val keyId: String,
    val userId: String,
    val model: String,
    val provider: String,
    val status: Int,
    val transportStatus: Int = status,
    val outcome: String = if (status >= 400) "ERROR" else "SUCCESS",
    val errorCode: String? = null,
    val errorDetail: String? = null,
    val usageSource: String = "PROVIDER",
    val upstreamKeyId: String? = null,
    val poolLevelId: String? = null,
    val streamed: Boolean = false,
    val failoverCount: Int = 0,
    val usage: TokenUsage,
    val cost: CostBreakdown,
    val latencyMs: Long,
    val createdAt: String
)

object ApiKeyStatuses {
    const val ACTIVE = "active"
    const val REVOKED = "revoked"
    const val EXPIRED = "expired"
}
