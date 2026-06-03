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
        creditCost: Long,
        usdMicrosCost: Long,
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
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadInputTokens: Long,
    val cacheCreationInputTokens: Long,
    val requestId: String,
)
