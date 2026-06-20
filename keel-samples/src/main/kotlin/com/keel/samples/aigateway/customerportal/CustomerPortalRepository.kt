package com.keel.samples.aigateway.customerportal

import com.keel.contract.customer.*
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.aigateway.customerportal.auth.*
import com.keel.samples.aigateway.customerportal.credits.*
import com.keel.samples.aigateway.customerportal.keys.CustomerKeysTable
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.days

class CustomerPortalRepository(
    private val database: KeelDatabase,
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    private val jwtService: CustomerJwtService = CustomerJwtService(),
    private val random: SecureRandom = SecureRandom(),
) : CustomerApiKeyVerifier, CreditLedger, CustomerDirectory {

    companion object {
        const val SIGNUP_BONUS_CREDITS = 1000L
        const val DEFAULT_GROUP_ID = "default"
    }

    // ---- lifecycle ----

    fun initializeSchema() {
        database.createTables(
            CustomersTable, CustomerSessionsTable, CustomerKeysTable,
            CreditLedgerTable, CustomerUsageTable, RedemptionCodesTable,
        )
        migrateCustomerUsageColumns()
        migrateCustomerKeyRoutingGroups()
    }

    /**
     * H2 / Exposed does not auto-migrate columns added after the table was first
     * created. The CustomerUsageTable gained cachedPromptTokens, reasoningTokens,
     * and per-field cost columns in a later release; existing databases need them
     * added on next boot. We use IF NOT EXISTS semantics via try/catch since H2
     * doesn't support `ADD COLUMN IF NOT EXISTS` on older builds.
     */
    private fun migrateCustomerUsageColumns() {
        // Exposed quotes plugin ids that contain '-' and H2 stores the physical name in uppercase.
        // Keep the old underscore table name in the candidate list because some dev databases were
        // touched by the previous broken migration before the real table name was fixed.
        val usageTables = listOf("\"CUSTOMER-PORTAL_USAGE_RECORDS\"", "customer_portal_usage_records")
        val usageColumns = listOf(
            "status INT NOT NULL DEFAULT 200",
            "cached_prompt_tokens BIGINT NOT NULL DEFAULT 0",
            "reasoning_tokens BIGINT NOT NULL DEFAULT 0",
            "input_cost_micros BIGINT NOT NULL DEFAULT 0",
            "output_cost_micros BIGINT NOT NULL DEFAULT 0",
            "cache_write_cost_micros BIGINT NOT NULL DEFAULT 0",
            "cache_read_cost_micros BIGINT NOT NULL DEFAULT 0",
            "cache_hit_rate DOUBLE NULL",
        )
        val widenColumns = listOf(
            "ALTER TABLE \"CUSTOMER-PORTAL_USAGE_RECORDS\" ALTER COLUMN request_id VARCHAR(512)",
            "ALTER TABLE \"CUSTOMER-PORTAL_CREDIT_LEDGER\" ALTER COLUMN ref_id VARCHAR(512)",
        )
        database.transaction {
            usageTables.forEach { table ->
                usageColumns.forEach { column ->
                    execIfPossible("ALTER TABLE $table ADD COLUMN IF NOT EXISTS $column")
                }
            }
            widenColumns.forEach { execIfPossible(it) }
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.execIfPossible(sql: String) {
        var ok = false
        try { exec(sql); ok = true } catch (_: Exception) { /* fallback */ }
        if (!ok && sql.contains("ADD COLUMN IF NOT EXISTS")) {
            try { exec(sql.replace("ADD COLUMN IF NOT EXISTS", "ADD COLUMN")) } catch (_: Exception) { /* already exists or legacy table absent */ }
        }
    }

    private fun migrateCustomerKeyRoutingGroups() {
        database.transaction {
            CustomerKeysTable.selectAll().forEach { row ->
                val current = row[CustomerKeysTable.routingGroupId]
                val normalized = normalizeRoutingGroupId(current)
                if (current != normalized) {
                    CustomerKeysTable.update({ CustomerKeysTable.keyId eq row[CustomerKeysTable.keyId] }) {
                        it[routingGroupId] = normalized
                    }
                }
            }
        }
    }

    // ---- auth ----

    fun register(request: CustomerRegisterRequest): CustomerAuthResponse = database.transaction {
        val email = normalizeEmail(request.email)
        requireValidPassword(request.password)
        requireDisplayName(request.displayName)
        requireUniqueEmail(email)
        val now = Clock.System.now()
        val customerId = nextId("cust")
        val salt = passwordHasher.newSalt()
        CustomersTable.insert {
            it[CustomersTable.customerId] = customerId
            it[CustomersTable.email] = email
            it[passwordHash] = passwordHasher.hash(request.password, salt)
            it[passwordSalt] = salt
            it[displayName] = request.displayName.trim()
            it[oauthProvider] = null
            it[oauthSubject] = null
            it[emailVerified] = false
            it[status] = CustomerStatuses.ACTIVE
            it[lastLoginAt] = now
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = customerId
            it[updatedBy] = customerId
            it[deletedAt] = null
        }
        grantBonus(customerId, LedgerReasons.SIGNUP_BONUS, SIGNUP_BONUS_CREDITS, 0L)
        issueAuth(rowFor(customerId))
    }

    fun login(request: CustomerLoginRequest): CustomerAuthResponse = database.transaction {
        val row = findEmailRow(normalizeEmail(request.email))
        if (!passwordHasher.verify(request.password, row[CustomersTable.passwordSalt], row[CustomersTable.passwordHash])) {
            throw PluginApiException(401, "Invalid credentials")
        }
        if (row[CustomersTable.status] != CustomerStatuses.ACTIVE) {
            throw PluginApiException(403, "Account is locked")
        }
        touchLogin(row[CustomersTable.customerId])
        issueAuth(rowFor(row[CustomersTable.customerId]))
    }

    fun refresh(request: CustomerRefreshRequest): CustomerAuthResponse = database.transaction {
        val hash = sha256(request.refreshToken)
        val row = CustomerSessionsTable.selectAll().where {
            (CustomerSessionsTable.refreshTokenHash eq hash) and
                CustomerSessionsTable.revokedAt.isNull() and
                CustomerSessionsTable.deletedAt.isNull()
        }.firstOrNull() ?: throw PluginApiException(401, "Invalid refresh token")
        if (Clock.System.now() >= row[CustomerSessionsTable.expiresAt]) {
            throw PluginApiException(401, "Refresh token expired")
        }
        revokeSession(row[CustomerSessionsTable.sessionId])
        issueAuth(rowFor(row[CustomerSessionsTable.customerId]))
    }

    fun oauthStub(request: CustomerOAuthStubRequest): CustomerAuthResponse = database.transaction {
        val email = normalizeEmail(request.email)
        val existing = CustomersTable.selectAll().where {
            (CustomersTable.email eq email) and CustomersTable.deletedAt.isNull()
        }.firstOrNull()
        val customerId = if (existing != null) {
            existing[CustomersTable.customerId]
        } else {
            val now = Clock.System.now()
            val cid = nextId("cust")
            CustomersTable.insert {
                it[CustomersTable.customerId] = cid
                it[CustomersTable.email] = email
                it[passwordHash] = ""
                it[passwordSalt] = ""
                it[displayName] = request.displayName.trim().ifBlank { email.substringBefore('@') }
                it[oauthProvider] = request.provider
                it[oauthSubject] = "fake_$cid"
                it[emailVerified] = true
                it[status] = CustomerStatuses.ACTIVE
                it[lastLoginAt] = now
                it[createdAt] = now
                it[updatedAt] = now
                it[createdBy] = cid
                it[updatedBy] = cid
                it[deletedAt] = null
            }
            grantBonus(cid, LedgerReasons.SIGNUP_BONUS, SIGNUP_BONUS_CREDITS, 0L)
            cid
        }
        touchLogin(customerId)
        issueAuth(rowFor(customerId))
    }

    fun me(principal: CustomerPrincipal): CustomerProfile = database.transaction {
        val row = rowFor(principal.customerId)
        CustomerProfile(
            customerId = row[CustomersTable.customerId],
            email = row[CustomersTable.email],
            displayName = row[CustomersTable.displayName],
            emailVerified = row[CustomersTable.emailVerified],
            oauthProvider = row[CustomersTable.oauthProvider],
            balanceCredits = balanceSnapshot(principal.customerId),
            createdAt = row[CustomersTable.createdAt].toString(),
        )
    }

    // ---- keys ----

    fun listKeys(customerId: String): CustomerKeyListResponse = database.transaction {
        val keys = CustomerKeysTable.selectAll()
            .where { (CustomerKeysTable.customerId eq customerId) and CustomerKeysTable.deletedAt.isNull() }
            .orderBy(CustomerKeysTable.createdAt to SortOrder.DESC)
            .map { it.toKeyView() }
        CustomerKeyListResponse(keys, keys.size)
    }

    fun createKey(customerId: String, request: CreateCustomerKeyRequest): CustomerKeyCreatedResponse = database.transaction {
        val now = Clock.System.now()
        val rawKey = "sk-keel-cust-${nextId("key")}-${randomHex(16)}"
        val keyId = nextId("ckey")
        CustomerKeysTable.insert {
            it[CustomerKeysTable.keyId] = keyId
            it[CustomerKeysTable.customerId] = customerId
            it[name] = request.name.trim().ifBlank { throw PluginApiException(400, "name is required") }
            it[prefix] = rawKey.take(24)
            it[secretHash] = sha256(rawKey)
            it[routingGroupId] = normalizeRoutingGroupId(request.routingGroupId)
            it[monthlyBudgetCredits] = request.monthlyBudgetCredits
            it[status] = "active"
            it[lastUsedAt] = null
            it[revokedAt] = null
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = customerId
            it[updatedBy] = customerId
            it[deletedAt] = null
        }
        CustomerKeyCreatedResponse(keyRow(keyId).toKeyView(), rawKey)
    }

    fun getKey(customerId: String, keyId: String): CustomerKeyView = database.transaction {
        val row = keyRow(keyId)
        if (row[CustomerKeysTable.customerId] != customerId) throw PluginApiException(403, "Forbidden")
        row.toKeyView()
    }

    fun updateKey(customerId: String, keyId: String, request: UpdateCustomerKeyRequest): CustomerKeyView = database.transaction {
        val row = keyRow(keyId)
        if (row[CustomerKeysTable.customerId] != customerId) throw PluginApiException(403, "Forbidden")
        CustomerKeysTable.update({ CustomerKeysTable.keyId eq keyId }) {
            request.name?.trim()?.takeIf(String::isNotBlank)?.let { v -> it[name] = v }
            request.routingGroupId?.let { v -> it[routingGroupId] = normalizeRoutingGroupId(v) }
            request.monthlyBudgetCredits?.let { v -> it[monthlyBudgetCredits] = v }
            it[updatedAt] = Clock.System.now()
        }
        keyRow(keyId).toKeyView()
    }

    fun revokeKey(customerId: String, keyId: String): CustomerKeyView = database.transaction {
        val row = keyRow(keyId)
        if (row[CustomerKeysTable.customerId] != customerId) throw PluginApiException(403, "Forbidden")
        val now = Clock.System.now()
        CustomerKeysTable.update({ CustomerKeysTable.keyId eq keyId }) {
            it[status] = "revoked"
            it[revokedAt] = now
            it[updatedAt] = now
        }
        keyRow(keyId).toKeyView()
    }

    fun deleteKey(customerId: String, keyId: String): CustomerKeyView = database.transaction {
        val row = keyRow(keyId)
        if (row[CustomerKeysTable.customerId] != customerId) throw PluginApiException(403, "Forbidden")
        val now = Clock.System.now()
        CustomerKeysTable.update({ CustomerKeysTable.keyId eq keyId }) {
            it[status] = "revoked"
            it[revokedAt] = now
            it[updatedAt] = now
            it[deletedAt] = now
        }
        row.toKeyView().copy(
            status = "revoked",
            revokedAt = now.toString(),
        )
    }

    // ---- credits ----

    fun customerBalance(customerId: String): CreditBalanceResponse = database.transaction {
        CreditBalanceResponse(balanceSnapshot(customerId))
    }

    fun customerLedger(customerId: String, cursor: String?, limit: Int): CreditLedgerResponse = database.transaction {
        val pageSize = limit.coerceIn(1, 200)
        val baseQuery = CreditLedgerTable.selectAll().where { CreditLedgerTable.customerId eq customerId }
        val total = baseQuery.count().toInt()
        val pageCursor = decodePageCursor(cursor)
        val filtered = if (pageCursor != null) {
            baseQuery.andWhere {
                (CreditLedgerTable.createdAt less pageCursor.createdAt) or
                    ((CreditLedgerTable.createdAt eq pageCursor.createdAt) and (CreditLedgerTable.entryId less pageCursor.recordId))
            }
        } else {
            baseQuery
        }
        val pageRows = filtered
            .orderBy(CreditLedgerTable.createdAt to SortOrder.DESC, CreditLedgerTable.entryId to SortOrder.DESC)
            .limit(pageSize + 1)
            .map { it.toLedgerEntry() }
        val entries = pageRows.take(pageSize)
        val nextCursor = entries.lastOrNull()?.takeIf { pageRows.size > pageSize }?.let { entry ->
            encodePageCursor(entry.createdAt, entry.entryId)
        }
        CreditLedgerResponse(entries = entries, total = total, nextCursor = nextCursor)
    }

    fun redeemCode(customerId: String, request: RedeemCodeRequest): RedeemCodeResponse = database.transaction {
        val code = request.code.trim().uppercase()
        val row = RedemptionCodesTable.selectAll().where { RedemptionCodesTable.code eq code }.firstOrNull()
            ?: throw PluginApiException(404, "Code not found")
        when (row[RedemptionCodesTable.status]) {
            RedemptionCodeStatuses.REDEEMED -> throw PluginApiException(409, "Code already redeemed")
            RedemptionCodeStatuses.REVOKED -> throw PluginApiException(410, "Code revoked")
            RedemptionCodeStatuses.EXPIRED -> throw PluginApiException(410, "Code expired")
        }
        if (row[RedemptionCodesTable.expiresAt] != null && Clock.System.now() >= row[RedemptionCodesTable.expiresAt]!!) {
            RedemptionCodesTable.update({ RedemptionCodesTable.code eq code }) { it[status] = RedemptionCodeStatuses.EXPIRED }
            throw PluginApiException(410, "Code expired")
        }
        val amount = row[RedemptionCodesTable.faceValueCredits]
        val newBalance = grantBonus(customerId, LedgerReasons.REDEMPTION, amount, 0L)
        val now = Clock.System.now()
        RedemptionCodesTable.update({ RedemptionCodesTable.code eq code }) {
            it[redeemedByCustomerId] = customerId
            it[redeemedAt] = now
            it[status] = RedemptionCodeStatuses.REDEEMED
        }
        RedeemCodeResponse(code, amount, newBalance)
    }

    fun customerUsage(customerId: String, cursor: String?, limit: Int): CustomerUsageListResponse = database.transaction {
        try {
            val pageSize = limit.coerceIn(1, 200)
            val baseQuery = CustomerUsageTable.selectAll().where { CustomerUsageTable.customerId eq customerId }
            val total = baseQuery.count().toInt()
            val pageCursor = decodePageCursor(cursor)
            val filtered = if (pageCursor != null) {
                baseQuery.andWhere {
                    (CustomerUsageTable.createdAt less pageCursor.createdAt) or
                        ((CustomerUsageTable.createdAt eq pageCursor.createdAt) and (CustomerUsageTable.recordId less pageCursor.recordId))
                }
            } else {
                baseQuery
            }
            val pageRows = filtered
                .orderBy(CustomerUsageTable.createdAt to SortOrder.DESC, CustomerUsageTable.recordId to SortOrder.DESC)
                .limit(pageSize + 1)
                .map { it.toUsageView() }
            val records = pageRows.take(pageSize)
            val nextCursor = records.lastOrNull()?.takeIf { pageRows.size > pageSize }?.let { record ->
                encodePageCursor(record.createdAt, record.recordId)
            }
            CustomerUsageListResponse(records = records, total = total, nextCursor = nextCursor)
        } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            // Schema drift: a column the code expects is missing. Try to migrate on the fly
            // and retry once. The most common cause is a new column being added after the
            // H2 file was already created. We don't want a single bad column to break the
            // entire usage list, so we fall back to a minimal projection of just the
            // original (pre-migration) columns.
            val root = e.cause ?: e
            if (root is java.sql.SQLException && (root.message?.contains("not found", ignoreCase = true) == true)) {
                val msg = root.message ?: ""
                val colMatch = Regex("""CUSTOMER-PORTAL_USAGE_RECORDS\.([A-Z_0-9]+)""").find(msg)
                val missing = colMatch?.groupValues?.getOrNull(1)
                if (missing != null) {
                    try {
                        val snaked = missing.lowercase().split("_").mapIndexed { i, s -> if (i == 0) s else s }.joinToString("_")
                        val columnType = if (snaked == "status") "INT NOT NULL DEFAULT 200" else "BIGINT NOT NULL DEFAULT 0"
                        database.transaction { exec("ALTER TABLE customer_portal_usage_records ADD COLUMN IF NOT EXISTS $snaked $columnType") }
                    } catch (_: Exception) { /* best-effort */ }
                    val total = CustomerUsageTable.selectAll().where { CustomerUsageTable.customerId eq customerId }.count().toInt()
                    // Retry with minimal projection
                    val fallbackQuery = CustomerUsageTable
                        .select(
                            CustomerUsageTable.recordId,
                            CustomerUsageTable.keyId,
                            CustomerUsageTable.model,
                            CustomerUsageTable.groupId,
                            CustomerUsageTable.providerId,
                            CustomerUsageTable.wireProtocol,
                            CustomerUsageTable.status,
                            CustomerUsageTable.inputTokens,
                            CustomerUsageTable.outputTokens,
                            CustomerUsageTable.cacheReadInputTokens,
                            CustomerUsageTable.cacheCreationInputTokens,
                            CustomerUsageTable.creditCost,
                            CustomerUsageTable.usdMicrosCost,
                            CustomerUsageTable.requestId,
                            CustomerUsageTable.createdAt,
                        )
                        .where { CustomerUsageTable.customerId eq customerId }
                    val rows = fallbackQuery.orderBy(CustomerUsageTable.createdAt to SortOrder.DESC)
                        .limit(limit.coerceIn(1, 200))
                        .map { row ->
                            CustomerUsageView(
                                recordId = row[CustomerUsageTable.recordId],
                                keyId = row[CustomerUsageTable.keyId],
                                model = row[CustomerUsageTable.model],
                                groupId = row[CustomerUsageTable.groupId],
                                providerId = row[CustomerUsageTable.providerId],
                                wireProtocol = row[CustomerUsageTable.wireProtocol],
                                status = row[CustomerUsageTable.status],
                                inputTokens = row[CustomerUsageTable.inputTokens],
                                outputTokens = row[CustomerUsageTable.outputTokens],
                                cacheReadInputTokens = row[CustomerUsageTable.cacheReadInputTokens],
                                cacheCreationInputTokens = row[CustomerUsageTable.cacheCreationInputTokens],
                                cachedPromptTokens = 0L,
                                reasoningTokens = 0L,
                                creditCost = row[CustomerUsageTable.creditCost],
                                usdMicrosCost = row[CustomerUsageTable.usdMicrosCost],
                                inputCostMicros = 0L,
                                outputCostMicros = 0L,
                                cacheWriteCostMicros = 0L,
                                cacheReadCostMicros = 0L,
                                cacheHitRate = null,
                                requestId = row[CustomerUsageTable.requestId],
                                createdAt = row[CustomerUsageTable.createdAt].toString(),
                            )
                        }
                    return@transaction CustomerUsageListResponse(records = rows, total = total, nextCursor = null)
                }
            }
            throw e
        }
    }

    // ---- admin (B-end) ----

    fun createRedemptionCode(request: CreateRedemptionCodeRequest, actorId: String): RedemptionCodeView = database.transaction {
        val code = request.code?.trim()?.uppercase()?.takeIf(String::isNotBlank) ?: generateRedemptionCode()
        requirePositiveCredits(request.faceValueCredits)
        if (RedemptionCodesTable.selectAll().where { RedemptionCodesTable.code eq code }.count() > 0L) {
            throw PluginApiException(409, "Code already exists")
        }
        val now = Clock.System.now()
        RedemptionCodesTable.insert {
            it[RedemptionCodesTable.code] = code
            it[faceValueCredits] = request.faceValueCredits
            it[createdByAdminId] = actorId
            it[redeemedByCustomerId] = null
            it[redeemedAt] = null
            it[expiresAt] = request.expiresInDays?.let { days -> now.plus(days.coerceAtLeast(1).days) }
            it[status] = RedemptionCodeStatuses.ACTIVE
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = actorId
            it[updatedBy] = actorId
            it[deletedAt] = null
        }
        codeRow(code).toCodeView()
    }

    fun listRedemptionCodes(): RedemptionCodeListResponse = database.transaction {
        val codes = RedemptionCodesTable.selectAll()
            .orderBy(RedemptionCodesTable.createdAt to SortOrder.DESC)
            .map { it.toCodeView() }
        RedemptionCodeListResponse(codes, codes.size)
    }

    fun revokeRedemptionCode(code: String): RedemptionCodeView = database.transaction {
        val row = codeRow(code)
        val now = Clock.System.now()
        RedemptionCodesTable.update({ RedemptionCodesTable.code eq code }) {
            it[status] = RedemptionCodeStatuses.REVOKED
            it[updatedAt] = now
        }
        codeRow(code).toCodeView()
    }

    /** Balance snapshot for gateway pre-check. Returns latest known balance or 0. */
    override suspend fun snapshotBalance(customerId: String): Long = database.suspendTransaction {
        balanceSnapshot(customerId)
    }

    /**
     * Atomic credit charge. Runs inside a single H2 transaction:
     * read latest balance → check >= 0 → write ledger row → write usage row.
     */
    override suspend fun chargeForUsage(
        customerId: String,
        keyId: String,
        usageCreditCost: Long,
        usageUsdMicrosCost: Long,
        usageRow: CustomerUsageRow,
    ): ChargeResult = database.suspendTransaction {
        // Re-read balance inside the tx to avoid stale snapshots.
        val balanceBefore = balanceSnapshot(customerId)
        val balanceAfter = balanceBefore - usageCreditCost
        val now = Clock.System.now()
        CreditLedgerTable.insert {
            it[entryId] = nextId("cle")
            it[CreditLedgerTable.customerId] = customerId
            it[deltaCredits] = -usageCreditCost
            it[reason] = LedgerReasons.USAGE
            it[refId] = usageRow.requestId
            it[balanceAfterCredits] = balanceAfter
            it[usdMicrosAtTime] = usageUsdMicrosCost
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = customerId
            it[updatedBy] = customerId
            it[deletedAt] = null
        }
        CustomerUsageTable.insert {
            it[recordId] = nextId("cur")
            it[CustomerUsageTable.customerId] = customerId
            it[CustomerUsageTable.keyId] = keyId
            it[model] = usageRow.model
            it[groupId] = usageRow.groupId
            it[providerId] = usageRow.providerId
            it[wireProtocol] = usageRow.wireProtocol
            it[status] = usageRow.status
            it[inputTokens] = usageRow.inputTokens
            it[outputTokens] = usageRow.outputTokens
            it[cacheReadInputTokens] = usageRow.cacheReadInputTokens
            it[cacheCreationInputTokens] = usageRow.cacheCreationInputTokens
            it[cachedPromptTokens] = usageRow.cachedPromptTokens
            it[reasoningTokens] = usageRow.reasoningTokens
            it[CustomerUsageTable.creditCost] = usageCreditCost
            it[CustomerUsageTable.usdMicrosCost] = usageUsdMicrosCost
            it[inputCostMicros] = usageRow.inputCostMicros
            it[outputCostMicros] = usageRow.outputCostMicros
            it[cacheWriteCostMicros] = usageRow.cacheWriteCostMicros
            it[cacheReadCostMicros] = usageRow.cacheReadCostMicros
            it[CustomerUsageTable.cacheHitRate] = usageRow.cacheHitRate
            it[requestId] = usageRow.requestId
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = customerId
            it[updatedBy] = customerId
            it[deletedAt] = null
        }
        ChargeResult.Ok(balanceAfter)
    }

    override suspend fun verifyCustomerKey(rawKey: String): VerifiedCustomerKey? = database.suspendTransaction {
        val hash = sha256(rawKey)
        CustomerKeysTable.selectAll().where {
            (CustomerKeysTable.secretHash eq hash) and CustomerKeysTable.deletedAt.isNull()
        }.firstOrNull()?.let {
            if (it[CustomerKeysTable.status] != "active") return@suspendTransaction null
            VerifiedCustomerKey(
                customerId = it[CustomerKeysTable.customerId],
                keyId = it[CustomerKeysTable.keyId],
                routingGroupId = normalizeRoutingGroupId(it[CustomerKeysTable.routingGroupId]),
                monthlyBudgetCredits = it[CustomerKeysTable.monthlyBudgetCredits],
                status = it[CustomerKeysTable.status],
            )
        }
    }

    // ---- CustomerDirectory ----

    override suspend fun listAll(): List<CustomerSummary> = database.suspendTransaction {
        CustomersTable.selectAll().where { CustomersTable.deletedAt.isNull() }
            .orderBy(CustomersTable.createdAt to SortOrder.DESC)
            .map { row ->
                CustomerSummary(
                    customerId = row[CustomersTable.customerId],
                    email = row[CustomersTable.email],
                    displayName = row[CustomersTable.displayName],
                    status = row[CustomersTable.status],
                    balanceCredits = balanceSnapshot(row[CustomersTable.customerId]),
                    totalKeys = CustomerKeysTable.selectAll().where {
                        (CustomerKeysTable.customerId eq row[CustomersTable.customerId]) and CustomerKeysTable.deletedAt.isNull()
                    }.count().toInt(),
                    createdAt = row[CustomersTable.createdAt].toString(),
                )
            }
    }

    fun adminCustomerDetail(customerId: String): CustomerAdminDetailView = database.transaction {
        val row = rowFor(customerId)
        CustomerAdminDetailView(
            customerId = row[CustomersTable.customerId],
            email = row[CustomersTable.email],
            displayName = row[CustomersTable.displayName],
            status = row[CustomersTable.status],
            balanceCredits = balanceSnapshot(customerId),
            totalKeys = CustomerKeysTable.selectAll().where {
                (CustomerKeysTable.customerId eq customerId) and CustomerKeysTable.deletedAt.isNull()
            }.count().toInt(),
            createdAt = row[CustomersTable.createdAt].toString(),
            keys = CustomerKeysTable.selectAll()
                .where { (CustomerKeysTable.customerId eq customerId) and CustomerKeysTable.deletedAt.isNull() }
                .orderBy(CustomerKeysTable.createdAt to SortOrder.DESC)
                .map { it.toKeyView() }
        )
    }

    fun updateCustomer(customerId: String, request: UpdateCustomerRequest): CustomerAdminDetailView = database.transaction {
        rowFor(customerId)
        CustomersTable.update({ CustomersTable.customerId eq customerId }) {
            request.displayName?.trim()?.takeIf(String::isNotBlank)?.let { value -> it[displayName] = value }
            request.status?.trim()?.lowercase()?.let { value ->
                val normalized = when (value) {
                    "suspended" -> CustomerStatuses.LOCKED
                    else -> value
                }
                if (normalized !in setOf(CustomerStatuses.ACTIVE, CustomerStatuses.LOCKED, CustomerStatuses.DELETED)) {
                    throw PluginApiException(400, "Invalid status $value")
                }
                it[status] = normalized
            }
            it[updatedAt] = Clock.System.now()
        }
        adminCustomerDetail(customerId)
    }

    fun softDeleteCustomer(customerId: String): CustomerAdminDetailView = database.transaction {
        val now = Clock.System.now()
        rowFor(customerId)
        CustomersTable.update({ CustomersTable.customerId eq customerId }) {
            it[status] = CustomerStatuses.DELETED
            it[updatedAt] = now
            it[deletedAt] = now
        }
        CustomerKeysTable.update({ (CustomerKeysTable.customerId eq customerId) and CustomerKeysTable.deletedAt.isNull() }) {
            it[status] = "revoked"
            it[revokedAt] = now
            it[updatedAt] = now
            it[deletedAt] = now
        }
        CustomerAdminDetailView(
            customerId = customerId,
            email = "",
            displayName = "",
            status = CustomerStatuses.DELETED,
            balanceCredits = balanceSnapshot(customerId),
            totalKeys = 0,
            createdAt = now.toString(),
            keys = emptyList()
        )
    }

    fun adjustCustomerCredits(customerId: String, request: AdjustCustomerCreditsRequest): CustomerAdminDetailView = database.transaction {
        if (request.deltaCredits == 0L) throw PluginApiException(400, "deltaCredits must not be 0")
        rowFor(customerId)
        grantBonus(customerId, request.reason.ifBlank { "admin_adjustment" }, request.deltaCredits, request.usdMicrosAtTime)
        adminCustomerDetail(customerId)
    }

    fun adminRevokeCustomerKey(customerId: String, keyId: String): CustomerKeyView = database.transaction {
        val row = keyRow(keyId)
        if (row[CustomerKeysTable.customerId] != customerId) throw PluginApiException(403, "Forbidden")
        val now = Clock.System.now()
        CustomerKeysTable.update({ CustomerKeysTable.keyId eq keyId }) {
            it[status] = "revoked"
            it[revokedAt] = now
            it[updatedAt] = now
        }
        keyRow(keyId).toKeyView()
    }

    override suspend fun count(): Long = database.suspendTransaction {
        CustomersTable.selectAll().where { CustomersTable.deletedAt.isNull() }.count()
    }

    // ---- private helpers ----

    private fun balanceSnapshot(customerId: String): Long {
        return CreditLedgerTable.selectAll()
            .where { CreditLedgerTable.customerId eq customerId }
            .orderBy(CreditLedgerTable.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(CreditLedgerTable.balanceAfterCredits) ?: 0L
    }

    private fun grantBonus(customerId: String, reason: String, credits: Long, usdMicros: Long): Long {
        val now = Clock.System.now()
        val before = balanceSnapshot(customerId)
        val after = before + credits
        CreditLedgerTable.insert {
            it[entryId] = nextId("cle")
            it[CreditLedgerTable.customerId] = customerId
            it[deltaCredits] = credits
            it[CreditLedgerTable.reason] = reason
            it[refId] = null
            it[balanceAfterCredits] = after
            it[usdMicrosAtTime] = usdMicros
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = customerId
            it[updatedBy] = customerId
            it[deletedAt] = null
        }
        return after
    }

    private fun issueAuth(row: ResultRow): CustomerAuthResponse {
        val principal = CustomerPrincipal(
            customerId = row[CustomersTable.customerId],
            email = row[CustomersTable.email],
        )
        val (accessToken, expiresIn) = jwtService.issue(principal)
        val refreshToken = randomId(60)
        val now = Clock.System.now()
        CustomerSessionsTable.insert {
            it[sessionId] = nextId("cs")
            it[customerId] = principal.customerId
            it[refreshTokenHash] = sha256(refreshToken)
            it[expiresAt] = now.plus(14.days)
            it[revokedAt] = null
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = principal.customerId
            it[updatedBy] = principal.customerId
            it[deletedAt] = null
        }
        return CustomerAuthResponse(
            customerId = principal.customerId,
            email = principal.email,
            displayName = row[CustomersTable.displayName],
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = expiresIn,
        )
    }

    private fun revokeSession(sessionId: String) {
        CustomerSessionsTable.update({ CustomerSessionsTable.sessionId eq sessionId }) {
            it[revokedAt] = Clock.System.now()
        }
    }

    private fun touchLogin(customerId: String) {
        CustomersTable.update({ CustomersTable.customerId eq customerId }) {
            it[lastLoginAt] = Clock.System.now()
        }
    }

    // --- row readers ---

    private fun rowFor(customerId: String): ResultRow = CustomersTable.selectAll()
        .where { (CustomersTable.customerId eq customerId) and CustomersTable.deletedAt.isNull() }
        .firstOrNull() ?: throw PluginApiException(404, "Customer not found")

    private fun findEmailRow(email: String): ResultRow = CustomersTable.selectAll()
        .where { (CustomersTable.email eq email) and CustomersTable.deletedAt.isNull() }
        .firstOrNull() ?: throw PluginApiException(401, "Invalid credentials")

    private fun keyRow(keyId: String): ResultRow = CustomerKeysTable.selectAll()
        .where { (CustomerKeysTable.keyId eq keyId) and CustomerKeysTable.deletedAt.isNull() }
        .firstOrNull() ?: throw PluginApiException(404, "Key not found")

    private fun codeRow(code: String): ResultRow = RedemptionCodesTable.selectAll()
        .where { RedemptionCodesTable.code eq code }
        .firstOrNull() ?: throw PluginApiException(404, "Code not found")

    // --- validators ---

    private fun requireUniqueEmail(email: String) {
        if (CustomersTable.selectAll().where { (CustomersTable.email eq email) and CustomersTable.deletedAt.isNull() }.count() > 0L) {
            throw PluginApiException(409, "Email already registered")
        }
    }

    private fun requireValidPassword(password: String) {
        if (password.length < 6) throw PluginApiException(400, "Password must be at least 6 characters")
    }

    private fun requireDisplayName(name: String) {
        if (name.trim().isBlank()) throw PluginApiException(400, "displayName is required")
    }

    private fun requirePositiveCredits(credits: Long) {
        if (credits <= 0) throw PluginApiException(400, "faceValueCredits must be positive")
    }

    // --- id helpers ---

    private fun nextId(prefix: String): String = "$prefix-${randomHex(8)}"

    private fun randomId(length: Int = 32): String {
        val bytes = ByteArray(length / 2)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    private fun randomHex(len: Int): String {
        val bytes = ByteArray(len)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    private fun generateRedemptionCode(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" // no 0/O/1/I
        val chars = CharArray(16) { alphabet[random.nextInt(alphabet.length)] }
        return String(chars)
    }

    private data class PageCursor(
        val createdAt: Instant,
        val recordId: String,
    )

    private fun encodePageCursor(createdAt: String, recordId: String): String =
        encodePageCursor(Instant.parse(createdAt), recordId)

    private fun encodePageCursor(createdAt: Instant, recordId: String): String =
        "${createdAt.toEpochMilliseconds()}|$recordId"

    private fun decodePageCursor(cursor: String?): PageCursor? {
        val raw = cursor?.trim().orEmpty()
        if (raw.isBlank()) return null
        val parts = raw.split('|', limit = 2)
        if (parts.size != 2) return null
        val createdAt = parts[0].toLongOrNull()?.let(Instant::fromEpochMilliseconds) ?: return null
        return PageCursor(createdAt = createdAt, recordId = parts[1])
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    private fun normalizeRoutingGroupId(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return DEFAULT_GROUP_ID
        return when (raw.lowercase()) {
            "default-chain",
            "openai-chat-chain",
            "openai-responses-chain",
            "anthropic-chain",
            "claude-default-chain" -> DEFAULT_GROUP_ID
            else -> raw.lowercase()
                .replace(Regex("[^a-z0-9_-]+"), "-")
                .trim('-')
                .ifBlank { DEFAULT_GROUP_ID }
        }
    }
}

// --- Extensions: row → view ---

private fun ResultRow.toKeyView(): CustomerKeyView = CustomerKeyView(
    keyId = this[CustomerKeysTable.keyId],
    customerId = this[CustomerKeysTable.customerId],
    name = this[CustomerKeysTable.name],
    prefix = this[CustomerKeysTable.prefix],
    routingGroupId = this[CustomerKeysTable.routingGroupId],
    monthlyBudgetCredits = this[CustomerKeysTable.monthlyBudgetCredits],
    status = this[CustomerKeysTable.status],
    lastUsedAt = this[CustomerKeysTable.lastUsedAt]?.toString(),
    revokedAt = this[CustomerKeysTable.revokedAt]?.toString(),
    createdAt = this[CustomerKeysTable.createdAt].toString(),
)

private fun ResultRow.toLedgerEntry(): CreditLedgerEntry = CreditLedgerEntry(
    entryId = this[CreditLedgerTable.entryId],
    deltaCredits = this[CreditLedgerTable.deltaCredits],
    reason = this[CreditLedgerTable.reason],
    refId = this[CreditLedgerTable.refId],
    balanceAfterCredits = this[CreditLedgerTable.balanceAfterCredits],
    usdMicrosAtTime = this[CreditLedgerTable.usdMicrosAtTime],
    createdAt = this[CreditLedgerTable.createdAt].toString(),
)

/**
 * Read a column, swallowing SQL errors (e.g. when a column was added in a later
 * release and the existing H2 file hasn't been migrated yet). Falls back to
 * the supplied default so the call site gets a sensible value instead of a 500.
 */
private fun <T> ResultRow.safe(column: org.jetbrains.exposed.sql.Column<T>, default: T): T = try {
    this[column]
} catch (_: Exception) {
    default
}

private fun ResultRow.safeOrNull(column: org.jetbrains.exposed.sql.Column<Double?>): Double? = try {
    this[column]
} catch (_: Exception) {
    null
}

private fun ResultRow.toUsageView(): CustomerUsageView = CustomerUsageView(
    recordId = this[CustomerUsageTable.recordId],
    keyId = this[CustomerUsageTable.keyId],
    model = this[CustomerUsageTable.model],
    groupId = this[CustomerUsageTable.groupId],
    providerId = this[CustomerUsageTable.providerId],
    wireProtocol = this[CustomerUsageTable.wireProtocol],
    status = this[CustomerUsageTable.status],
    inputTokens = this[CustomerUsageTable.inputTokens],
    outputTokens = this[CustomerUsageTable.outputTokens],
    cacheReadInputTokens = this[CustomerUsageTable.cacheReadInputTokens],
    cacheCreationInputTokens = this[CustomerUsageTable.cacheCreationInputTokens],
    cachedPromptTokens = safe(CustomerUsageTable.cachedPromptTokens, 0L),
    reasoningTokens = safe(CustomerUsageTable.reasoningTokens, 0L),
    creditCost = this[CustomerUsageTable.creditCost],
    usdMicrosCost = this[CustomerUsageTable.usdMicrosCost],
    inputCostMicros = safe(CustomerUsageTable.inputCostMicros, 0L),
    outputCostMicros = safe(CustomerUsageTable.outputCostMicros, 0L),
    cacheWriteCostMicros = safe(CustomerUsageTable.cacheWriteCostMicros, 0L),
    cacheReadCostMicros = safe(CustomerUsageTable.cacheReadCostMicros, 0L),
    cacheHitRate = safeOrNull(CustomerUsageTable.cacheHitRate),
    requestId = this[CustomerUsageTable.requestId],
    createdAt = this[CustomerUsageTable.createdAt].toString(),
)

private fun ResultRow.toCodeView(): RedemptionCodeView = RedemptionCodeView(
    code = this[RedemptionCodesTable.code],
    faceValueCredits = this[RedemptionCodesTable.faceValueCredits],
    createdByAdminId = this[RedemptionCodesTable.createdByAdminId],
    redeemedByCustomerId = this[RedemptionCodesTable.redeemedByCustomerId],
    redeemedAt = this[RedemptionCodesTable.redeemedAt]?.toString(),
    expiresAt = this[RedemptionCodesTable.expiresAt]?.toString(),
    status = this[RedemptionCodesTable.status],
    createdAt = this[RedemptionCodesTable.createdAt].toString(),
)
