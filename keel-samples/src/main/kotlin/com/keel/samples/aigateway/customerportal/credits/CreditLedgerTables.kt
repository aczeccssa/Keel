package com.keel.samples.aigateway.customerportal.credits

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column

object CreditLedgerTable : AuditPluginTable("customer-portal", "credit_ledger") {
    val entryId: Column<String> = varchar("entry_id", 32)
    val customerId: Column<String> = varchar("customer_id", 32).index()
    val deltaCredits: Column<Long> = long("delta_credits")
    val reason: Column<String> = varchar("reason", 32)
    val refId: Column<String?> = varchar("ref_id", 64).nullable()
    val balanceAfterCredits: Column<Long> = long("balance_after_credits")
    val usdMicrosAtTime: Column<Long> = long("usd_micros_at_time").default(0)

    override val primaryKey = PrimaryKey(entryId)
}

object LedgerReasons {
    const val SIGNUP_BONUS = "signup_bonus"
    const val REDEMPTION = "redemption"
    const val USAGE = "usage"
    const val REFUND = "refund"
    const val ADMIN_ADJUSTMENT = "admin_adjustment"
}
