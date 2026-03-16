package com.keel.test.fixtures

import com.keel.db.database.DatabaseConfig
import com.keel.db.di.databaseModule
import org.koin.core.module.Module

/**
 * Abstraction for providing a test database.
 * Default implementation uses H2 in-memory; users can implement this
 * for Testcontainers or other providers.
 */
interface TestDatabaseProvider {
    /** Prepare external resources before Koin/context startup. */
    fun beforeStart() {}

    /** Create the Koin module that provides the database beans. */
    fun createModule(): Module

    /** Clean up after the test (drop tables, close connections, etc.). */
    fun cleanup() {}

    /** Final hook after fixture disposal. */
    fun afterDispose() {}
}

/**
 * Default H2 in-memory database provider.
 * Creates a uniquely-named database per test to ensure isolation.
 */
class H2InMemoryDatabaseProvider(
    private val databaseName: String = "test_db_${System.nanoTime()}"
) : TestDatabaseProvider {
    override fun createModule(): Module {
        return databaseModule(DatabaseConfig.h2Memory(databaseName))
    }
}
