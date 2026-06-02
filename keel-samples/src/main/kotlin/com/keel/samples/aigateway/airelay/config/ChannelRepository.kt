package com.keel.samples.aigateway.airelay.config

import com.keel.db.database.KeelDatabase
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

@Serializable
data class ChannelView(
    val channelId: String,
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
    val models: List<ChannelModelView> = emptyList()
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
    val timeoutMs: Long = 60_000,
    val models: List<UpsertModelRequest> = emptyList()
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
    val enabled: Boolean = true
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
    fun initializeSchema() {
        database.createTables(ChannelTable, ChannelModelTable)
    }

    fun listChannels(): List<ChannelView> = database.transaction {
        val models = ChannelModelTable.selectAll().map { it.toModelView() }.groupBy { it.channelId }
        ChannelTable.selectAll()
            .orderBy(ChannelTable.priority to SortOrder.DESC, ChannelTable.name to SortOrder.ASC)
            .map { row -> row.toChannelView(models[row[ChannelTable.channelId]] ?: emptyList()) }
    }

    fun getChannel(channelId: String): ChannelView? = database.transaction {
        val row = ChannelTable.selectAll().where { ChannelTable.channelId eq channelId }.singleOrNull()
            ?: return@transaction null
        val models = ChannelModelTable.selectAll().where { ChannelModelTable.channelId eq channelId }
            .map { it.toModelView() }
        row.toChannelView(models)
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
            replaceModels(channelId, request.models)
        }
        return getChannel(channelId)
    }

    fun deleteChannel(channelId: String): Boolean = database.transaction {
        ChannelModelTable.deleteWhere { ChannelModelTable.channelId eq channelId }
        ChannelTable.deleteWhere { ChannelTable.channelId eq channelId } > 0
    }

    fun setEnabled(channelId: String, enabled: Boolean): Boolean = database.transaction {
        ChannelTable.update({ ChannelTable.channelId eq channelId }) { it[ChannelTable.enabled] = enabled } > 0
    }

    fun recordTestResult(channelId: String, latencyMs: Long?, error: String?) = database.transaction {
        ChannelTable.update({ ChannelTable.channelId eq channelId }) {
            it[status] = if (error == null) "HEALTHY" else "DEGRADED"
            it[lastTestLatencyMs] = latencyMs
            it[lastTestError] = error?.take(500)
        }
    }

    fun count(): Long = database.transaction { ChannelTable.selectAll().count() }

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
                it[enabled] = m.enabled
            }
        }
    }

    private fun ResultRow.toChannelView(models: List<ChannelModelView>): ChannelView {
        val enc = this[ChannelTable.apiKeyEncrypted]
        return ChannelView(
            channelId = this[ChannelTable.channelId],
            name = this[ChannelTable.name],
            protocol = this[ChannelTable.protocol],
            baseUrl = this[ChannelTable.baseUrl],
            apiKeyMasked = maskKey(runCatching { cipher.decrypt(enc) }.getOrDefault("")),
            apiKeyEnv = this[ChannelTable.apiKeyEnv],
            enabled = this[ChannelTable.enabled],
            priority = this[ChannelTable.priority],
            weight = this[ChannelTable.weight],
            maxConcurrency = this[ChannelTable.maxConcurrency],
            timeoutMs = this[ChannelTable.timeoutMs],
            status = this[ChannelTable.status],
            lastTestLatencyMs = this[ChannelTable.lastTestLatencyMs],
            lastTestError = this[ChannelTable.lastTestError],
            models = models
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
        enabled = this[ChannelModelTable.enabled]
    )

    private fun maskKey(plain: String): String = when {
        plain.isEmpty() -> ""
        plain.length <= 8 -> "****"
        else -> plain.take(4) + "…" + plain.takeLast(4)
    }
}
