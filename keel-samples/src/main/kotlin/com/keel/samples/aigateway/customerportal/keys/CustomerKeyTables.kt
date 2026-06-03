package com.keel.samples.aigateway.customerportal.keys

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object CustomerKeysTable : AuditPluginTable("customer-portal", "keys") {
    val keyId: Column<String> = varchar("key_id", 32)
    val customerId: Column<String> = varchar("customer_id", 32).index()
    val name: Column<String> = varchar("name", 120)
    val prefix: Column<String> = varchar("prefix", 32)
    val secretHash: Column<String> = varchar("secret_hash", 64).uniqueIndex()
    val routingGroupId: Column<String> = varchar("routing_group_id", 64).default("default")
    val monthlyBudgetCredits: Column<Long?> = long("monthly_budget_credits").nullable()
    val status: Column<String> = varchar("status", 16).default("active")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(keyId)
}
