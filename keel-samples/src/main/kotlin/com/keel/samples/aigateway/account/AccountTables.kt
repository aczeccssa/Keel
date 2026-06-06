package com.keel.samples.aigateway.account

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object AccountUsersTable : AuditPluginTable("account", "users") {
    val userId: Column<String> = varchar("user_id", 32)
    val email: Column<String> = varchar("email", 256).uniqueIndex()
    val passwordHash: Column<String> = varchar("password_hash", 256)
    val displayName: Column<String> = varchar("display_name", 120)
    val role: Column<String> = varchar("role", 16)
    val groupId: Column<String> = varchar("group_id", 32).index()
    val status: Column<String> = varchar("status", 16)
    val lastLoginAt = timestamp("last_login_at").nullable()

    override val primaryKey = PrimaryKey(userId)
}

object AccountUserGroupsTable : AuditPluginTable("account", "user_groups") {
    val groupId: Column<String> = varchar("group_id", 32)
    val name: Column<String> = varchar("name", 80)
    val costMultiplier: Column<Double> = double("cost_multiplier")
    val defaultRpm: Column<Int?> = integer("default_rpm").nullable()
    val defaultTpm: Column<Int?> = integer("default_tpm").nullable()
    val defaultBudgetUsd: Column<Double> = double("default_budget_usd")

    override val primaryKey = PrimaryKey(groupId)
}

object AccountRefreshTokensTable : AuditPluginTable("account", "refresh_tokens") {
    val tokenId: Column<String> = varchar("token_id", 32)
    val tokenHash: Column<String> = varchar("token_hash", 64).uniqueIndex()
    val userId: Column<String> = varchar("user_id", 32).index()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(tokenId)
}
