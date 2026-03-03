package com.keel.db.table

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * A table mixin that automatically manages createdAt and updatedAt timestamps.
 * These columns are automatically set when records are inserted or updated.
 *
 * Use this for tables where you want to track when records were created/modified.
 *
 * Example:
 * ```kotlin
 * class UserTable(pluginId: String) : PluginTable(pluginId, "users"), TimestampTable {
 *     val id = integer("id").autoIncrement()
 *     val name = varchar("name", 100)
 *     // TimestampTable provides: createdAt, updatedAt
 * }
 * ```
 */
interface TimestampTable {
    /**
     * The column storing when the record was created.
     * This is set automatically on insert.
     */
    val createdAt: Column<Instant>

    /**
     * The column storing when the record was last updated.
     * This is automatically updated on insert and update.
     */
    val updatedAt: Column<Instant>

    companion object {
        /**
         * Column name for the createdAt field.
         */
        const val CREATED_AT_COLUMN = "created_at"

        /**
         * Column name for the updatedAt field.
         */
        const val UPDATED_AT_COLUMN = "updated_at"
    }
}

/**
 * A table that supports both plugin prefixing and automatic timestamp management.
 *
 * @param pluginId The plugin ID for table name prefixing
 * @param tableName The logical table name
 */
abstract class TimestampPluginTable(
    pluginId: String,
    tableName: String
) : PluginTable(pluginId, tableName), TimestampTable {

    /**
     * The createdAt column - automatically set to current time on insert.
     */
    override val createdAt: Column<Instant> = timestamp(TimestampTable.CREATED_AT_COLUMN)
        .default(Clock.System.now())

    /**
     * The updatedAt column - automatically set to current time on insert and update.
     */
    override val updatedAt: Column<Instant> = timestamp(TimestampTable.UPDATED_AT_COLUMN)
        .default(Clock.System.now())

    /**
     * Get the current timestamp using kotlinx.datetime.Clock.
     */
    fun currentTime(): Instant = Clock.System.now()
}
