package com.keel.contract.customer

import kotlinx.serialization.Serializable

/**
 * Verifies a raw customer API key (e.g. `sk-keel-cust-...`).
 *
 * Implementations: [com.keel.samples.aigateway.customerportal.keys.CustomerKeyRepository].
 * Consumed by the gateway relay to identify the calling customer and pick a routing group.
 */
interface CustomerApiKeyVerifier {
    suspend fun verifyCustomerKey(rawKey: String): VerifiedCustomerKey?
}

@Serializable
data class VerifiedCustomerKey(
    val customerId: String,
    val keyId: String,
    val routingGroupId: String,
    val monthlyBudgetCredits: Long?,
    val status: String,
)

/**
 * Append-only credit ledger.
 *
 * Implementations: [com.keel.samples.aigateway.customerportal.credits.CreditLedgerRepository].
 * The gateway relay snapshots the balance before forwarding a request and charges after the
 * upstream call returns. Charging is allowed to drive the balance negative — `snapshotBalance`
 * rejects the next request.
 */
interface CreditLedger {
    suspend fun snapshotBalance(customerId: String): Long

    suspend fun chargeForUsage(
        customerId: String,
        keyId: String,
        usageCreditCost: Long,
        usageUsdMicrosCost: Long,
        usageRow: CustomerUsageRow,
    ): ChargeResult
}

sealed class ChargeResult {
    data class Ok(val newBalanceCredits: Long) : ChargeResult()
    data class Failed(val message: String) : ChargeResult()
}

@Serializable
data class CustomerUsageRow(
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
    val inputCostMicros: Long = 0,
    val outputCostMicros: Long = 0,
    val cacheWriteCostMicros: Long = 0,
    val cacheReadCostMicros: Long = 0,
    val cacheHitRate: Double? = null,
    val requestId: String,
)

/**
 * Read-only directory of end-customers used by the B-end admin UI.
 */
interface CustomerDirectory {
    suspend fun listAll(): List<CustomerSummary>
    suspend fun count(): Long
}

@Serializable
data class CustomerSummary(
    val customerId: String,
    val email: String,
    val displayName: String,
    val status: String,
    val balanceCredits: Long,
    val totalKeys: Int,
    val createdAt: String,
)
