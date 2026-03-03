package com.keel.db.table

import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * A table mixin that adds soft-delete functionality.
 * Provides a `deletedAt` column that is set when a record is "deleted"
 * and cleared when the record is "restored".
 *
 * Use this for tables where you want to retain data but hide deleted records
 * from normal queries.
 *
 * Example:
 * ```kotlin
 * class UserTable(pluginId: String) : PluginTable(pluginId, "users"), SoftDeletableTable {
 *     val id = integer("id").autoIncrement()
 *     val name = varchar("name", 100)
 *     // SoftDeletableTable provides: deletedAt
 * }
 * ```
 */
interface SoftDeletableTable {
    /**
     * The column storing the deletion timestamp.
     * - null: Record is active (not deleted)
     * - non-null: Record was deleted at this time
     */
    val deletedAt: Column<Instant?>

    companion object {
        /**
         * Column name for the deletedAt field.
         */
        const val DELETED_AT_COLUMN = "deleted_at"
    }
}

/**
 * A table that supports both plugin prefixing and soft-delete functionality.
 *
 * @param pluginId The plugin ID for table name prefixing
 * @param tableName The logical table name
 */
abstract class SoftDeletablePluginTable(
    pluginId: String,
    tableName: String
) : PluginTable(pluginId, tableName), SoftDeletableTable {

    /**
     * The deletedAt column using kotlinx.datetime.Instant.
     * null means the record is active, non-null means it's soft-deleted.
     */
    override val deletedAt: Column<Instant?> = timestamp(SoftDeletableTable.DELETED_AT_COLUMN).nullable()
}
