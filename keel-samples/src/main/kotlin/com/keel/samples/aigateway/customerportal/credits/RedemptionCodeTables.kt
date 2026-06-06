package com.keel.samples.aigateway.customerportal.credits

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object RedemptionCodesTable : AuditPluginTable("customer-portal", "redemption_codes") {
    val code: Column<String> = varchar("code", 32).uniqueIndex()
    val faceValueCredits: Column<Long> = long("face_value_credits")
    val createdByAdminId: Column<String> = varchar("created_by_admin_id", 64)
    val redeemedByCustomerId: Column<String?> = varchar("redeemed_by_customer_id", 32).nullable()
    val redeemedAt = timestamp("redeemed_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val status: Column<String> = varchar("status", 16).default("active")

    override val primaryKey = PrimaryKey(code)
}

object RedemptionCodeStatuses {
    const val ACTIVE = "active"
    const val REDEEMED = "redeemed"
    const val EXPIRED = "expired"
    const val REVOKED = "revoked"
}
