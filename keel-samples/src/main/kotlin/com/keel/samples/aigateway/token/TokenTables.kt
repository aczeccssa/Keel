package com.keel.samples.aigateway.token

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ApiKeysTable : AuditPluginTable("token", "api_keys") {
    val keyId: Column<String> = varchar("key_id", 32)
    val keyPrefix: Column<String> = varchar("key_prefix", 24)
    val keyHash: Column<String> = varchar("key_hash", 64).uniqueIndex()
    val userId: Column<String> = varchar("user_id", 32).index()
    val displayName: Column<String> = varchar("display_name", 120)
    val maxBudgetUsd: Column<Double> = double("max_budget_usd")
    val budgetDurationDays: Column<Int> = integer("budget_duration_days")
    val budgetResetAt = timestamp("budget_reset_at")
    val currentSpendUsd: Column<Double> = double("current_spend_usd").default(0.0)
    val tempBudgetIncrease: Column<Double?> = double("temp_budget_increase").nullable()
    val tempBudgetExpiry = timestamp("temp_budget_expiry").nullable()
    val rpmLimit: Column<Int?> = integer("rpm_limit").nullable()
    val tpmLimit: Column<Int?> = integer("tpm_limit").nullable()
    val allowedModelsJson: Column<String> = text("allowed_models_json").default("[]")
    val allowedIpsJson: Column<String> = text("allowed_ips_json").default("[]")
    val status: Column<String> = varchar("status", 16)
    val expiresAt = timestamp("expires_at").nullable()
    val lastUsedAt = timestamp("last_used_at").nullable()

    override val primaryKey = PrimaryKey(keyId)
}

object ApiKeyGroupTable : AuditPluginTable("token", "api_key_group") {
    val keyId: Column<String> = varchar("key_id", 32)
    val groupId: Column<String> = varchar("group_id", 64).default("default")

    override val primaryKey = PrimaryKey(keyId)
}

object UsageRecordsTable : AuditPluginTable("token", "usage_records") {
    val recordId: Column<String> = varchar("record_id", 32)
    val keyId: Column<String> = varchar("key_id", 32).index()
    val userId: Column<String> = varchar("user_id", 32).index()
    val userGroupId: Column<String> = varchar("user_group_id", 32)
    val clientProtocol: Column<String> = varchar("client_protocol", 40)
    val upstreamProtocol: Column<String> = varchar("upstream_protocol", 40)
    val model: Column<String> = varchar("model", 120).index()
    val provider: Column<String> = varchar("provider", 64)
    val poolLevelId: Column<String?> = varchar("pool_level_id", 64).nullable()
    val upstreamKeyId: Column<String?> = varchar("upstream_key_id", 64).nullable()
    val promptTokens: Column<Int> = integer("prompt_tokens")
    val completionTokens: Column<Int> = integer("completion_tokens")
    val cacheCreationInputTokens: Column<Int> = integer("cache_creation_input_tokens").default(0)
    val cacheReadInputTokens: Column<Int> = integer("cache_read_input_tokens").default(0)
    val cachedPromptTokens: Column<Int> = integer("cached_prompt_tokens").default(0)
    val reasoningTokens: Column<Int> = integer("reasoning_tokens").default(0)
    val inputCostUsd: Column<Double> = double("input_cost_usd")
    val outputCostUsd: Column<Double> = double("output_cost_usd")
    val cacheWriteCostUsd: Column<Double> = double("cache_write_cost_usd").default(0.0)
    val cacheReadCostUsd: Column<Double> = double("cache_read_cost_usd").default(0.0)
    val totalCostUsd: Column<Double> = double("total_cost_usd")
    val cacheHitRate: Column<Double?> = double("cache_hit_rate").nullable()
    val latencyMs: Column<Long> = long("latency_ms")
    val status: Column<Int> = integer("status")
    val transportStatus: Column<Int> = integer("transport_status").default(200)
    val outcome: Column<String> = varchar("outcome", 16).default("SUCCESS")
    val usageSource: Column<String> = varchar("usage_source", 16).default("PROVIDER")
    val errorCode: Column<String?> = varchar("error_code", 128).nullable()
    val streamed: Column<Boolean> = bool("streamed")
    val failoverCount: Column<Int> = integer("failover_count")

    override val primaryKey = PrimaryKey(recordId)
}
