package com.keel.samples.aigateway.airelay.config

import com.keel.db.database.KeelDatabase
import com.keel.samples.aigateway.airelay.AliasRouteConfig
import com.keel.samples.aigateway.airelay.AliasRoutingPolicy
import com.keel.samples.aigateway.airelay.AliasTargetConfig
import com.keel.samples.aigateway.airelay.AI_RELAY_MIN_TIMEOUT_MS
import com.keel.samples.aigateway.airelay.GroupExposureMode
import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.aigateway.airelay.batches.BatchListResponse
import com.keel.samples.aigateway.airelay.batches.BatchView
import com.keel.samples.aigateway.airelay.batches.CreateBatchRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

enum class GroupDeleteResult { DELETED, DEFAULT_PROTECTED, HAS_CHANNELS, NOT_FOUND }

@Serializable
data class GroupView(
    val groupId: String,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val exposureMode: String = GroupExposureMode.ALL_MODELS.name,
    val channelCount: Int = 0,
    val modelCount: Int = 0,
    val healthyChannelCount: Int = 0,
    val aliasRoutes: List<GroupAliasView> = emptyList(),
)

@Serializable
data class UpsertGroupRequest(
    val groupId: String? = null,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val exposureMode: String = GroupExposureMode.ALL_MODELS.name,
    val aliasRoutes: List<UpsertGroupAliasRequest> = emptyList(),
)

@Serializable
data class GroupAliasView(
    val aliasId: String,
    val aliasName: String,
    val targetModels: List<String>,
    val enabled: Boolean = true,
    val creditMultiplier: Double? = null,
    val targets: List<GroupAliasTargetView> = targetModels.map { GroupAliasTargetView(it) },
    val routingPolicy: String = AliasRoutingPolicy.ORDERED_FAILOVER.name,
)

@Serializable
data class GroupAliasTargetView(
    val model: String,
    val channelId: String? = null,
)

@Serializable
data class UpsertGroupAliasRequest(
    val aliasName: String,
    val targetModels: List<String> = emptyList(),
    val enabled: Boolean = true,
    val creditMultiplier: Double? = null,
    val targets: List<GroupAliasTargetView> = emptyList(),
    val routingPolicy: String = AliasRoutingPolicy.ORDERED_FAILOVER.name,
) {
    fun orderedTargets(): List<GroupAliasTargetView> = targets.ifEmpty {
        targetModels.map { GroupAliasTargetView(it) }
    }.filter { it.model.isNotBlank() }
}

@Serializable
data class ChannelMembershipView(
    val groupId: String,
    val priority: Int,
    val weight: Int,
    val enabled: Boolean,
)

@Serializable
data class GroupMembershipView(
    val channelId: String,
    val name: String,
    val protocol: String,
    val enabled: Boolean,
    val priority: Int,
    val weight: Int,
    val modelCount: Int,
)

@Serializable
data class GroupMembershipAttachRequest(
    val channelId: String,
    val priority: Int = 0,
    val weight: Int = 100,
    val enabled: Boolean = true,
)

@Serializable
data class GroupMembershipUpdateRequest(
    val priority: Int = 0,
    val weight: Int = 100,
    val enabled: Boolean = true,
)

@Serializable
data class ChannelView(
    val channelId: String,
    /** Legacy convenience field; use [memberships] for the true relationship. */
    val groupId: String,
    val name: String,
    val protocol: String,
    val baseUrl: String,
    val apiKeyMasked: String,
    val apiKeyEnv: String?,
    val enabled: Boolean,
    val priority: Int,
    val weight: Int,
    val maxConcurrency: Int,
    val timeoutMs: Long,
    val status: String,
    val lastTestLatencyMs: Long?,
    val lastTestError: String?,
    val models: List<ChannelModelView> = emptyList(),
    val memberships: List<ChannelMembershipView> = emptyList(),
)

@Serializable
data class ChannelModelView(
    val modelId: String,
    val channelId: String,
    val publicModelName: String,
    val upstreamModelName: String,
    val inputCostPerMTok: Double,
    val outputCostPerMTok: Double,
    val cacheCreationCostPerMTok: Double? = null,
    val cacheReadCostPerMTok: Double? = null,
    val cachedInputDiscount: Double? = null,
    val reasoningOutputCostPerMTok: Double? = null,
    val creditMultiplier: Double? = null,
    val enabled: Boolean
)

@Serializable
data class UpsertChannelRequest(
    val name: String,
    val protocol: String,
    val baseUrl: String,
    /** New plaintext key. Empty string = leave unchanged on update. */
    val apiKey: String = "",
    val apiKeyEnv: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val weight: Int = 100,
    val maxConcurrency: Int = 10,
    val timeoutMs: Long = AI_RELAY_MIN_TIMEOUT_MS,
    val groupId: String = ChannelRepository.DEFAULT_GROUP_ID,
    val memberships: List<UpsertChannelMembershipRequest> = emptyList(),
    val models: List<UpsertModelRequest> = emptyList()
)

@Serializable
data class UpsertChannelMembershipRequest(
    val groupId: String,
    val priority: Int = 0,
    val weight: Int = 100,
    val enabled: Boolean = true,
)

@Serializable
data class UpsertModelRequest(
    val publicModelName: String,
    val upstreamModelName: String = "",
    val inputCostPerMTok: Double = 0.0,
    val outputCostPerMTok: Double = 0.0,
    val cacheCreationCostPerMTok: Double? = null,
    val cacheReadCostPerMTok: Double? = null,
    val cachedInputDiscount: Double? = null,
    val reasoningOutputCostPerMTok: Double? = null,
    val creditMultiplier: Double? = null,
    val enabled: Boolean = true
)

@Serializable
data class TestRecord(
    val timestamp: Long,
    val ok: Boolean,
    val latencyMs: Long?
)

/**
 * CRUD over [ChannelTable] / [ChannelModelTable]. Keys are stored encrypted via [SecretCipher];
 * the plaintext key never leaves this layer except when [decryptedKey] is called by the config
 * bridge that builds the live pool configuration.
 */
class ChannelRepository(
    private val database: KeelDatabase,
    private val cipher: SecretCipher
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun initializeSchema() {
        database.createTables(
            GroupTable,
            ChannelGroupTable,
            ChannelMembershipTable,
            GroupAliasTable,
            ChannelTable,
            ChannelModelTable,
            ChannelTestHistoryTable,
            ModelPricingTable,
            ModelPricingTierTable
        )
        migrateGroupExposureMode()
        migratePricingSchema()
        migrateCreditMultiplierSchema()
        migrateAliasRoutingPolicySchema()
        ensureDefaultGroup()
        backfillChannelGroups()
        backfillChannelMemberships()
    }

    fun listGroups(): List<GroupView> = database.transaction {
        val channelRows = ChannelTable.selectAll().toList()
        val memberships = ChannelMembershipTable.selectAll().toList()
        val modelsByChannel = ChannelModelTable.selectAll().map { it.toModelView() }.groupBy { it.channelId }
        val aliasByGroup = GroupAliasTable.selectAll()
            .groupBy { it[GroupAliasTable.groupId] }
            .mapValues { (_, rows) -> rows.map { it.toAliasView() } }
        GroupTable.selectAll()
            .orderBy(GroupTable.groupId to SortOrder.ASC)
            .map { row ->
                val groupId = row[GroupTable.groupId]
                val channels = memberships.filter { it[ChannelMembershipTable.groupId] == groupId }
                    .mapNotNull { membership -> channelRows.find { it[ChannelTable.channelId] == membership[ChannelMembershipTable.channelId] } }
                val modelCount = channels.flatMap { modelsByChannel[it[ChannelTable.channelId]] ?: emptyList() }
                    .filter { it.enabled }
                    .map { it.publicModelName }
                    .distinct()
                    .size
                GroupView(
                    groupId = groupId,
                    name = row[GroupTable.name],
                    description = row[GroupTable.description],
                    enabled = row[GroupTable.enabled],
                    exposureMode = GroupExposureMode.from(runCatching { row[GroupTable.exposureMode] }.getOrNull()).name,
                    channelCount = channels.size,
                    modelCount = modelCount,
                    healthyChannelCount = channels.count { it[ChannelTable.enabled] && it[ChannelTable.status] == "HEALTHY" },
                    aliasRoutes = aliasByGroup[groupId].orEmpty(),
                )
            }
    }

    fun createGroup(request: UpsertGroupRequest): GroupView {
        val groupId = normalizeGroupId(request.groupId ?: request.name)
        database.transaction {
            val exists = GroupTable.selectAll().where { GroupTable.groupId eq groupId }.count() > 0
            if (exists) throw PluginApiException(409, "Group already exists")
            GroupTable.insert {
                it[GroupTable.groupId] = groupId
                it[name] = request.name.trim().ifBlank { throw PluginApiException(400, "name is required") }
                it[description] = request.description?.trim()?.ifBlank { null }
                it[enabled] = request.enabled
                it[exposureMode] = GroupExposureMode.from(request.exposureMode).name
            }
            replaceGroupAliases(groupId, request.aliasRoutes)
        }
        return listGroups().first { it.groupId == groupId }
    }

    fun updateGroup(groupId: String, request: UpsertGroupRequest): GroupView? {
        val updated = database.transaction {
            val count = GroupTable.update({ GroupTable.groupId eq groupId }) {
                it[name] = request.name.trim().ifBlank { throw PluginApiException(400, "name is required") }
                it[description] = request.description?.trim()?.ifBlank { null }
                it[enabled] = request.enabled
                it[exposureMode] = GroupExposureMode.from(request.exposureMode).name
            }
            replaceGroupAliases(groupId, request.aliasRoutes)
            count
        }
        if (updated == 0) return null
        return listGroups().firstOrNull { it.groupId == groupId }
    }

    fun deleteGroup(groupId: String): Boolean = database.transaction {
        if (groupId == DEFAULT_GROUP_ID) return@transaction false
        val hasChannels = ChannelMembershipTable.selectAll().where { ChannelMembershipTable.groupId eq groupId }.count() > 0
        if (hasChannels) return@transaction false
        GroupAliasTable.deleteWhere { GroupAliasTable.groupId eq groupId }
        GroupTable.deleteWhere { GroupTable.groupId eq groupId } > 0
    }

    fun deleteGroupDetailed(groupId: String): GroupDeleteResult = database.transaction {
        if (groupId == DEFAULT_GROUP_ID) return@transaction GroupDeleteResult.DEFAULT_PROTECTED
        val exists = GroupTable.selectAll().where { GroupTable.groupId eq groupId }.count() > 0
        if (!exists) return@transaction GroupDeleteResult.NOT_FOUND
        val hasChannels = ChannelMembershipTable.selectAll().where { ChannelMembershipTable.groupId eq groupId }.count() > 0
        if (hasChannels) return@transaction GroupDeleteResult.HAS_CHANNELS
        GroupAliasTable.deleteWhere { GroupAliasTable.groupId eq groupId }
        if (GroupTable.deleteWhere { GroupTable.groupId eq groupId } > 0) GroupDeleteResult.DELETED else GroupDeleteResult.NOT_FOUND
    }

    fun listChannels(): List<ChannelView> = database.transaction {
        val models = ChannelModelTable.selectAll().map { it.toModelView() }.groupBy { it.channelId }
        val memberships = ChannelMembershipTable.selectAll()
            .groupBy { it[ChannelMembershipTable.channelId] }
        ChannelTable.selectAll()
            .orderBy(ChannelTable.priority to SortOrder.DESC, ChannelTable.name to SortOrder.ASC)
            .map { row ->
                val channelId = row[ChannelTable.channelId]
                val membershipViews = memberships[channelId].orEmpty()
                    .map {
                        ChannelMembershipView(
                            groupId = it[ChannelMembershipTable.groupId],
                            priority = it[ChannelMembershipTable.priority],
                            weight = it[ChannelMembershipTable.weight],
                            enabled = it[ChannelMembershipTable.enabled]
                        )
                    }
                    .sortedByDescending { it.priority }
                row.toChannelView(
                    membershipViews.firstOrNull()?.groupId ?: DEFAULT_GROUP_ID,
                    membershipViews.firstOrNull()?.priority ?: row[ChannelTable.priority],
                    membershipViews.firstOrNull()?.weight ?: row[ChannelTable.weight],
                    models[channelId] ?: emptyList(),
                    membershipViews
                )
            }
    }

    fun getChannel(channelId: String): ChannelView? = database.transaction {
        val row = ChannelTable.selectAll().where { ChannelTable.channelId eq channelId }.singleOrNull()
            ?: return@transaction null
        val models = ChannelModelTable.selectAll().where { ChannelModelTable.channelId eq channelId }
            .map { it.toModelView() }
        val memberships = ChannelMembershipTable.selectAll()
            .where { ChannelMembershipTable.channelId eq channelId }
            .map {
                ChannelMembershipView(
                    groupId = it[ChannelMembershipTable.groupId],
                    priority = it[ChannelMembershipTable.priority],
                    weight = it[ChannelMembershipTable.weight],
                    enabled = it[ChannelMembershipTable.enabled]
                )
            }
            .sortedByDescending { it.priority }
        row.toChannelView(
            memberships.firstOrNull()?.groupId ?: DEFAULT_GROUP_ID,
            memberships.firstOrNull()?.priority ?: row[ChannelTable.priority],
            memberships.firstOrNull()?.weight ?: row[ChannelTable.weight],
            models,
            memberships
        )
    }

    /** Returns the decrypted upstream key for a channel (used by the live-config bridge only). */
    fun decryptedKey(channelId: String): String? = database.transaction {
        ChannelTable.selectAll().where { ChannelTable.channelId eq channelId }.singleOrNull()
            ?.let { cipher.decrypt(it[ChannelTable.apiKeyEncrypted]) }
    }

    fun createChannel(request: UpsertChannelRequest): ChannelView {
        val id = "ch-" + UUID.randomUUID().toString().replace("-", "").take(20)
        database.transaction {
            ChannelTable.insert {
                it[channelId] = id
                it[name] = request.name
                it[protocol] = request.protocol
                it[baseUrl] = request.baseUrl
                it[apiKeyEncrypted] = cipher.encrypt(request.apiKey)
                it[apiKeyEnv] = request.apiKeyEnv
                it[enabled] = request.enabled
                it[priority] = request.priority
                it[weight] = request.weight
                it[maxConcurrency] = request.maxConcurrency
                it[timeoutMs] = request.timeoutMs
                it[status] = "HEALTHY"
            }
            replaceMemberships(
                id,
                request.memberships.ifEmpty {
                    listOf(
                        UpsertChannelMembershipRequest(
                            groupId = request.groupId.ifBlank { DEFAULT_GROUP_ID },
                            priority = request.priority,
                            weight = request.weight,
                            enabled = true,
                        )
                    )
                }
            )
            replaceModels(id, request.models)
        }
        return getChannel(id)!!
    }

    fun updateChannel(channelId: String, request: UpsertChannelRequest): ChannelView? {
        val exists = database.transaction {
            ChannelTable.selectAll().where { ChannelTable.channelId eq channelId }.count() > 0
        }
        if (!exists) return null
        database.transaction {
            ChannelTable.update({ ChannelTable.channelId eq channelId }) {
                it[name] = request.name
                it[protocol] = request.protocol
                it[baseUrl] = request.baseUrl
                if (request.apiKey.isNotEmpty()) it[apiKeyEncrypted] = cipher.encrypt(request.apiKey)
                it[apiKeyEnv] = request.apiKeyEnv
                it[enabled] = request.enabled
                it[priority] = request.priority
                it[weight] = request.weight
                it[maxConcurrency] = request.maxConcurrency
                it[timeoutMs] = request.timeoutMs
            }
            replaceMemberships(
                channelId,
                request.memberships.ifEmpty {
                    listOf(
                        UpsertChannelMembershipRequest(
                            groupId = request.groupId.ifBlank { DEFAULT_GROUP_ID },
                            priority = request.priority,
                            weight = request.weight,
                            enabled = true,
                        )
                    )
                }
            )
            replaceModels(channelId, request.models)
        }
        return getChannel(channelId)
    }

    fun deleteChannel(channelId: String): Boolean = database.transaction {
        ChannelGroupTable.deleteWhere { ChannelGroupTable.channelId eq channelId }
        ChannelMembershipTable.deleteWhere { ChannelMembershipTable.channelId eq channelId }
        ChannelModelTable.deleteWhere { ChannelModelTable.channelId eq channelId }
        ChannelTable.deleteWhere { ChannelTable.channelId eq channelId } > 0
    }

    fun setEnabled(channelId: String, enabled: Boolean): Boolean = database.transaction {
        ChannelTable.update({ ChannelTable.channelId eq channelId }) { it[ChannelTable.enabled] = enabled } > 0
    }

    fun attachChannelToGroup(channelId: String, groupId: String, priority: Int, weight: Int, enabled: Boolean): ChannelView? {
        val exists = database.transaction {
            ChannelTable.selectAll().where { ChannelTable.channelId eq channelId }.count() > 0
        }
        if (!exists) return null
        database.transaction {
            val targetGroup = normalizeGroupId(groupId)
            val groupExists = GroupTable.selectAll().where { GroupTable.groupId eq targetGroup }.count() > 0
            if (!groupExists) throw PluginApiException(400, "Unknown group $targetGroup")
            val updated = ChannelMembershipTable.update({
                (ChannelMembershipTable.channelId eq channelId) and (ChannelMembershipTable.groupId eq targetGroup)
            }) {
                it[ChannelMembershipTable.priority] = priority
                it[ChannelMembershipTable.weight] = weight
                it[ChannelMembershipTable.enabled] = enabled
            }
            if (updated == 0) {
                ChannelMembershipTable.insert {
                    it[ChannelMembershipTable.channelId] = channelId
                    it[ChannelMembershipTable.groupId] = targetGroup
                    it[ChannelMembershipTable.priority] = priority
                    it[ChannelMembershipTable.weight] = weight
                    it[ChannelMembershipTable.enabled] = enabled
                }
            }
        }
        return getChannel(channelId)
    }

    fun detachChannelFromGroup(channelId: String, groupId: String): ChannelView? {
        val exists = database.transaction {
            ChannelTable.selectAll().where { ChannelTable.channelId eq channelId }.count() > 0
        }
        if (!exists) return null
        database.transaction {
            ChannelMembershipTable.deleteWhere {
                (ChannelMembershipTable.channelId eq channelId) and (ChannelMembershipTable.groupId eq normalizeGroupId(groupId))
            }
            val remaining = ChannelMembershipTable.selectAll()
                .where { ChannelMembershipTable.channelId eq channelId }
                .orderBy(ChannelMembershipTable.priority to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
            if (remaining == null) {
                ChannelGroupTable.deleteWhere { ChannelGroupTable.channelId eq channelId }
            } else {
                val legacyGroup = remaining[ChannelMembershipTable.groupId]
                val updated = ChannelGroupTable.update({ ChannelGroupTable.channelId eq channelId }) {
                    it[ChannelGroupTable.groupId] = legacyGroup
                }
                if (updated == 0) {
                    ChannelGroupTable.insert {
                        it[ChannelGroupTable.channelId] = channelId
                        it[ChannelGroupTable.groupId] = legacyGroup
                    }
                }
            }
        }
        return getChannel(channelId)
    }

    fun replaceGroupAliasesForAdmin(groupId: String, aliases: List<UpsertGroupAliasRequest>): GroupView? = database.transaction {
        val exists = GroupTable.selectAll().where { GroupTable.groupId eq groupId }.count() > 0
        if (!exists) return@transaction null
        replaceGroupAliases(groupId, aliases)
        listGroups().firstOrNull { it.groupId == groupId }
    }

    fun recordTestResult(channelId: String, latencyMs: Long?, error: String?) = database.transaction {
        ChannelTable.update({ ChannelTable.channelId eq channelId }) {
            it[status] = if (error == null) "HEALTHY" else "DEGRADED"
            it[lastTestLatencyMs] = latencyMs
            it[lastTestError] = error?.take(500)
        }
        // Record to test history table
        val testId = "tst-${randomSuffix()}"
        ChannelTestHistoryTable.insert {
            it[ChannelTestHistoryTable.testId] = testId
            it[ChannelTestHistoryTable.channelId] = channelId
            it[testedAt] = System.currentTimeMillis()
            it[success] = error == null
            it[ChannelTestHistoryTable.latencyMs] = latencyMs
            it[errorMessage] = error?.take(500)
        }
        // Keep only last 100 tests per channel - delete older ones
        val allTests = ChannelTestHistoryTable.selectAll()
            .where { ChannelTestHistoryTable.channelId eq channelId }
            .orderBy(ChannelTestHistoryTable.testedAt to SortOrder.DESC)
            .toList()
        if (allTests.size > 100) {
            val toDelete = allTests.drop(100).map { it[ChannelTestHistoryTable.testId] }
            toDelete.forEach { id ->
                ChannelTestHistoryTable.deleteWhere { ChannelTestHistoryTable.testId eq id }
            }
        }
    }

    fun getRecentTests(channelId: String, limit: Int = 60): List<TestRecord> = database.transaction {
        ChannelTestHistoryTable.selectAll()
            .where { ChannelTestHistoryTable.channelId eq channelId }
            .orderBy(ChannelTestHistoryTable.testedAt to SortOrder.DESC)
            .limit(limit)
            .map {
                TestRecord(
                    timestamp = it[ChannelTestHistoryTable.testedAt],
                    ok = it[ChannelTestHistoryTable.success],
                    latencyMs = it[ChannelTestHistoryTable.latencyMs]
                )
            }
    }

    fun count(): Long = database.transaction { ChannelTable.selectAll().count() }

    private fun ensureDefaultGroup() = database.transaction {
        val exists = GroupTable.selectAll().where { GroupTable.groupId eq DEFAULT_GROUP_ID }.count() > 0
        if (!exists) {
            GroupTable.insert {
                it[groupId] = DEFAULT_GROUP_ID
                it[name] = "Default"
                it[description] = "Default routing pool"
                it[enabled] = true
                it[exposureMode] = GroupExposureMode.ALL_MODELS.name
            }
        }
    }

    private fun backfillChannelGroups() = database.transaction {
        val mapped = ChannelGroupTable.selectAll().map { it[ChannelGroupTable.channelId] }.toSet()
        ChannelTable.selectAll().forEach { row ->
            val channelId = row[ChannelTable.channelId]
            if (channelId !in mapped) {
                ChannelGroupTable.insert {
                    it[ChannelGroupTable.channelId] = channelId
                    it[groupId] = DEFAULT_GROUP_ID
                }
            }
        }
    }

    private fun migrateGroupExposureMode() = database.transaction {
        val ddl = "ALTER TABLE airelay_group ADD COLUMN IF NOT EXISTS exposure_mode VARCHAR(32) NOT NULL DEFAULT 'ALL_MODELS'"
        runCatching { exec(ddl) }
        GroupTable.selectAll().forEach { row ->
            val current = row[GroupTable.exposureMode]
            val normalized = GroupExposureMode.from(current).name
            if (current != normalized) {
                GroupTable.update({ GroupTable.groupId eq row[GroupTable.groupId] }) {
                    it[exposureMode] = normalized
                }
            }
        }
    }

    private fun migratePricingSchema() = database.transaction {
        listOf(
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS variant_key VARCHAR(64)",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS label VARCHAR(120)",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS billing_unit_tokens BIGINT NOT NULL DEFAULT 1000000",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS input_cost_per_mtok DOUBLE PRECISION NOT NULL DEFAULT 0.0",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS output_cost_per_mtok DOUBLE PRECISION NOT NULL DEFAULT 0.0",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS cache_creation_cost_per_mtok DOUBLE PRECISION",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS cache_read_cost_per_mtok DOUBLE PRECISION",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS cached_input_discount DOUBLE PRECISION",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS reasoning_output_cost_per_mtok DOUBLE PRECISION",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS credit_multiplier DOUBLE PRECISION",
            "ALTER TABLE airelay_model_pricing ADD COLUMN IF NOT EXISTS notes VARCHAR(500)",
            "ALTER TABLE airelay_model_pricing_tier ADD COLUMN IF NOT EXISTS start_tokens_inclusive BIGINT NOT NULL DEFAULT 0",
            "ALTER TABLE airelay_model_pricing_tier ADD COLUMN IF NOT EXISTS end_tokens_exclusive BIGINT",
            "ALTER TABLE airelay_model_pricing_tier ADD COLUMN IF NOT EXISTS billing_unit_tokens BIGINT NOT NULL DEFAULT 1000000",
            "ALTER TABLE airelay_model_pricing_tier ADD COLUMN IF NOT EXISTS input_cost_per_unit DOUBLE PRECISION NOT NULL DEFAULT 0.0",
            "ALTER TABLE airelay_model_pricing_tier ADD COLUMN IF NOT EXISTS output_cost_per_unit DOUBLE PRECISION NOT NULL DEFAULT 0.0",
            "ALTER TABLE airelay_model_pricing_tier ADD COLUMN IF NOT EXISTS cache_creation_cost_per_unit DOUBLE PRECISION",
            "ALTER TABLE airelay_model_pricing_tier ADD COLUMN IF NOT EXISTS cache_read_cost_per_unit DOUBLE PRECISION",
            "ALTER TABLE airelay_model_pricing_tier ADD COLUMN IF NOT EXISTS reasoning_output_cost_per_unit DOUBLE PRECISION",
            "CREATE INDEX IF NOT EXISTS airelay_model_pricing_tier_pricing_id ON airelay_model_pricing_tier (pricing_id)"
        ).forEach { ddl ->
            runCatching { exec(ddl) }
        }
    }

    private fun migrateCreditMultiplierSchema() = database.transaction {
        listOf(
            "ALTER TABLE airelay_group_alias ADD COLUMN IF NOT EXISTS credit_multiplier DOUBLE PRECISION",
            "ALTER TABLE airelay_model ADD COLUMN IF NOT EXISTS credit_multiplier DOUBLE PRECISION",
        ).forEach { ddl ->
            runCatching { exec(ddl) }
        }
    }

    private fun migrateAliasRoutingPolicySchema() = database.transaction {
        runCatching {
            exec("ALTER TABLE airelay_group_alias ADD COLUMN IF NOT EXISTS routing_policy VARCHAR(32) NOT NULL DEFAULT 'ORDERED_FAILOVER'")
        }
    }

    private fun backfillChannelMemberships() = database.transaction {
        val existing = ChannelMembershipTable.selectAll().map { it[ChannelMembershipTable.channelId] to it[ChannelMembershipTable.groupId] }.toSet()
        val legacy = ChannelGroupTable.selectAll().toList()
        val priorities = ChannelTable.selectAll().associateBy { it[ChannelTable.channelId] }
        legacy.forEach { row ->
            val key = row[ChannelGroupTable.channelId] to row[ChannelGroupTable.groupId]
            if (key !in existing) {
                val channel = priorities[row[ChannelGroupTable.channelId]]
                ChannelMembershipTable.insert {
                    it[channelId] = row[ChannelGroupTable.channelId]
                    it[groupId] = row[ChannelGroupTable.groupId]
                    it[priority] = channel?.get(ChannelTable.priority) ?: 0
                    it[weight] = channel?.get(ChannelTable.weight) ?: 100
                    it[enabled] = channel?.get(ChannelTable.enabled) ?: true
                }
            }
        }
    }

    private fun replaceMemberships(channelId: String, memberships: List<UpsertChannelMembershipRequest>) {
        ChannelMembershipTable.deleteWhere { ChannelMembershipTable.channelId eq channelId }
        memberships.forEach { membership ->
            val targetGroup = normalizeGroupId(membership.groupId)
            val groupExists = GroupTable.selectAll().where { GroupTable.groupId eq targetGroup }.count() > 0
            if (!groupExists) throw PluginApiException(400, "Unknown group $targetGroup")
            ChannelMembershipTable.insert {
                it[ChannelMembershipTable.channelId] = channelId
                it[groupId] = targetGroup
                it[priority] = membership.priority
                it[weight] = membership.weight
                it[enabled] = membership.enabled
            }
        }
        // Keep legacy one-to-one table pointed at the highest-priority membership for compatibility.
        val primary = memberships.maxByOrNull { it.priority }
        if (primary != null) {
            val updated = ChannelGroupTable.update({ ChannelGroupTable.channelId eq channelId }) {
                it[ChannelGroupTable.groupId] = normalizeGroupId(primary.groupId)
            }
            if (updated == 0) {
                ChannelGroupTable.insert {
                    it[ChannelGroupTable.channelId] = channelId
                    it[groupId] = normalizeGroupId(primary.groupId)
                }
            }
        }
    }

    private fun replaceGroupAliases(groupId: String, aliases: List<UpsertGroupAliasRequest>) {
        GroupAliasTable.deleteWhere { GroupAliasTable.groupId eq groupId }
        aliases.forEach { alias ->
            if (alias.aliasName.isBlank()) return@forEach
            val targets = alias.orderedTargets()
            if (targets.isEmpty()) return@forEach
            GroupAliasTable.insert {
                it[aliasId] = "gal-" + UUID.randomUUID().toString().replace("-", "").take(20)
                it[GroupAliasTable.groupId] = groupId
                it[aliasName] = alias.aliasName.trim()
                it[targetModelsJson] = json.encodeToString(targets)
                it[enabled] = alias.enabled
                it[creditMultiplier] = alias.creditMultiplier
                it[routingPolicy] = AliasRoutingPolicy.from(alias.routingPolicy).name
            }
        }
    }

    fun listGroupMemberships(groupId: String): List<GroupMembershipView> = database.transaction {
        ensureGroupExists(groupId)
        val channels = ChannelTable.selectAll().associateBy { it[ChannelTable.channelId] }
        ChannelMembershipTable.selectAll()
            .where { ChannelMembershipTable.groupId eq normalizeGroupId(groupId) }
            .orderBy(ChannelMembershipTable.priority to SortOrder.DESC, ChannelMembershipTable.weight to SortOrder.DESC)
            .mapNotNull { membership ->
                val channelId = membership[ChannelMembershipTable.channelId]
                channels[channelId]?.let { channel ->
                    GroupMembershipView(
                        channelId = channelId,
                        name = channel[ChannelTable.name],
                        protocol = channel[ChannelTable.protocol],
                        enabled = channel[ChannelTable.enabled] && membership[ChannelMembershipTable.enabled],
                        priority = membership[ChannelMembershipTable.priority],
                        weight = membership[ChannelMembershipTable.weight],
                        modelCount = ChannelModelTable.selectAll().where {
                            (ChannelModelTable.channelId eq channelId) and (ChannelModelTable.enabled eq true)
                        }.count().toInt()
                    )
                }
            }
    }

    fun attachChannelToGroupFromGroupSide(groupId: String, request: GroupMembershipAttachRequest): GroupMembershipView? = database.transaction {
        val channelId = request.channelId.trim()
        ensureChannelExists(channelId)
        ensureGroupExists(groupId)
        val updated = ChannelMembershipTable.update({
            (ChannelMembershipTable.channelId eq channelId) and (ChannelMembershipTable.groupId eq normalizeGroupId(groupId))
        }) {
            it[ChannelMembershipTable.priority] = request.priority
            it[ChannelMembershipTable.weight] = request.weight
            it[ChannelMembershipTable.enabled] = request.enabled
        }
        if (updated == 0) {
            ChannelMembershipTable.insert {
                it[ChannelMembershipTable.channelId] = channelId
                it[ChannelMembershipTable.groupId] = normalizeGroupId(groupId)
                it[ChannelMembershipTable.priority] = request.priority
                it[ChannelMembershipTable.weight] = request.weight
                it[ChannelMembershipTable.enabled] = request.enabled
            }
        }
        syncChannelGroupLegacy(channelId)
        listGroupMemberships(groupId).firstOrNull { it.channelId == channelId }
    }

    fun updateGroupMembershipFromGroupSide(groupId: String, channelId: String, request: GroupMembershipUpdateRequest): GroupMembershipView? = database.transaction {
        ensureChannelExists(channelId)
        ensureGroupExists(groupId)
        val updated = ChannelMembershipTable.update({
            (ChannelMembershipTable.channelId eq channelId) and (ChannelMembershipTable.groupId eq normalizeGroupId(groupId))
        }) {
            it[ChannelMembershipTable.priority] = request.priority
            it[ChannelMembershipTable.weight] = request.weight
            it[ChannelMembershipTable.enabled] = request.enabled
        }
        if (updated == 0) return@transaction null
        syncChannelGroupLegacy(channelId)
        listGroupMemberships(groupId).firstOrNull { it.channelId == channelId }
    }

    fun detachChannelFromGroupFromGroupSide(groupId: String, channelId: String): Boolean = database.transaction {
        ensureGroupExists(groupId)
        val deleted = ChannelMembershipTable.deleteWhere {
            (ChannelMembershipTable.channelId eq channelId) and (ChannelMembershipTable.groupId eq normalizeGroupId(groupId))
        }
        if (deleted > 0) {
            syncChannelGroupLegacy(channelId)
        }
        deleted > 0
    }

    fun listGroupAliases(groupId: String): List<GroupAliasView> = database.transaction {
        ensureGroupExists(groupId)
        GroupAliasTable.selectAll().where { GroupAliasTable.groupId eq normalizeGroupId(groupId) }
            .orderBy(GroupAliasTable.aliasName to SortOrder.ASC)
            .map { it.toAliasView() }
    }

    fun replaceAliasesForGroupFromAdmin(groupId: String, aliases: List<UpsertGroupAliasRequest>): List<GroupAliasView> = database.transaction {
        ensureGroupExists(groupId)
        replaceGroupAliases(groupId, aliases)
        listGroupAliases(groupId)
    }

    private fun ensureChannelExists(channelId: String) {
        val exists = ChannelTable.selectAll().where { ChannelTable.channelId eq channelId }.count() > 0
        if (!exists) throw PluginApiException(404, "Channel $channelId not found")
    }

    private fun ensureGroupExists(groupId: String) {
        val exists = GroupTable.selectAll().where { GroupTable.groupId eq normalizeGroupId(groupId) }.count() > 0
        if (!exists) throw PluginApiException(404, "Group $groupId not found")
    }

    private fun syncChannelGroupLegacy(channelId: String) {
        val primary = ChannelMembershipTable.selectAll()
            .where { ChannelMembershipTable.channelId eq channelId }
            .orderBy(ChannelMembershipTable.priority to SortOrder.DESC, ChannelMembershipTable.weight to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
        if (primary == null) {
            ChannelGroupTable.deleteWhere { ChannelGroupTable.channelId eq channelId }
        } else {
            val groupId = primary[ChannelMembershipTable.groupId]
            val updated = ChannelGroupTable.update({ ChannelGroupTable.channelId eq channelId }) {
                it[ChannelGroupTable.groupId] = groupId
            }
            if (updated == 0) {
                ChannelGroupTable.insert {
                    it[ChannelGroupTable.channelId] = channelId
                    it[ChannelGroupTable.groupId] = groupId
                }
            }
        }
    }

    private fun replaceModels(channelId: String, models: List<UpsertModelRequest>) {
        ChannelModelTable.deleteWhere { ChannelModelTable.channelId eq channelId }
        models.forEach { m ->
            ChannelModelTable.insert {
                it[modelId] = "md-" + UUID.randomUUID().toString().replace("-", "").take(20)
                it[ChannelModelTable.channelId] = channelId
                it[publicModelName] = m.publicModelName
                it[upstreamModelName] = m.upstreamModelName
                it[inputCostPerMTok] = m.inputCostPerMTok
                it[outputCostPerMTok] = m.outputCostPerMTok
                it[cacheCreationCostPerMTok] = m.cacheCreationCostPerMTok
                it[cacheReadCostPerMTok] = m.cacheReadCostPerMTok
                it[cachedInputDiscount] = m.cachedInputDiscount
                it[reasoningOutputCostPerMTok] = m.reasoningOutputCostPerMTok
                it[creditMultiplier] = m.creditMultiplier
                it[enabled] = m.enabled
            }
        }
    }

    private fun ResultRow.toChannelView(
        groupIdValue: String,
        priorityValue: Int,
        weightValue: Int,
        models: List<ChannelModelView>,
        memberships: List<ChannelMembershipView>
    ): ChannelView {
        val enc = this[ChannelTable.apiKeyEncrypted]
        return ChannelView(
            channelId = this[ChannelTable.channelId],
            groupId = groupIdValue,
            name = this[ChannelTable.name],
            protocol = this[ChannelTable.protocol],
            baseUrl = this[ChannelTable.baseUrl],
            apiKeyMasked = maskKey(runCatching { cipher.decrypt(enc) }.getOrDefault("")),
            apiKeyEnv = this[ChannelTable.apiKeyEnv],
            enabled = this[ChannelTable.enabled],
            priority = priorityValue,
            weight = weightValue,
            maxConcurrency = this[ChannelTable.maxConcurrency],
            timeoutMs = this[ChannelTable.timeoutMs],
            status = this[ChannelTable.status],
            lastTestLatencyMs = this[ChannelTable.lastTestLatencyMs],
            lastTestError = this[ChannelTable.lastTestError],
            models = models,
            memberships = memberships,
        )
    }

    private fun ResultRow.toModelView() = ChannelModelView(
        modelId = this[ChannelModelTable.modelId],
        channelId = this[ChannelModelTable.channelId],
        publicModelName = this[ChannelModelTable.publicModelName],
        upstreamModelName = this[ChannelModelTable.upstreamModelName],
        inputCostPerMTok = this[ChannelModelTable.inputCostPerMTok],
        outputCostPerMTok = this[ChannelModelTable.outputCostPerMTok],
        cacheCreationCostPerMTok = this[ChannelModelTable.cacheCreationCostPerMTok],
        cacheReadCostPerMTok = this[ChannelModelTable.cacheReadCostPerMTok],
        cachedInputDiscount = this[ChannelModelTable.cachedInputDiscount],
        reasoningOutputCostPerMTok = this[ChannelModelTable.reasoningOutputCostPerMTok],
        creditMultiplier = this[ChannelModelTable.creditMultiplier],
        enabled = this[ChannelModelTable.enabled]
    )

    private fun ResultRow.toAliasView(): GroupAliasView {
        val targets = decodeAliasTargets(this[GroupAliasTable.targetModelsJson])
        return GroupAliasView(
            aliasId = this[GroupAliasTable.aliasId],
            aliasName = this[GroupAliasTable.aliasName],
            targetModels = targets.map { it.model },
            enabled = this[GroupAliasTable.enabled],
            creditMultiplier = this[GroupAliasTable.creditMultiplier],
            targets = targets,
            routingPolicy = AliasRoutingPolicy.from(runCatching { this[GroupAliasTable.routingPolicy] }.getOrNull()).name,
        )
    }

    private fun decodeAliasTargets(raw: String): List<GroupAliasTargetView> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { item ->
            val obj = runCatching { item.jsonObject }.getOrNull()
            if (obj != null) {
                val model = obj["model"]?.jsonPrimitive?.content?.trim().orEmpty()
                val channelId = obj["channelId"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                model.takeIf { it.isNotBlank() }?.let { GroupAliasTargetView(it, channelId) }
            } else {
                item.jsonPrimitive.content.trim().takeIf { it.isNotBlank() }?.let { GroupAliasTargetView(it) }
            }
        }
    }.getOrDefault(emptyList())

    private fun normalizeGroupId(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "-")
        .trim('-')
        .ifBlank { DEFAULT_GROUP_ID }

    private fun maskKey(plain: String): String = when {
        plain.isEmpty() -> ""
        plain.length <= 8 -> "****"
        else -> plain.take(4) + "…" + plain.takeLast(4)
    }

    // ---- pricing (standalone) ----

    fun pricingCount(): Long = database.transaction {
        ModelPricingTable.selectAll().count()
    }

    private fun pricingStorageKey(model: String, variantKey: String?): String =
        variantKey?.takeIf { it.isNotBlank() }?.let { "$model::$it" } ?: model

    /** All public model names that the operator has wired up in `ChannelModelTable`. */
    fun configuredModelNames(): Set<String> = database.transaction {
        ChannelModelTable.selectAll()
            .where { ChannelModelTable.enabled eq true }
            .map { it[ChannelModelTable.publicModelName] }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * Copy the rates embedded in every enabled `ChannelModelTable` row into the
     * standalone `ModelPricingTable` so the Pricing tab can show and edit them.
     * Standalone rows always overwrite channel values (the operator's last edit wins).
     */
    fun absorbChannelPricingIntoStandalone() = database.transaction {
        ChannelModelTable.selectAll()
            .where { ChannelModelTable.enabled eq true }
            .forEach { row ->
                val model = row[ChannelModelTable.publicModelName]
                if (model.isBlank()) return@forEach
                val existing = ModelPricingTable.selectAll()
                    .where { ModelPricingTable.model eq model }
                    .singleOrNull()
                if (existing == null) {
                    val pricingId = "prc-${randomSuffix()}"
                    ModelPricingTable.insert {
                        it[ModelPricingTable.pricingId] = pricingId
                        it[ModelPricingTable.model] = pricingStorageKey(model, null)
                        it[ModelPricingTable.variantKey] = null
                        it[ModelPricingTable.label] = null
                        it[ModelPricingTable.billingUnitTokens] = 1_000_000
                        it[ModelPricingTable.inputCostPerMTok] = row[ChannelModelTable.inputCostPerMTok]
                        it[ModelPricingTable.outputCostPerMTok] = row[ChannelModelTable.outputCostPerMTok]
                        it[ModelPricingTable.cacheCreationCostPerMTok] = row[ChannelModelTable.cacheCreationCostPerMTok]
                        it[ModelPricingTable.cacheReadCostPerMTok] = row[ChannelModelTable.cacheReadCostPerMTok]
                        it[ModelPricingTable.cachedInputDiscount] = row[ChannelModelTable.cachedInputDiscount]
                        it[ModelPricingTable.reasoningOutputCostPerMTok] = row[ChannelModelTable.reasoningOutputCostPerMTok]
                        it[ModelPricingTable.creditMultiplier] = row[ChannelModelTable.creditMultiplier]
                    }
                    ModelPricingTierTable.insert {
                        it[ModelPricingTierTable.tierId] = "prt-${randomSuffix()}"
                        it[ModelPricingTierTable.pricingId] = pricingId
                        it[ModelPricingTierTable.startTokensInclusive] = 0
                        it[ModelPricingTierTable.endTokensExclusive] = null
                        it[ModelPricingTierTable.billingUnitTokens] = 1_000_000
                        it[ModelPricingTierTable.inputCostPerUnit] = row[ChannelModelTable.inputCostPerMTok]
                        it[ModelPricingTierTable.outputCostPerUnit] = row[ChannelModelTable.outputCostPerMTok]
                        it[ModelPricingTierTable.cacheCreationCostPerUnit] = row[ChannelModelTable.cacheCreationCostPerMTok]
                        it[ModelPricingTierTable.cacheReadCostPerUnit] = row[ChannelModelTable.cacheReadCostPerMTok]
                        it[ModelPricingTierTable.reasoningOutputCostPerUnit] = row[ChannelModelTable.reasoningOutputCostPerMTok]
                    }
                }
                // Existing rows are left as-is — operator's last edit through the
                // Pricing tab is authoritative, not the channel-embedded copy.
            }
    }

    fun listPricings(): List<PricingView> = database.transaction {
        val configured = configuredModelNames()
        ModelPricingTable.selectAll()
            .toList()
            .filter { row ->
                val baseModel = row[ModelPricingTable.model].substringBefore("::")
                baseModel in configured
            }
            .sortedBy { row -> row[ModelPricingTable.model] }
            .map { it.toPricingView() }
    }

    fun getPricing(model: String, variantKey: String? = null): PricingView? = database.transaction {
        if (model !in configuredModelNames()) return@transaction null
        ModelPricingTable.selectAll().where { ModelPricingTable.model eq pricingStorageKey(model, variantKey) }
            .singleOrNull()?.toPricingView()
    }

    fun upsertPricing(request: UpsertPricingRequest): PricingView = database.transaction {
        if (request.model !in configuredModelNames()) {
            throw IllegalArgumentException(
                "Model '${request.model}' is not configured in any channel. " +
                "Add the model to a channel first, then set its pricing."
            )
        }
        val storageKey = pricingStorageKey(request.model, request.variantKey)
        val tiers = request.tiers.ifEmpty {
            listOf(
                UpsertPricingTierRequest(
                    startTokensInclusive = 0,
                    endTokensExclusive = null,
                    billingUnitTokens = request.billingUnitTokens,
                    inputCostPerUnit = request.inputCostPerMTok,
                    outputCostPerUnit = request.outputCostPerMTok,
                    cacheCreationCostPerUnit = request.cacheCreationCostPerMTok,
                    cacheReadCostPerUnit = request.cacheReadCostPerMTok,
                    reasoningOutputCostPerUnit = request.reasoningOutputCostPerMTok,
                )
            )
        }
        val existing = ModelPricingTable.selectAll()
            .where { ModelPricingTable.model eq storageKey }
            .singleOrNull()
        val pricingId = existing?.get(ModelPricingTable.pricingId) ?: "prc-${randomSuffix()}"
        if (existing != null) {
            ModelPricingTable.update({ ModelPricingTable.pricingId eq pricingId }) {
                it[model] = storageKey
                it[variantKey] = request.variantKey?.ifBlank { null }
                it[label] = request.label?.ifBlank { null }
                it[billingUnitTokens] = request.billingUnitTokens
                it[inputCostPerMTok] = request.inputCostPerMTok
                it[outputCostPerMTok] = request.outputCostPerMTok
                it[cacheCreationCostPerMTok] = request.cacheCreationCostPerMTok
                it[cacheReadCostPerMTok] = request.cacheReadCostPerMTok
                it[cachedInputDiscount] = request.cachedInputDiscount
                it[reasoningOutputCostPerMTok] = request.reasoningOutputCostPerMTok
                it[creditMultiplier] = request.creditMultiplier
                it[notes] = request.notes
            }
        } else {
            ModelPricingTable.insert {
                it[ModelPricingTable.pricingId] = pricingId
                it[ModelPricingTable.model] = storageKey
                it[ModelPricingTable.variantKey] = request.variantKey?.ifBlank { null }
                it[ModelPricingTable.label] = request.label?.ifBlank { null }
                it[ModelPricingTable.billingUnitTokens] = request.billingUnitTokens
                it[ModelPricingTable.inputCostPerMTok] = request.inputCostPerMTok
                it[ModelPricingTable.outputCostPerMTok] = request.outputCostPerMTok
                it[ModelPricingTable.cacheCreationCostPerMTok] = request.cacheCreationCostPerMTok
                it[ModelPricingTable.cacheReadCostPerMTok] = request.cacheReadCostPerMTok
                it[ModelPricingTable.cachedInputDiscount] = request.cachedInputDiscount
                it[ModelPricingTable.reasoningOutputCostPerMTok] = request.reasoningOutputCostPerMTok
                it[ModelPricingTable.creditMultiplier] = request.creditMultiplier
                it[ModelPricingTable.notes] = request.notes
            }
        }
        ModelPricingTierTable.deleteWhere { ModelPricingTierTable.pricingId eq pricingId }
        tiers.forEach { tier ->
            ModelPricingTierTable.insert {
                it[ModelPricingTierTable.tierId] = "prt-${randomSuffix()}"
                it[ModelPricingTierTable.pricingId] = pricingId
                it[ModelPricingTierTable.startTokensInclusive] = tier.startTokensInclusive
                it[ModelPricingTierTable.endTokensExclusive] = tier.endTokensExclusive
                it[ModelPricingTierTable.billingUnitTokens] = tier.billingUnitTokens
                it[ModelPricingTierTable.inputCostPerUnit] = tier.inputCostPerUnit
                it[ModelPricingTierTable.outputCostPerUnit] = tier.outputCostPerUnit
                it[ModelPricingTierTable.cacheCreationCostPerUnit] = tier.cacheCreationCostPerUnit
                it[ModelPricingTierTable.cacheReadCostPerUnit] = tier.cacheReadCostPerUnit
                it[ModelPricingTierTable.reasoningOutputCostPerUnit] = tier.reasoningOutputCostPerUnit
            }
        }
        getPricing(request.model, request.variantKey)!!
    }

    fun deletePricing(model: String, variantKey: String? = null): Boolean = database.transaction {
        val isPricingId = model.startsWith("prc-")
        if (!isPricingId && model !in configuredModelNames()) {
            // Silently no-op — the row may have already been pruned.
            return@transaction ModelPricingTable.deleteWhere { ModelPricingTable.model eq pricingStorageKey(model, variantKey) } > 0
        }
        val pricingId = ModelPricingTable.selectAll()
            .where {
                if (isPricingId) {
                    ModelPricingTable.pricingId eq model
                } else {
                    ModelPricingTable.model eq pricingStorageKey(model, variantKey)
                }
            }
            .singleOrNull()
            ?.get(ModelPricingTable.pricingId)
        if (pricingId != null) {
            ModelPricingTierTable.deleteWhere { ModelPricingTierTable.pricingId eq pricingId }
        }
        if (isPricingId) {
            ModelPricingTable.deleteWhere { ModelPricingTable.pricingId eq model } > 0
        } else {
            ModelPricingTable.deleteWhere { ModelPricingTable.model eq pricingStorageKey(model, variantKey) } > 0
        }
    }

    /**
     * Drop pricing rows whose model no longer exists in any enabled channel.
     * Keeps the standalone table in lock-step with the operator's actual config.
     */
    fun pruneUnconfiguredPricing() = database.transaction {
        val configured = configuredModelNames()
        ModelPricingTable.selectAll()
            .filter { it[ModelPricingTable.model] !in configured }
            .forEach { row ->
                ModelPricingTable.deleteWhere { ModelPricingTable.pricingId eq row[ModelPricingTable.pricingId] }
            }
    }

    private fun ResultRow.toPricingView(): PricingView {
        val currentPricingId = this[ModelPricingTable.pricingId]
        val tiers = ModelPricingTierTable.selectAll()
            .where { ModelPricingTierTable.pricingId eq currentPricingId }
            .toList()
            .sortedBy { tier -> tier[ModelPricingTierTable.startTokensInclusive] }
            .map { tier ->
                PricingTierView(
                    startTokensInclusive = tier[ModelPricingTierTable.startTokensInclusive],
                    endTokensExclusive = tier[ModelPricingTierTable.endTokensExclusive],
                    billingUnitTokens = tier[ModelPricingTierTable.billingUnitTokens],
                    inputCostPerUnit = tier[ModelPricingTierTable.inputCostPerUnit],
                    outputCostPerUnit = tier[ModelPricingTierTable.outputCostPerUnit],
                    cacheCreationCostPerUnit = tier[ModelPricingTierTable.cacheCreationCostPerUnit],
                    cacheReadCostPerUnit = tier[ModelPricingTierTable.cacheReadCostPerUnit],
                    reasoningOutputCostPerUnit = tier[ModelPricingTierTable.reasoningOutputCostPerUnit],
                )
            }
        return PricingView(
            pricingId = currentPricingId,
            model = this[ModelPricingTable.model].substringBefore("::"),
            variantKey = this[ModelPricingTable.variantKey],
            label = this[ModelPricingTable.label],
            billingUnitTokens = this[ModelPricingTable.billingUnitTokens],
            tiers = tiers,
            inputCostPerMTok = this[ModelPricingTable.inputCostPerMTok],
            outputCostPerMTok = this[ModelPricingTable.outputCostPerMTok],
            cacheCreationCostPerMTok = this[ModelPricingTable.cacheCreationCostPerMTok],
            cacheReadCostPerMTok = this[ModelPricingTable.cacheReadCostPerMTok],
            cachedInputDiscount = this[ModelPricingTable.cachedInputDiscount],
            reasoningOutputCostPerMTok = this[ModelPricingTable.reasoningOutputCostPerMTok],
            creditMultiplier = this[ModelPricingTable.creditMultiplier],
            notes = this[ModelPricingTable.notes],
        )
    }

    companion object {
        const val DEFAULT_GROUP_ID = "default"
    }

    // ---- batches (minimal) ----

    fun listBatches(): BatchListResponse = database.transaction {
        val rows = com.keel.samples.aigateway.airelay.batches.BatchesTable.selectAll()
            .orderBy(com.keel.samples.aigateway.airelay.batches.BatchesTable.createdAt to SortOrder.DESC)
            .map { it.toBatchView() }
        BatchListResponse(rows, rows.size)
    }

    fun createBatch(request: CreateBatchRequest): BatchView = database.transaction {
        val now = kotlinx.datetime.Clock.System.now()
        val batchId = "bat-${randomSuffix()}"
        val counts = mapOf("total" to request.requests.size, "completed" to 0, "failed" to 0)
        val countsJson = "{\"total\":${request.requests.size},\"completed\":0,\"failed\":0}"
        com.keel.samples.aigateway.airelay.batches.BatchesTable.insert {
            it[com.keel.samples.aigateway.airelay.batches.BatchesTable.batchId] = batchId
            it[customerId] = null
            it[groupId] = request.groupId.ifBlank { DEFAULT_GROUP_ID }
            it[status] = com.keel.samples.aigateway.airelay.batches.BatchStatuses.IN_PROGRESS
            it[requestCountsJson] = countsJson
            it[resultsJsonlPath] = null
            it[expiresAt] = null
            it[createdAt] = now; it[updatedAt] = now
            it[createdBy] = "system"; it[updatedBy] = "system"; it[deletedAt] = null
        }
        BatchView(batchId, com.keel.samples.aigateway.airelay.batches.BatchStatuses.IN_PROGRESS, request.groupId, counts, null, null, now.toString())
    }

    fun getBatch(batchId: String): BatchView? = database.transaction {
        com.keel.samples.aigateway.airelay.batches.BatchesTable.selectAll()
            .where { com.keel.samples.aigateway.airelay.batches.BatchesTable.batchId eq batchId }
            .firstOrNull()?.toBatchView()
    }

    fun cancelBatch(batchId: String): BatchView? = database.transaction {
        com.keel.samples.aigateway.airelay.batches.BatchesTable.update({ com.keel.samples.aigateway.airelay.batches.BatchesTable.batchId eq batchId }) {
            it[status] = com.keel.samples.aigateway.airelay.batches.BatchStatuses.CANCELING
        }
        getBatch(batchId)
    }

    fun getBatchResults(batchId: String): String = ""

    private fun randomSuffix(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun ResultRow.toBatchView(): BatchView {
        val raw = this[com.keel.samples.aigateway.airelay.batches.BatchesTable.requestCountsJson]
        val counts = runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
            obj.mapValues { (_, v) -> v.jsonPrimitive.intOrNull ?: 0 }
        }.getOrDefault(emptyMap())
        return BatchView(
            batchId = this[com.keel.samples.aigateway.airelay.batches.BatchesTable.batchId],
            status = this[com.keel.samples.aigateway.airelay.batches.BatchesTable.status],
            groupId = this[com.keel.samples.aigateway.airelay.batches.BatchesTable.groupId],
            requestCounts = counts,
            resultsJsonl = null,
            expiresAt = this[com.keel.samples.aigateway.airelay.batches.BatchesTable.expiresAt]?.toString(),
            createdAt = this[com.keel.samples.aigateway.airelay.batches.BatchesTable.createdAt].toString(),
        )
    }
}
