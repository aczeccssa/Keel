package com.keel.samples.aigateway.airelay.config

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column

/**
 * Standalone per-model pricing. Distinct from `ChannelModelTable.inputCostPerMTok`
 * etc. so operators can configure rates for models that don't yet have a channel
 * attached (or override channel-embedded rates for known models).
 *
 * Looked up by `ConfigService.reload()` with the channel-embedded rate as fallback.
 */
object ModelPricingTable : AuditPluginTable("airelay", "model_pricing") {
    val pricingId: Column<String> = varchar("pricing_id", 32)
    val model: Column<String> = varchar("model", 120).uniqueIndex()
    val variantKey: Column<String?> = varchar("variant_key", 64).nullable()
    val label: Column<String?> = varchar("label", 120).nullable()
    val billingUnitTokens: Column<Long> = long("billing_unit_tokens").default(1_000_000)
    val inputCostPerMTok: Column<Double> = double("input_cost_per_mtok").default(0.0)
    val outputCostPerMTok: Column<Double> = double("output_cost_per_mtok").default(0.0)
    val cacheCreationCostPerMTok: Column<Double?> = double("cache_creation_cost_per_mtok").nullable()
    val cacheReadCostPerMTok: Column<Double?> = double("cache_read_cost_per_mtok").nullable()
    val cachedInputDiscount: Column<Double?> = double("cached_input_discount").nullable()
    val reasoningOutputCostPerMTok: Column<Double?> = double("reasoning_output_cost_per_mtok").nullable()
    val creditMultiplier: Column<Double?> = double("credit_multiplier").nullable()
    val notes: Column<String?> = varchar("notes", 500).nullable()

    override val primaryKey = PrimaryKey(pricingId)
}

object ModelPricingTierTable : AuditPluginTable("airelay", "model_pricing_tier") {
    val tierId: Column<String> = varchar("tier_id", 32)
    val pricingId: Column<String> = varchar("pricing_id", 32).index()
    val startTokensInclusive: Column<Long> = long("start_tokens_inclusive").default(0)
    val endTokensExclusive: Column<Long?> = long("end_tokens_exclusive").nullable()
    val billingUnitTokens: Column<Long> = long("billing_unit_tokens").default(1_000_000)
    val inputCostPerUnit: Column<Double> = double("input_cost_per_unit").default(0.0)
    val outputCostPerUnit: Column<Double> = double("output_cost_per_unit").default(0.0)
    val cacheCreationCostPerUnit: Column<Double?> = double("cache_creation_cost_per_unit").nullable()
    val cacheReadCostPerUnit: Column<Double?> = double("cache_read_cost_per_unit").nullable()
    val reasoningOutputCostPerUnit: Column<Double?> = double("reasoning_output_cost_per_unit").nullable()

    override val primaryKey = PrimaryKey(tierId)
}
