package com.keel.samples.aigateway.account

import com.keel.contract.ai.AiPrincipal
import com.keel.contract.ai.JwtPrincipalVerifier
import com.keel.contract.ai.UserDirectory
import com.keel.contract.ai.UserGroupSummary
import com.keel.contract.ai.UserSummary
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.PluginApiException
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.time.Duration.Companion.days

class AccountRepository(
    private val database: KeelDatabase,
    private val passwordHasher: PasswordHasher,
    private val jwtTokenService: JwtTokenService,
    private val random: SecureRandom = SecureRandom()
) : UserDirectory, JwtPrincipalVerifier {
    fun initializeSchema() {
        database.createTables(AccountUserGroupsTable, AccountUsersTable, AccountRefreshTokensTable)
    }

    fun seedDefaults() = database.transaction {
        val now = Clock.System.now()
        if (AccountUserGroupsTable.selectAll().count() == 0L) {
            seedGroup("free", "Free", 1.0, 30, 30_000, 10.0, now)
            seedGroup("pro", "Pro", 1.0, 300, 300_000, 100.0, now)
            seedGroup("enterprise", "Enterprise", 0.9, null, null, 1_000.0, now)
        }
        if (AccountUsersTable.selectAll().count() == 0L) {
            seedUser("usr-admin", "admin@example.com", "admin123", "Admin", AccountRoles.ADMIN, "enterprise", now)
            seedUser("usr-demo", "user@example.com", "user123", "Demo User", AccountRoles.USER, "free", now)
        }
    }

    fun register(request: RegisterRequest): AuthResponse = database.transaction {
        val email = normalizeEmail(request.email)
        val displayName = request.displayName.trim().ifBlank { throw PluginApiException(400, "displayName is required") }
        requirePassword(request.password)
        if (AccountUsersTable.selectAll().where { AccountUsersTable.email eq email }.count() > 0L) {
            throw PluginApiException(409, "Email already registered")
        }
        val now = Clock.System.now()
        val userId = nextId("usr")
        AccountUsersTable.insert {
            it[AccountUsersTable.userId] = userId
            it[AccountUsersTable.email] = email
            it[passwordHash] = passwordHasher.hash(request.password)
            it[AccountUsersTable.displayName] = displayName
            it[role] = AccountRoles.USER
            it[groupId] = "free"
            it[status] = AccountStatuses.ACTIVE
            it[lastLoginAt] = now
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = userId
            it[updatedBy] = userId
            it[deletedAt] = null
        }
        issueAuth(findUserRow(userId))
    }

    fun login(request: LoginRequest): AuthResponse = database.transaction {
        val row = AccountUsersTable.selectAll()
            .where { (AccountUsersTable.email eq normalizeEmail(request.email)) and AccountUsersTable.deletedAt.isNull() }
            .firstOrNull()
            ?: throw PluginApiException(401, "Invalid credentials")
        if (row[AccountUsersTable.status] != AccountStatuses.ACTIVE) {
            throw PluginApiException(403, "Account is not active")
        }
        if (!passwordHasher.verify(request.password, row[AccountUsersTable.passwordHash])) {
            throw PluginApiException(401, "Invalid credentials")
        }
        AccountUsersTable.update({ AccountUsersTable.userId eq row[AccountUsersTable.userId] }) {
            it[lastLoginAt] = Clock.System.now()
        }
        issueAuth(findUserRow(row[AccountUsersTable.userId]))
    }

    fun refresh(request: RefreshRequest): AuthResponse = database.transaction {
        val tokenHash = hashToken(request.refreshToken)
        val tokenRow = AccountRefreshTokensTable.selectAll()
            .where {
                (AccountRefreshTokensTable.tokenHash eq tokenHash) and
                    AccountRefreshTokensTable.revokedAt.isNull() and
                    AccountRefreshTokensTable.deletedAt.isNull()
            }
            .firstOrNull()
            ?: throw PluginApiException(401, "Invalid refresh token")
        if (Clock.System.now() >= tokenRow[AccountRefreshTokensTable.expiresAt]) {
            throw PluginApiException(401, "Refresh token expired")
        }
        val user = findUserRow(tokenRow[AccountRefreshTokensTable.userId])
        if (user[AccountUsersTable.status] != AccountStatuses.ACTIVE) {
            throw PluginApiException(403, "Account is not active")
        }
        issueAuth(user)
    }

    fun me(principal: AccountPrincipal): AccountUserView = database.transaction {
        findUserRow(principal.userId).toView()
    }

    fun listUsers(): AccountUserListResponse = database.transaction {
        val users = AccountUsersTable.selectAll()
            .where { AccountUsersTable.deletedAt.isNull() }
            .orderBy(AccountUsersTable.createdAt to SortOrder.ASC)
            .map { it.toView() }
        AccountUserListResponse(users, users.size)
    }

    fun getUser(userId: String): AccountUserView = database.transaction {
        findUserRow(userId).toView()
    }

    fun updateUser(userId: String, request: UpdateUserRequest, actor: AccountPrincipal): AccountUserView = database.transaction {
        findUserRow(userId)
        val now = Clock.System.now()
        AccountUsersTable.update({ AccountUsersTable.userId eq userId }) {
            request.displayName?.trim()?.takeIf(String::isNotBlank)?.let { value -> it[displayName] = value }
            request.role?.let { value -> it[role] = normalizeRole(value) }
            request.groupId?.let { value ->
                findGroupRow(value)
                it[groupId] = value
            }
            request.status?.let { value -> it[status] = normalizeStatus(value) }
            it[updatedAt] = now
            it[updatedBy] = actor.userId
        }
        findUserRow(userId).toView()
    }

    fun updateStatus(userId: String, status: String, actor: AccountPrincipal): AccountUserView = database.transaction {
        findUserRow(userId)
        val now = Clock.System.now()
        AccountUsersTable.update({ AccountUsersTable.userId eq userId }) {
            it[AccountUsersTable.status] = normalizeStatus(status)
            it[updatedAt] = now
            it[updatedBy] = actor.userId
        }
        findUserRow(userId).toView()
    }

    fun listGroups(): AccountGroupListResponse = database.transaction {
        val groups = AccountUserGroupsTable.selectAll()
            .where { AccountUserGroupsTable.deletedAt.isNull() }
            .orderBy(AccountUserGroupsTable.groupId to SortOrder.ASC)
            .map { it.toGroupView() }
        AccountGroupListResponse(groups, groups.size)
    }

    fun createGroup(request: CreateGroupRequest, actor: AccountPrincipal): AccountGroupView = database.transaction {
        val groupId = request.groupId.trim().ifBlank { throw PluginApiException(400, "groupId is required") }
        if (AccountUserGroupsTable.selectAll().where { AccountUserGroupsTable.groupId eq groupId }.count() > 0L) {
            throw PluginApiException(409, "Group already exists")
        }
        val now = Clock.System.now()
        AccountUserGroupsTable.insert {
            it[AccountUserGroupsTable.groupId] = groupId
            it[name] = request.name.trim().ifBlank { throw PluginApiException(400, "name is required") }
            it[costMultiplier] = request.costMultiplier
            it[defaultRpm] = request.defaultRpm
            it[defaultTpm] = request.defaultTpm
            it[defaultBudgetUsd] = request.defaultBudgetUsd
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = actor.userId
            it[updatedBy] = actor.userId
            it[deletedAt] = null
        }
        findGroupRow(groupId).toGroupView()
    }

    override suspend fun findById(userId: String): UserSummary? = database.suspendTransaction {
        AccountUsersTable.selectAll()
            .where { (AccountUsersTable.userId eq userId) and AccountUsersTable.deletedAt.isNull() }
            .firstOrNull()
            ?.let {
                UserSummary(
                    userId = it[AccountUsersTable.userId],
                    email = it[AccountUsersTable.email],
                    displayName = it[AccountUsersTable.displayName],
                    role = it[AccountUsersTable.role],
                    groupId = it[AccountUsersTable.groupId],
                    status = it[AccountUsersTable.status]
                )
            }
    }

    override suspend fun findGroup(groupId: String): UserGroupSummary? = database.suspendTransaction {
        AccountUserGroupsTable.selectAll()
            .where { (AccountUserGroupsTable.groupId eq groupId) and AccountUserGroupsTable.deletedAt.isNull() }
            .firstOrNull()
            ?.let {
                UserGroupSummary(
                    groupId = it[AccountUserGroupsTable.groupId],
                    name = it[AccountUserGroupsTable.name],
                    costMultiplier = it[AccountUserGroupsTable.costMultiplier],
                    defaultRpm = it[AccountUserGroupsTable.defaultRpm],
                    defaultTpm = it[AccountUserGroupsTable.defaultTpm],
                    defaultBudgetUsd = it[AccountUserGroupsTable.defaultBudgetUsd]
                )
            }
    }

    fun verifyJwt(authHeader: String?): AccountPrincipal? {
        val token = authHeader?.removePrefix("Bearer ")?.takeIf { it != authHeader && it.isNotBlank() }
            ?: return null
        return jwtTokenService.verify(token)
    }

    override suspend fun verifyAuthorizationHeader(authHeader: String?): AiPrincipal? {
        return verifyJwt(authHeader)?.let {
            AiPrincipal(
                userId = it.userId,
                email = it.email,
                role = it.role,
                groupId = it.groupId
            )
        }
    }

    private fun issueAuth(row: ResultRow): AuthResponse {
        val principal = AccountPrincipal(
            userId = row[AccountUsersTable.userId],
            email = row[AccountUsersTable.email],
            role = row[AccountUsersTable.role],
            groupId = row[AccountUsersTable.groupId]
        )
        val refreshToken = nextId("rft") + nextId("tok")
        val now = Clock.System.now()
        AccountRefreshTokensTable.insert {
            it[tokenId] = nextId("rt")
            it[tokenHash] = hashToken(refreshToken)
            it[userId] = principal.userId
            it[expiresAt] = now.plus(14.days)
            it[revokedAt] = null
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = principal.userId
            it[updatedBy] = principal.userId
            it[deletedAt] = null
        }
        return AuthResponse(
            accessToken = jwtTokenService.issue(principal),
            refreshToken = refreshToken,
            user = row.toView()
        )
    }

    private fun seedGroup(
        groupIdValue: String,
        nameValue: String,
        multiplier: Double,
        rpm: Int?,
        tpm: Int?,
        budget: Double,
        now: kotlinx.datetime.Instant
    ) {
        AccountUserGroupsTable.insert {
            it[groupId] = groupIdValue
            it[name] = nameValue
            it[costMultiplier] = multiplier
            it[defaultRpm] = rpm
            it[defaultTpm] = tpm
            it[defaultBudgetUsd] = budget
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = "system"
            it[updatedBy] = "system"
            it[deletedAt] = null
        }
    }

    private fun seedUser(
        userIdValue: String,
        emailValue: String,
        password: String,
        displayNameValue: String,
        roleValue: String,
        groupIdValue: String,
        now: kotlinx.datetime.Instant
    ) {
        AccountUsersTable.insert {
            it[userId] = userIdValue
            it[email] = emailValue
            it[passwordHash] = passwordHasher.hash(password)
            it[displayName] = displayNameValue
            it[role] = roleValue
            it[groupId] = groupIdValue
            it[status] = AccountStatuses.ACTIVE
            it[lastLoginAt] = null
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = "system"
            it[updatedBy] = "system"
            it[deletedAt] = null
        }
    }

    private fun findUserRow(userId: String): ResultRow = AccountUsersTable.selectAll()
        .where { (AccountUsersTable.userId eq userId) and AccountUsersTable.deletedAt.isNull() }
        .firstOrNull()
        ?: throw PluginApiException(404, "User not found")

    private fun findGroupRow(groupId: String): ResultRow = AccountUserGroupsTable.selectAll()
        .where { (AccountUserGroupsTable.groupId eq groupId) and AccountUserGroupsTable.deletedAt.isNull() }
        .firstOrNull()
        ?: throw PluginApiException(404, "Group not found")

    private fun ResultRow.toView(): AccountUserView = AccountUserView(
        userId = this[AccountUsersTable.userId],
        email = this[AccountUsersTable.email],
        displayName = this[AccountUsersTable.displayName],
        role = this[AccountUsersTable.role],
        groupId = this[AccountUsersTable.groupId],
        status = this[AccountUsersTable.status]
    )

    private fun ResultRow.toGroupView(): AccountGroupView = AccountGroupView(
        groupId = this[AccountUserGroupsTable.groupId],
        name = this[AccountUserGroupsTable.name],
        costMultiplier = this[AccountUserGroupsTable.costMultiplier],
        defaultRpm = this[AccountUserGroupsTable.defaultRpm],
        defaultTpm = this[AccountUserGroupsTable.defaultTpm],
        defaultBudgetUsd = this[AccountUserGroupsTable.defaultBudgetUsd]
    )

    private fun normalizeEmail(email: String): String = email.trim().lowercase().also {
        if (!it.contains('@')) throw PluginApiException(400, "Valid email is required")
    }

    private fun requirePassword(password: String) {
        if (password.length < 8) throw PluginApiException(400, "Password must be at least 8 characters")
    }

    private fun normalizeRole(role: String): String = when (role.trim().lowercase()) {
        AccountRoles.ADMIN -> AccountRoles.ADMIN
        AccountRoles.USER -> AccountRoles.USER
        else -> throw PluginApiException(400, "Unsupported role")
    }

    private fun normalizeStatus(status: String): String = when (status.trim().lowercase()) {
        AccountStatuses.ACTIVE -> AccountStatuses.ACTIVE
        AccountStatuses.SUSPENDED -> AccountStatuses.SUSPENDED
        else -> throw PluginApiException(400, "Unsupported status")
    }

    private fun nextId(prefix: String): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return "$prefix-${base64Url(bytes)}"
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return base64Url(digest)
    }
}
