package com.keel.test.db

import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import com.keel.db.table.AuditPluginTable
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert
import kotlin.test.Test
import kotlin.test.assertTrue

class DatabaseFactoryTest {

    @Test
    fun h2MemoryDatabaseCanCreateTablesAndInsert() {
        val factory = DatabaseFactory.h2Memory(name = "keel_test_db", poolSize = 1)
        val database = factory.init()

        database.createTables(TestTable)
        val id = insertRow(database)
        assertTrue(id > 0)

        factory.close()
    }

    private fun insertRow(database: KeelDatabase): Int {
        return database.transaction {
            TestTable.insert {
                it[name] = "row"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
                it[createdBy] = "tester"
                it[updatedBy] = "tester"
            } get TestTable.id
        }
    }

    private object TestTable : AuditPluginTable("test_plugin", "items") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 50)
        override val primaryKey = PrimaryKey(id)
    }
}
