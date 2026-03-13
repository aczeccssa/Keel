package com.keel.db.table

import org.jetbrains.exposed.sql.Table

/**
 * Base table class that enforces plugin-specific table naming.
 * All plugin tables MUST extend this class to ensure proper table prefixing.
 *
 * Physical table name format: ${pluginId}_${name}
 * Example: auth_users, user_profiles
 */
abstract class PluginTable(
    /**
     * The plugin ID that owns this table.
     * Used as a prefix for the physical table name.
     */
    protected val pluginId: String,
    /**
     * The logical name of the table (without prefix).
     */
    tableName: String
) : Table("${pluginId}_$tableName") {

    /**
     * The logical table name for this plugin table.
     */
    private val tableNameValue: String = tableName

    /**
     * Generate the full physical table name.
     */
    fun physicalName(): String = "${pluginId}_$tableNameValue"
}
