package com.keel.db.table

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * A table mixin that provides full audit trail functionality.
 * Combines TimestampTable (createdAt, updatedAt) with SoftDeletableTable (deletedAt)
 * and adds creator/modifier tracking.
 *
 * Use this for tables where you need complete audit history including
 * who created/modified records.
 *
 * Example:
 * ```kotlin
 * class UserTable(pluginId: String) : PluginTable(pluginId, "users"), AuditTable {
 *     val id = integer("id").autoIncrement()
 *     val name = varchar("name", 100)
 *     // AuditTable provides: createdAt, updatedAt, deletedAt, createdBy, updatedBy
 * }
 * ```
 */
interface AuditTable : TimestampTable, SoftDeletableTable {
    /**
     * The column storing who created the record.
     * This could be a user ID, username, or system identifier.
     */
    val createdBy: Column<String?>

    /**
     * The column storing who last modified the record.
     * This could be a user ID, username, or system identifier.
     */
    val updatedBy: Column<String?>

    companion object {
        /**
         * Column name for the createdBy field.
         */
        const val CREATED_BY_COLUMN = "created_by"

        /**
         * Column name for the updatedBy field.
         */
        const val UPDATED_BY_COLUMN = "updated_by"
    }
}

/**
 * A table that supports both plugin prefixing and full audit trail.
 * Combines timestamp management, soft-delete, and creator/modifier tracking.
 *
 * @param pluginId The plugin ID for table name prefixing
 * @param tableName The logical table name
 */
abstract class AuditPluginTable(
    pluginId: String,
    tableName: String
) : PluginTable(pluginId, tableName), AuditTable {

    // TimestampTable implementation
    override val createdAt: Column<Instant> = timestamp(TimestampTable.CREATED_AT_COLUMN)
        .default(Clock.System.now())

    override val updatedAt: Column<Instant> = timestamp(TimestampTable.UPDATED_AT_COLUMN)
        .default(Clock.System.now())

    // SoftDeletableTable implementation
    override val deletedAt: Column<Instant?> = timestamp(SoftDeletableTable.DELETED_AT_COLUMN).nullable()

    // AuditTable implementation
    override val createdBy: Column<String?> = varchar(AuditTable.CREATED_BY_COLUMN, 255).nullable()

    override val updatedBy: Column<String?> = varchar(AuditTable.UPDATED_BY_COLUMN, 255).nullable()

    /**
     * Get the current timestamp using kotlinx.datetime.Clock.
     */
    @Suppress("unused")
    fun currentTime(): Instant = Clock.System.now()
}
