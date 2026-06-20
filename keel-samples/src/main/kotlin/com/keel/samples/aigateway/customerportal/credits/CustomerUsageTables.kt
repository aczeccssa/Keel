package com.keel.samples.aigateway.customerportal.credits

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column

object CustomerUsageTable : AuditPluginTable("customer-portal", "usage_records") {
    val recordId: Column<String> = varchar("record_id", 32)
    val customerId: Column<String> = varchar("customer_id", 32).index()
    val keyId: Column<String> = varchar("key_id", 32).index()
    val model: Column<String> = varchar("model", 120)
    val groupId: Column<String> = varchar("group_id", 64)
    val providerId: Column<String> = varchar("provider_id", 64)
    val wireProtocol: Column<String> = varchar("wire_protocol", 40)
    val status: Column<Int> = integer("status").default(200)
    val inputTokens: Column<Long> = long("input_tokens").default(0)
    val outputTokens: Column<Long> = long("output_tokens").default(0)
    val cacheReadInputTokens: Column<Long> = long("cache_read_input_tokens").default(0)
    val cacheCreationInputTokens: Column<Long> = long("cache_creation_input_tokens").default(0)
    val cachedPromptTokens: Column<Long> = long("cached_prompt_tokens").default(0)
    val reasoningTokens: Column<Long> = long("reasoning_tokens").default(0)
    val creditCost: Column<Long> = long("credit_cost").default(0)
    val usdMicrosCost: Column<Long> = long("usd_micros_cost").default(0)
    val inputCostMicros: Column<Long> = long("input_cost_micros").default(0)
    val outputCostMicros: Column<Long> = long("output_cost_micros").default(0)
    val cacheWriteCostMicros: Column<Long> = long("cache_write_cost_micros").default(0)
    val cacheReadCostMicros: Column<Long> = long("cache_read_cost_micros").default(0)
    val cacheHitRate: Column<Double?> = double("cache_hit_rate").nullable()
    val requestId: Column<String> = varchar("request_id", 512).default("")

    override val primaryKey = PrimaryKey(recordId)
}
