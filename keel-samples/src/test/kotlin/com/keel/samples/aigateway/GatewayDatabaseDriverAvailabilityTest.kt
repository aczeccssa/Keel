package com.keel.samples.aigateway

import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import com.keel.db.table.AuditPluginTable
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatewayDatabaseDriverAvailabilityTest {

    @Test
    fun sqliteRuntimeCanCreateFileBackedDatabase() {
        val dbFile = createTempDirectory("gateway-sqlite-db").resolve("sample.sqlite.db").toFile()
        val factory = DatabaseFactory.sqlite(dbFile.absolutePath)
        val database = factory.init()

        try {
            database.createTables(TestTable)
            val id = insertRow(database)
            assertTrue(id > 0)
        } finally {
            factory.close()
        }
    }

    @Test
    fun postgresqlDriverIsOnClasspath() {
        assertEquals("org.postgresql.Driver", Class.forName("org.postgresql.Driver").name)
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

    private object TestTable : AuditPluginTable("gateway_driver_test", "items") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 50)
        override val primaryKey = PrimaryKey(id)
    }
}
