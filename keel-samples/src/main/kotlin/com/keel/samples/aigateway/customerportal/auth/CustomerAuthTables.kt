package com.keel.samples.aigateway.customerportal.auth

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object CustomersTable : AuditPluginTable("customer-portal", "customers") {
    val customerId: Column<String> = varchar("customer_id", 32)
    val email: Column<String> = varchar("email", 256).uniqueIndex()
    val passwordHash: Column<String> = varchar("password_hash", 256).default("")
    val passwordSalt: Column<String> = varchar("password_salt", 64).default("")
    val displayName: Column<String> = varchar("display_name", 120)
    val oauthProvider: Column<String?> = varchar("oauth_provider", 16).nullable()
    val oauthSubject: Column<String?> = varchar("oauth_subject", 128).nullable()
    val emailVerified: Column<Boolean> = bool("email_verified").default(false)
    val status: Column<String> = varchar("status", 16).default("active")
    val lastLoginAt = timestamp("last_login_at").nullable()

    override val primaryKey = PrimaryKey(customerId)
}

object CustomerSessionsTable : AuditPluginTable("customer-portal", "sessions") {
    val sessionId: Column<String> = varchar("session_id", 32)
    val customerId: Column<String> = varchar("customer_id", 32).index()
    val refreshTokenHash: Column<String> = varchar("refresh_token_hash", 64).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(sessionId)
}

object CustomerStatuses {
    const val ACTIVE = "active"
    const val LOCKED = "locked"
    const val DELETED = "deleted"
}
