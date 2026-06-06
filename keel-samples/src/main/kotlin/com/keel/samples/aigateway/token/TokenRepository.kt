package com.keel.samples.aigateway.token

import com.keel.contract.ai.ApiKeyVerifier
import com.keel.contract.ai.CostBreakdown
import com.keel.contract.ai.InvalidApiKeyException
import com.keel.contract.ai.ModelUsageSummary
import com.keel.contract.ai.QuotaExceededException
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UsageRecordInput
import com.keel.contract.ai.UsageRecordView
import com.keel.contract.ai.UsageRecorder
import com.keel.contract.ai.UsageSnapshot
import com.keel.contract.ai.UserDirectory
import com.keel.contract.ai.UserUsageSummary
import com.keel.contract.ai.VerifiedApiKey
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.PluginApiException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.days

class TokenRepository(
    private val database: KeelDatabase,
    private val userDirectory: UserDirectory,
    private val random: SecureRandom = SecureRandom()
) : ApiKeyVerifier, UsageRecorder {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun initializeSchema() {
        database.createTables(ApiKeysTable, ApiKeyGroupTable, UsageRecordsTable)
        migrateUsageRecordOutcomeColumns()
        backfillKeyGroups()
    }

    private fun migrateUsageRecordOutcomeColumns() {
        database.transaction {
            exec("ALTER TABLE token_usage_records ADD COLUMN IF NOT EXISTS transport_status INT NOT NULL DEFAULT 200")
            exec("ALTER TABLE token_usage_records ADD COLUMN IF NOT EXISTS outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'")
            exec("ALTER TABLE token_usage_records ADD COLUMN IF NOT EXISTS usage_source VARCHAR(16) NOT NULL DEFAULT 'PROVIDER'")
            exec("ALTER TABLE token_usage_records ALTER COLUMN error_code VARCHAR(128)")
        }
    }

    fun seedDemoKeyIfNeeded() = database.transaction {
        if (ApiKeysTable.selectAll().count() == 0L) {
            val now = Clock.System.now()
            val rawKey = "sk-keel-demo-user"
            ApiKeysTable.insert {
                it[keyId] = "key-demo-user"
                it[keyPrefix] = rawKey.take(16)
                it[keyHash] = hashKey(rawKey)
                it[userId] = "usr-demo"
                it[displayName] = "Demo key"
                it[maxBudgetUsd] = 100.0
                it[budgetDurationDays] = 30
                it[budgetResetAt] = now.plus(30.days)
                it[currentSpendUsd] = 0.0
                it[tempBudgetIncrease] = null
                it[tempBudgetExpiry] = null
                it[rpmLimit] = 120
                it[tpmLimit] = 120_000
                it[allowedModelsJson] = json.encodeToString(listOf("gpt-4o-mini", "gpt-4o", "gpt-5", "gpt-chat-only", "gpt-responses-only", "claude-only", "claude-sonnet-4-20250514"))
                it[allowedIpsJson] = json.encodeToString(emptyList<String>())
                it[status] = ApiKeyStatuses.ACTIVE
                it[expiresAt] = null
                it[lastUsedAt] = null
                it[createdAt] = now
                it[updatedAt] = now
                it[createdBy] = "system"
                it[updatedBy] = "system"
                it[deletedAt] = null
            }
            ApiKeyGroupTable.insert {
                it[keyId] = "key-demo-user"
                it[groupId] = DEFAULT_GROUP_ID
            }
        }
    }

    fun createKey(ownerUserId: String, request: CreateApiKeyRequest): ApiKeyCreatedResponse = database.transaction {
        val now = Clock.System.now()
        val rawKey = nextRawKey()
        val keyIdValue = nextId("key")
        ApiKeysTable.insert {
            it[keyId] = keyIdValue
            it[keyPrefix] = rawKey.take(16)
            it[keyHash] = hashKey(rawKey)
            it[userId] = ownerUserId
            it[displayName] = request.displayName.trim().ifBlank { throw PluginApiException(400, "displayName is required") }
            it[maxBudgetUsd] = request.maxBudgetUsd
            it[budgetDurationDays] = request.budgetDurationDays.coerceAtLeast(1)
            it[budgetResetAt] = now.plus(request.budgetDurationDays.coerceAtLeast(1).days)
            it[currentSpendUsd] = 0.0
            it[tempBudgetIncrease] = null
            it[tempBudgetExpiry] = null
            it[rpmLimit] = request.rpmLimit
            it[tpmLimit] = request.tpmLimit
            it[allowedModelsJson] = json.encodeToString(request.allowedModels)
            it[allowedIpsJson] = json.encodeToString(request.allowedIps)
            it[status] = ApiKeyStatuses.ACTIVE
            it[expiresAt] = request.expiresInDays?.let { days -> now.plus(days.coerceAtLeast(1).days) }
            it[lastUsedAt] = null
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = ownerUserId
            it[updatedBy] = ownerUserId
            it[deletedAt] = null
        }
        ApiKeyGroupTable.insert {
            it[keyId] = keyIdValue
            it[groupId] = request.groupId.ifBlank { DEFAULT_GROUP_ID }
        }
        ApiKeyCreatedResponse(findKeyRow(keyIdValue).toView(), rawKey)
    }

    fun listKeys(ownerUserId: String, includeAll: Boolean): ApiKeyListResponse = database.transaction {
        val query = ApiKeysTable.selectAll().where {
            if (includeAll) ApiKeysTable.deletedAt.isNull() else (ApiKeysTable.userId eq ownerUserId) and ApiKeysTable.deletedAt.isNull()
        }
        val keys = query.orderBy(ApiKeysTable.createdAt to SortOrder.DESC).map { it.toView() }
        ApiKeyListResponse(keys, keys.size)
    }

    fun getKey(ownerUserId: String, keyId: String, includeAll: Boolean): ApiKeyView = database.transaction {
        val row = findKeyRow(keyId)
        if (!includeAll && row[ApiKeysTable.userId] != ownerUserId) throw PluginApiException(403, "Forbidden")
        row.toView()
    }

    fun updateKey(ownerUserId: String, keyId: String, request: UpdateApiKeyRequest, includeAll: Boolean): ApiKeyView = database.transaction {
        val row = findKeyRow(keyId)
        if (!includeAll && row[ApiKeysTable.userId] != ownerUserId) throw PluginApiException(403, "Forbidden")
        val now = Clock.System.now()
        ApiKeysTable.update({ ApiKeysTable.keyId eq keyId }) {
            request.displayName?.trim()?.takeIf(String::isNotBlank)?.let { value -> it[displayName] = value }
            request.allowedModels?.let { value -> it[allowedModelsJson] = json.encodeToString(value) }
            request.allowedIps?.let { value -> it[allowedIpsJson] = json.encodeToString(value) }
            request.maxBudgetUsd?.let { value -> it[maxBudgetUsd] = value }
            if (request.rpmLimit != null) it[rpmLimit] = request.rpmLimit
            if (request.tpmLimit != null) it[tpmLimit] = request.tpmLimit
            request.status?.let { value -> it[status] = normalizeStatus(value) }
            it[updatedAt] = now
            it[updatedBy] = ownerUserId
        }
        request.groupId?.let { group -> upsertKeyGroup(keyId, group.ifBlank { DEFAULT_GROUP_ID }) }
        findKeyRow(keyId).toView()
    }

    fun revokeKey(ownerUserId: String, keyId: String, includeAll: Boolean): ApiKeyView = database.transaction {
        val row = findKeyRow(keyId)
        if (!includeAll && row[ApiKeysTable.userId] != ownerUserId) throw PluginApiException(403, "Forbidden")
        val now = Clock.System.now()
        ApiKeysTable.update({ ApiKeysTable.keyId eq keyId }) {
            it[status] = ApiKeyStatuses.REVOKED
            it[updatedAt] = now
            it[updatedBy] = ownerUserId
        }
        findKeyRow(keyId).toView()
    }

    fun deleteKey(ownerUserId: String, keyId: String, includeAll: Boolean): ApiKeyView = database.transaction {
        val row = findKeyRow(keyId)
        if (!includeAll && row[ApiKeysTable.userId] != ownerUserId) throw PluginApiException(403, "Forbidden")
        val now = Clock.System.now()
        ApiKeysTable.update({ ApiKeysTable.keyId eq keyId }) {
            it[status] = ApiKeyStatuses.REVOKED
            it[updatedAt] = now
            it[updatedBy] = ownerUserId
            it[deletedAt] = now
        }
        row.toView().copy(status = ApiKeyStatuses.REVOKED)
    }

    fun addTempBudget(ownerUserId: String, keyId: String, request: TempBudgetRequest, includeAll: Boolean): ApiKeyView = database.transaction {
        val row = findKeyRow(keyId)
        if (!includeAll && row[ApiKeysTable.userId] != ownerUserId) throw PluginApiException(403, "Forbidden")
        val now = Clock.System.now()
        ApiKeysTable.update({ ApiKeysTable.keyId eq keyId }) {
            it[tempBudgetIncrease] = (row[ApiKeysTable.tempBudgetIncrease] ?: 0.0) + request.amountUsd
            it[tempBudgetExpiry] = now.plus(request.expiresInDays.coerceAtLeast(1).days)
            it[updatedAt] = now
            it[updatedBy] = ownerUserId
        }
        findKeyRow(keyId).toView()
    }

    fun usageForKey(ownerUserId: String, keyId: String, includeAll: Boolean): UsageListResponse = database.transaction {
        val key = findKeyRow(keyId)
        if (!includeAll && key[ApiKeysTable.userId] != ownerUserId) throw PluginApiException(403, "Forbidden")
        val records = UsageRecordsTable.selectAll()
            .where { (UsageRecordsTable.keyId eq keyId) and UsageRecordsTable.deletedAt.isNull() }
            .orderBy(UsageRecordsTable.createdAt to SortOrder.DESC)
            .map { it.toUsageRecordView() }
        UsageListResponse(records, records.size)
    }

    override suspend fun verify(rawKey: String, clientIp: String?): VerifiedApiKey {
        val partial = database.suspendTransaction {
            val now = Clock.System.now()
            val row = ApiKeysTable.selectAll()
                .where { (ApiKeysTable.keyHash eq hashKey(rawKey)) and ApiKeysTable.deletedAt.isNull() }
                .firstOrNull()
                ?: throw InvalidApiKeyException("not_found")
            if (row[ApiKeysTable.status] != ApiKeyStatuses.ACTIVE) throw InvalidApiKeyException(row[ApiKeysTable.status])
            row[ApiKeysTable.expiresAt]?.let { if (now >= it) throw InvalidApiKeyException(ApiKeyStatuses.EXPIRED) }
            maybeResetBudget(row, now)
            val refreshed = findKeyRow(row[ApiKeysTable.keyId])
            val allowedIps = refreshed.allowedIps()
            if (allowedIps.isNotEmpty() && clientIp != null && clientIp !in allowedIps) {
                throw InvalidApiKeyException("ip_not_allowed")
            }
            val maxBudget = refreshed.effectiveBudget(now)
            val remaining = maxBudget - refreshed[ApiKeysTable.currentSpendUsd]
            if (remaining <= 0.0) throw QuotaExceededException(refreshed[ApiKeysTable.keyId])
            VerifiedKeyPartial(
                keyId = refreshed[ApiKeysTable.keyId],
                userId = refreshed[ApiKeysTable.userId],
                routingGroupId = groupForKey(refreshed[ApiKeysTable.keyId]),
                allowedModels = refreshed.allowedModels(),
                rpmLimit = refreshed[ApiKeysTable.rpmLimit],
                tpmLimit = refreshed[ApiKeysTable.tpmLimit],
                remainingBudgetUsd = remaining
            )
        }
        val user = userDirectory.findById(partial.userId)
            ?: throw InvalidApiKeyException("user_not_found")
        if (user.status != "active") throw InvalidApiKeyException("user_${user.status}")
        database.suspendTransaction {
            val now = Clock.System.now()
            ApiKeysTable.update({ ApiKeysTable.keyId eq partial.keyId }) {
                it[lastUsedAt] = now
                it[updatedAt] = now
                it[updatedBy] = user.userId
            }
        }
        return VerifiedApiKey(
            keyId = partial.keyId,
            userId = partial.userId,
            userGroupId = user.groupId,
            routingGroupId = partial.routingGroupId,
            allowedModels = partial.allowedModels,
            rpmLimit = partial.rpmLimit,
            tpmLimit = partial.tpmLimit,
            remainingBudgetUsd = partial.remainingBudgetUsd
        )
    }

    override suspend fun record(record: UsageRecordInput) {
        database.suspendTransaction {
            val now = Clock.System.now()
            UsageRecordsTable.insert {
                it[recordId] = nextId("use")
                it[keyId] = record.keyId
                it[userId] = record.userId
                it[userGroupId] = record.userGroupId
                it[clientProtocol] = record.clientProtocol
                it[upstreamProtocol] = record.upstreamProtocol
                it[model] = record.model
                it[provider] = record.provider
                it[poolLevelId] = record.poolLevelId
                it[upstreamKeyId] = record.upstreamKeyId
                it[promptTokens] = record.usage.promptTokens
                it[completionTokens] = record.usage.completionTokens
                it[cacheCreationInputTokens] = record.usage.cacheCreationInputTokens
                it[cacheReadInputTokens] = record.usage.cacheReadInputTokens
                it[cachedPromptTokens] = record.usage.cachedPromptTokens
                it[reasoningTokens] = record.usage.reasoningTokens
                it[inputCostUsd] = record.cost.inputCostUsd
                it[outputCostUsd] = record.cost.outputCostUsd
                it[cacheWriteCostUsd] = record.cost.cacheWriteCostUsd
                it[cacheReadCostUsd] = record.cost.cacheReadCostUsd
                it[totalCostUsd] = record.cost.totalCostUsd
                it[cacheHitRate] = record.cost.cacheHitRate
                it[latencyMs] = record.latencyMs
                it[status] = record.status
                it[transportStatus] = record.transportStatus
                it[outcome] = record.outcome.name
                it[usageSource] = record.usageSource.name
                it[errorCode] = record.errorCode?.take(128)
                it[streamed] = record.streamed
                it[failoverCount] = record.failoverCount
                it[createdAt] = now
                it[updatedAt] = now
                it[createdBy] = record.userId
                it[updatedBy] = record.userId
                it[deletedAt] = null
            }
            if (record.cost.totalCostUsd > 0.0) {
                val current = ApiKeysTable.selectAll()
                    .where { ApiKeysTable.keyId eq record.keyId }
                    .firstOrNull()
                    ?.get(ApiKeysTable.currentSpendUsd) ?: 0.0
                ApiKeysTable.update({ ApiKeysTable.keyId eq record.keyId }) {
                    it[currentSpendUsd] = current + record.cost.totalCostUsd
                    it[updatedAt] = now
                    it[updatedBy] = record.userId
                }
            }
        }
    }

    override suspend fun snapshot(): UsageSnapshot = database.suspendTransaction {
        val rows = UsageRecordsTable.selectAll().where { UsageRecordsTable.deletedAt.isNull() }.toList()
        val recent = rows.sortedByDescending { it[UsageRecordsTable.createdAt] }.take(20).map { it.toUsageView() }
        val topModels = rows.groupBy { it[UsageRecordsTable.model] }.map { (model, modelRows) ->
            ModelUsageSummary(
                model = model,
                requests = modelRows.size.toLong(),
                totalTokens = modelRows.sumOf { it[UsageRecordsTable.promptTokens] + it[UsageRecordsTable.completionTokens] }.toLong(),
                totalCostUsd = modelRows.sumOf { it[UsageRecordsTable.totalCostUsd] }
            )
        }.sortedByDescending { it.totalCostUsd }.take(10)
        val topUsers = rows.groupBy { it[UsageRecordsTable.userId] }.map { (userId, userRows) ->
            UserUsageSummary(
                userId = userId,
                requests = userRows.size.toLong(),
                totalTokens = userRows.sumOf { it[UsageRecordsTable.promptTokens] + it[UsageRecordsTable.completionTokens] }.toLong(),
                totalCostUsd = userRows.sumOf { it[UsageRecordsTable.totalCostUsd] }
            )
        }.sortedByDescending { it.totalCostUsd }.take(10)
        UsageSnapshot(
            totalRequests = rows.size.toLong(),
            totalCostUsd = rows.sumOf { it[UsageRecordsTable.totalCostUsd] },
            totalTokens = rows.sumOf { it[UsageRecordsTable.promptTokens] + it[UsageRecordsTable.completionTokens] }.toLong(),
            recentRequests = recent,
            topModels = topModels,
            topUsers = topUsers
        )
    }

    fun recentRecords(limit: Int, status: Int? = null): UsageListResponse = database.transaction {
        val n = limit.coerceIn(1, 200)
        val base = UsageRecordsTable.selectAll().where { UsageRecordsTable.deletedAt.isNull() }
        val rows = (if (status != null) base.andWhere { UsageRecordsTable.status eq status } else base)
            .orderBy(UsageRecordsTable.createdAt to SortOrder.DESC)
            .limit(n)
            .map { it.toUsageRecordView() }
        UsageListResponse(records = rows, total = rows.size)
    }

    private data class VerifiedKeyPartial(
        val keyId: String,
        val userId: String,
        val routingGroupId: String,
        val allowedModels: List<String>,
        val rpmLimit: Int?,
        val tpmLimit: Int?,
        val remainingBudgetUsd: Double
    )

    private fun backfillKeyGroups() = database.transaction {
        val mapped = ApiKeyGroupTable.selectAll().map { it[ApiKeyGroupTable.keyId] }.toSet()
        ApiKeysTable.selectAll().forEach { row ->
            val keyId = row[ApiKeysTable.keyId]
            if (keyId !in mapped) {
                ApiKeyGroupTable.insert {
                    it[ApiKeyGroupTable.keyId] = keyId
                    it[groupId] = DEFAULT_GROUP_ID
                }
            }
        }
    }

    private fun upsertKeyGroup(keyId: String, groupId: String) {
        val updated = ApiKeyGroupTable.update({ ApiKeyGroupTable.keyId eq keyId }) {
            it[ApiKeyGroupTable.groupId] = groupId
        }
        if (updated == 0) {
            ApiKeyGroupTable.insert {
                it[ApiKeyGroupTable.keyId] = keyId
                it[ApiKeyGroupTable.groupId] = groupId
            }
        }
    }

    private fun groupForKey(keyId: String): String = ApiKeyGroupTable.selectAll()
        .where { ApiKeyGroupTable.keyId eq keyId }
        .firstOrNull()
        ?.get(ApiKeyGroupTable.groupId)
        ?: DEFAULT_GROUP_ID

    private fun maybeResetBudget(row: ResultRow, now: Instant) {
        if (now < row[ApiKeysTable.budgetResetAt]) return
        ApiKeysTable.update({ ApiKeysTable.keyId eq row[ApiKeysTable.keyId] }) {
            it[currentSpendUsd] = 0.0
            it[budgetResetAt] = now.plus(row[ApiKeysTable.budgetDurationDays].days)
            it[tempBudgetIncrease] = null
            it[tempBudgetExpiry] = null
            it[updatedAt] = now
            it[updatedBy] = "system"
        }
    }

    private fun findKeyRow(keyId: String): ResultRow = ApiKeysTable.selectAll()
        .where { (ApiKeysTable.keyId eq keyId) and ApiKeysTable.deletedAt.isNull() }
        .firstOrNull()
        ?: throw PluginApiException(404, "API key not found")

    private fun ResultRow.toView(): ApiKeyView {
        val now = Clock.System.now()
        val budget = effectiveBudget(now)
        val spend = this[ApiKeysTable.currentSpendUsd]
        return ApiKeyView(
            keyId = this[ApiKeysTable.keyId],
            keyPrefix = this[ApiKeysTable.keyPrefix],
            userId = this[ApiKeysTable.userId],
            displayName = this[ApiKeysTable.displayName],
            groupId = groupForKey(this[ApiKeysTable.keyId]),
            maxBudgetUsd = budget,
            currentSpendUsd = spend,
            remainingBudgetUsd = budget - spend,
            rpmLimit = this[ApiKeysTable.rpmLimit],
            tpmLimit = this[ApiKeysTable.tpmLimit],
            allowedModels = allowedModels(),
            allowedIps = allowedIps(),
            status = this[ApiKeysTable.status],
            expiresAt = this[ApiKeysTable.expiresAt]?.toString()
        )
    }

    private fun ResultRow.toUsageRecordView(): TokenUsageRecordView = TokenUsageRecordView(
        recordId = this[UsageRecordsTable.recordId],
        keyId = this[UsageRecordsTable.keyId],
        userId = this[UsageRecordsTable.userId],
        model = this[UsageRecordsTable.model],
        provider = this[UsageRecordsTable.provider],
        status = this[UsageRecordsTable.status],
        transportStatus = this[UsageRecordsTable.transportStatus],
        outcome = this[UsageRecordsTable.outcome],
        errorCode = this[UsageRecordsTable.errorCode],
        usageSource = this[UsageRecordsTable.usageSource],
        usage = usage(),
        cost = cost(),
        latencyMs = this[UsageRecordsTable.latencyMs],
        createdAt = this[UsageRecordsTable.createdAt].toString()
    )

    private fun ResultRow.toUsageView(): UsageRecordView = UsageRecordView(
        recordId = this[UsageRecordsTable.recordId],
        userId = this[UsageRecordsTable.userId],
        keyId = this[UsageRecordsTable.keyId],
        model = this[UsageRecordsTable.model],
        provider = this[UsageRecordsTable.provider],
        status = this[UsageRecordsTable.status],
        totalTokens = this[UsageRecordsTable.promptTokens] + this[UsageRecordsTable.completionTokens],
        totalCostUsd = this[UsageRecordsTable.totalCostUsd],
        latencyMs = this[UsageRecordsTable.latencyMs],
        createdAt = this[UsageRecordsTable.createdAt].toString()
    )

    private fun ResultRow.usage(): TokenUsage = TokenUsage(
        promptTokens = this[UsageRecordsTable.promptTokens],
        completionTokens = this[UsageRecordsTable.completionTokens],
        cacheCreationInputTokens = this[UsageRecordsTable.cacheCreationInputTokens],
        cacheReadInputTokens = this[UsageRecordsTable.cacheReadInputTokens],
        cachedPromptTokens = this[UsageRecordsTable.cachedPromptTokens],
        reasoningTokens = this[UsageRecordsTable.reasoningTokens]
    )

    private fun ResultRow.cost(): CostBreakdown = CostBreakdown(
        inputCostUsd = this[UsageRecordsTable.inputCostUsd],
        outputCostUsd = this[UsageRecordsTable.outputCostUsd],
        cacheWriteCostUsd = this[UsageRecordsTable.cacheWriteCostUsd],
        cacheReadCostUsd = this[UsageRecordsTable.cacheReadCostUsd],
        totalCostUsd = this[UsageRecordsTable.totalCostUsd],
        cacheHitRate = this[UsageRecordsTable.cacheHitRate]
    )

    private fun ResultRow.allowedModels(): List<String> = decodeStringList(this[ApiKeysTable.allowedModelsJson])

    private fun ResultRow.allowedIps(): List<String> = decodeStringList(this[ApiKeysTable.allowedIpsJson])

    private fun ResultRow.effectiveBudget(now: Instant): Double {
        val temp = this[ApiKeysTable.tempBudgetIncrease]
        val tempExpiry = this[ApiKeysTable.tempBudgetExpiry]
        return this[ApiKeysTable.maxBudgetUsd] + if (temp != null && tempExpiry != null && now < tempExpiry) temp else 0.0
    }

    private fun decodeStringList(value: String): List<String> = runCatching {
        json.decodeFromString<List<String>>(value)
    }.getOrDefault(emptyList())

    private fun normalizeStatus(status: String): String = when (status.trim().lowercase()) {
        ApiKeyStatuses.ACTIVE -> ApiKeyStatuses.ACTIVE
        ApiKeyStatuses.REVOKED -> ApiKeyStatuses.REVOKED
        ApiKeyStatuses.EXPIRED -> ApiKeyStatuses.EXPIRED
        else -> throw PluginApiException(400, "Unsupported key status")
    }

    private fun nextRawKey(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return "sk-keel-${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
    }

    private fun nextId(prefix: String): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return "$prefix-${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
    }

    private fun hashKey(rawKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawKey.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    companion object {
        const val DEFAULT_GROUP_ID = "default"
    }
}
