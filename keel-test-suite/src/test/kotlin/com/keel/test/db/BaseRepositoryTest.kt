package com.keel.test.db

import com.keel.db.database.DatabaseFactory
import com.keel.db.repository.BaseRepository
import com.keel.db.table.PluginTable
import org.jetbrains.exposed.sql.insert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaseRepositoryTest {

    @Test
    fun baseRepositoryCountAndExistsWork() {
        val factory = DatabaseFactory.h2Memory(name = "repo_test_db", poolSize = 1)
        val database = factory.init()

        database.createTables(RepoTable)

        val repository = TestRepository(database)
        assertEquals(0, repository.count())
        assertTrue(!repository.exists())

        database.transaction {
            RepoTable.insert {
                it[name] = "row"
            }
        }

        assertEquals(1, repository.count())
        assertTrue(repository.exists())

        factory.close()
    }

    private object RepoTable : PluginTable("repo", "items") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 50)
        override val primaryKey = PrimaryKey(id)
    }

    private class TestRepository(database: com.keel.db.database.KeelDatabase) :
        BaseRepository<RepoTable>(database, RepoTable)
}
