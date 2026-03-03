package com.keel.test.db

import com.keel.db.table.AuditPluginTable
import com.keel.db.table.PluginTable
import com.keel.db.table.SoftDeletablePluginTable
import com.keel.db.table.TimestampPluginTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TableMixinsTest {

    @Test
    fun pluginTablePhysicalNameIsPrefixed() {
        val table = object : PluginTable("plug", "users") {}
        assertEquals("plug_users", table.physicalName())
    }

    @Test
    fun softDeletableTableHasDeletedAtColumn() {
        val table = object : SoftDeletablePluginTable("plug", "items") {}
        assertNotNull(table.deletedAt)
        assertEquals("deleted_at", table.deletedAt.name)
    }

    @Test
    fun timestampTableHasCreatedAndUpdatedColumns() {
        val table = object : TimestampPluginTable("plug", "entries") {}
        assertNotNull(table.createdAt)
        assertNotNull(table.updatedAt)
        assertEquals("created_at", table.createdAt.name)
        assertEquals("updated_at", table.updatedAt.name)
    }

    @Test
    fun auditTableIncludesCreatorAndModifierColumns() {
        val table = object : AuditPluginTable("plug", "audit") {}
        assertNotNull(table.createdBy)
        assertNotNull(table.updatedBy)
        assertEquals("created_by", table.createdBy.name)
        assertEquals("updated_by", table.updatedBy.name)
    }
}
