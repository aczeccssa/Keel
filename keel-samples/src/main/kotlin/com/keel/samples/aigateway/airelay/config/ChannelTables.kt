package com.keel.samples.aigateway.airelay.config

import com.keel.db.table.AuditPluginTable
import com.keel.samples.aigateway.airelay.AI_RELAY_MIN_TIMEOUT_MS
import org.jetbrains.exposed.sql.Column

object GroupTable : AuditPluginTable("airelay", "group") {
    val groupId: Column<String> = varchar("group_id", 64)
    val name: Column<String> = varchar("name", 120)
    val description: Column<String?> = varchar("description", 500).nullable()
    val enabled: Column<Boolean> = bool("enabled").default(true)
    val exposureMode: Column<String> = varchar("exposure_mode", 32).default("ALL_MODELS")

    override val primaryKey = PrimaryKey(groupId)
}

/**
 * Legacy one-to-one channel/group mapping. Kept for on-start migration into
 * [ChannelMembershipTable] so existing H2 files continue to boot.
 */
object ChannelGroupTable : AuditPluginTable("airelay", "channel_group") {
    val channelId: Column<String> = varchar("channel_id", 32)
    val groupId: Column<String> = varchar("group_id", 64).index()

    override val primaryKey = PrimaryKey(channelId)
}

object ChannelMembershipTable : AuditPluginTable("airelay", "channel_membership") {
    val channelId: Column<String> = varchar("channel_id", 32)
    val groupId: Column<String> = varchar("group_id", 64).index()
    val priority: Column<Int> = integer("priority").default(0)
    val weight: Column<Int> = integer("weight").default(100)
    val enabled: Column<Boolean> = bool("enabled").default(true)

    override val primaryKey = PrimaryKey(channelId, groupId)
}

object GroupAliasTable : AuditPluginTable("airelay", "group_alias") {
    val aliasId: Column<String> = varchar("alias_id", 32)
    val groupId: Column<String> = varchar("group_id", 64).index()
    val aliasName: Column<String> = varchar("alias_name", 120).index()
    /** Ordered alias targets stored as JSON. Supports legacy string arrays and {model, channelId?} objects. */
    val targetModelsJson: Column<String> = text("target_models_json")
    val enabled: Column<Boolean> = bool("enabled").default(true)
    val creditMultiplier: Column<Double?> = double("credit_multiplier").nullable()

    override val primaryKey = PrimaryKey(aliasId)
}

/**
 * Persisted upstream channel (a.k.a. provider). One row = one connection to one upstream:
 * its wire protocol, base URL, encrypted key, and routing weight/priority. Modeled on
 * one-api's `channel` table.
 */
object ChannelTable : AuditPluginTable("airelay", "channel") {
    val channelId: Column<String> = varchar("channel_id", 32)
    val name: Column<String> = varchar("name", 120)
    val protocol: Column<String> = varchar("protocol", 40)
    val baseUrl: Column<String> = varchar("base_url", 512)
    /** Upstream API key, encrypted at rest via CryptoSupport. */
    val apiKeyEncrypted: Column<String> = text("api_key_encrypted")
    /** Optional env-var name to source the key from instead of the stored value. */
    val apiKeyEnv: Column<String?> = varchar("api_key_env", 120).nullable()
    val enabled: Column<Boolean> = bool("enabled").default(true)
    /** Higher priority chains are tried first; ties broken by weight. */
    val priority: Column<Int> = integer("priority").default(0)
    val weight: Column<Int> = integer("weight").default(100)
    val maxConcurrency: Column<Int> = integer("max_concurrency").default(10)
    val timeoutMs: Column<Long> = long("timeout_ms").default(AI_RELAY_MIN_TIMEOUT_MS)
    /** HEALTHY / DEGRADED / DISABLED — last observed status from a Test or live traffic. */
    val status: Column<String> = varchar("status", 16).default("HEALTHY")
    val lastTestLatencyMs: Column<Long?> = long("last_test_latency_ms").nullable()
    val lastTestError: Column<String?> = varchar("last_test_error", 500).nullable()

    override val primaryKey = PrimaryKey(channelId)
}

/**
 * Maps a client-facing model name to a channel + the upstream model name to call, plus pricing.
 * Several models can point at the same channel; the same public name can be served by several
 * channels (failover) — exactly the one-api ability model.
 */
object ChannelModelTable : AuditPluginTable("airelay", "model") {
    val modelId: Column<String> = varchar("model_id", 32)
    val channelId: Column<String> = varchar("channel_id", 32).index()
    /** Name the client requests, e.g. "claude-sonnet-4-20250514". */
    val publicModelName: Column<String> = varchar("public_model_name", 120).index()
    /** Name sent upstream (model remapping). Blank = same as public. */
    val upstreamModelName: Column<String> = varchar("upstream_model_name", 120).default("")
    val inputCostPerMTok: Column<Double> = double("input_cost_per_mtok").default(0.0)
    val outputCostPerMTok: Column<Double> = double("output_cost_per_mtok").default(0.0)
    val cacheCreationCostPerMTok: Column<Double?> = double("cache_creation_cost_per_mtok").nullable()
    val cacheReadCostPerMTok: Column<Double?> = double("cache_read_cost_per_mtok").nullable()
    val cachedInputDiscount: Column<Double?> = double("cached_input_discount").nullable()
    val reasoningOutputCostPerMTok: Column<Double?> = double("reasoning_output_cost_per_mtok").nullable()
    val creditMultiplier: Column<Double?> = double("credit_multiplier").nullable()
    val enabled: Column<Boolean> = bool("enabled").default(true)

    override val primaryKey = PrimaryKey(modelId)
}

/**
 * Channel test history — stores the result of each channel test (admin "Test" button).
 * Used to render the "near 60 tests" sparkline.
 */
object ChannelTestHistoryTable : AuditPluginTable("airelay", "channel_test_history") {
    val testId: Column<String> = varchar("test_id", 32)
    val channelId: Column<String> = varchar("channel_id", 32).index()
    val testedAt: Column<Long> = long("tested_at")
    val success: Column<Boolean> = bool("success")
    val latencyMs: Column<Long?> = long("latency_ms").nullable()
    val errorMessage: Column<String?> = varchar("error_message", 500).nullable()

    override val primaryKey = PrimaryKey(testId)
}
