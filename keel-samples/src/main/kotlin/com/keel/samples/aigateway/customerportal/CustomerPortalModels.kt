package com.keel.samples.aigateway.customerportal

import com.keel.contract.ai.ModelPricingSummary
import kotlinx.serialization.Serializable

// ---- keys ----

@Serializable
data class CreateCustomerKeyRequest(
    val name: String,
    val routingGroupId: String = "default",
    val monthlyBudgetCredits: Long? = null,
)

@Serializable
data class UpdateCustomerKeyRequest(
    val name: String? = null,
    val routingGroupId: String? = null,
    val monthlyBudgetCredits: Long? = null,
)

@Serializable
data class CustomerKeyView(
    val keyId: String,
    val customerId: String,
    val name: String,
    val prefix: String,
    val routingGroupId: String,
    val monthlyBudgetCredits: Long?,
    val status: String,
    val lastUsedAt: String?,
    val revokedAt: String?,
    val createdAt: String,
)

@Serializable
data class CustomerKeyCreatedResponse(
    val key: CustomerKeyView,
    val rawKey: String,
)

@Serializable
data class CustomerKeyListResponse(
    val keys: List<CustomerKeyView>,
    val total: Int,
)

// ---- credits ----

@Serializable
data class CreditBalanceResponse(
    val balanceCredits: Long,
)

@Serializable
data class CreditLedgerEntry(
    val entryId: String,
    val deltaCredits: Long,
    val reason: String,
    val refId: String?,
    val balanceAfterCredits: Long,
    val usdMicrosAtTime: Long,
    val createdAt: String,
)

@Serializable
data class CreditLedgerResponse(
    val entries: List<CreditLedgerEntry>,
    val total: Int,
    val nextCursor: String? = null,
)

@Serializable
data class RedeemCodeRequest(
    val code: String,
)

@Serializable
data class RedeemCodeResponse(
    val code: String,
    val amountCredits: Long,
    val newBalanceCredits: Long,
)

// ---- usage ----

@Serializable
data class CustomerUsageView(
    val recordId: String,
    val keyId: String,
    val model: String,
    val groupId: String,
    val providerId: String,
    val wireProtocol: String,
    val status: Int = 200,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadInputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cachedPromptTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val creditCost: Long,
    val usdMicrosCost: Long,
    val inputCostMicros: Long = 0,
    val outputCostMicros: Long = 0,
    val cacheWriteCostMicros: Long = 0,
    val cacheReadCostMicros: Long = 0,
    val cacheHitRate: Double? = null,
    val requestId: String,
    val createdAt: String,
)

@Serializable
data class CustomerUsageListResponse(
    val records: List<CustomerUsageView>,
    val total: Int,
    val nextCursor: String? = null,
)

// ---- model pricing (customer-facing) ----

@Serializable
data class ModelPricingListResponse(
    val summaries: List<ModelPricingSummary>,
)

// ---- redemption codes (admin) ----

@Serializable
data class CreateRedemptionCodeRequest(
    val faceValueCredits: Long,
    val code: String? = null,
    val expiresInDays: Int? = null,
)

@Serializable
data class RedemptionCodeView(
    val code: String,
    val faceValueCredits: Long,
    val createdByAdminId: String,
    val redeemedByCustomerId: String?,
    val redeemedAt: String?,
    val expiresAt: String?,
    val status: String,
    val createdAt: String,
)

@Serializable
data class RedemptionCodeListResponse(
    val codes: List<RedemptionCodeView>,
    val total: Int,
)

// ---- customer list (B-end) ----

@Serializable
data class CustomerListView(
    val customerId: String,
    val email: String,
    val displayName: String,
    val status: String,
    val balanceCredits: Long,
    val totalKeys: Int,
    val createdAt: String,
)

@Serializable
data class CustomerListResponse(
    val customers: List<CustomerListView>,
    val total: Int,
)

@Serializable
data class UpdateCustomerRequest(
    val displayName: String? = null,
    val status: String? = null,
)

@Serializable
data class AdjustCustomerCreditsRequest(
    val deltaCredits: Long,
    val reason: String = "admin_adjustment",
    val usdMicrosAtTime: Long = 0,
)

@Serializable
data class CustomerAdminDetailView(
    val customerId: String,
    val email: String,
    val displayName: String,
    val status: String,
    val balanceCredits: Long,
    val totalKeys: Int,
    val createdAt: String,
    val keys: List<CustomerKeyView>,
)

@Serializable
data class DeleteResponse(val message: String)
