package com.keel.samples.aigateway.airelay.batches

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object BatchesTable : AuditPluginTable("airelay", "batches") {
    val batchId: Column<String> = varchar("batch_id", 32)
    val customerId: Column<String?> = varchar("customer_id", 64).nullable()
    val groupId: Column<String> = varchar("group_id", 64).default("default")
    val status: Column<String> = varchar("status", 16).default("in_progress")
    val requestCountsJson: Column<String> = text("request_counts_json").default("{}")
    val resultsJsonlPath: Column<String?> = varchar("results_jsonl_path", 512).nullable()
    val expiresAt = timestamp("expires_at").nullable()

    override val primaryKey = PrimaryKey(batchId)
}

object BatchRequestItemsTable : AuditPluginTable("airelay", "batch_request_items") {
    val itemId: Column<String> = varchar("item_id", 32)
    val batchId: Column<String> = varchar("batch_id", 32).index()
    val customId: Column<String> = varchar("custom_id", 128)
    val requestJson: Column<String> = text("request_json")
    val status: Column<String> = varchar("status", 16).default("pending")
    val resultJsonl: Column<String?> = text("result_jsonl").nullable()

    override val primaryKey = PrimaryKey(itemId)
}

object BatchStatuses {
    const val IN_PROGRESS = "in_progress"
    const val CANCELING = "canceling"
    const val ENDED = "ended"
}

object BatchItemStatuses {
    const val PENDING = "pending"
    const val PROCESSING = "processing"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELED = "canceled"
}
