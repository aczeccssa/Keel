package com.keel.db.di

import com.keel.db.database.DatabaseConfig
import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import org.koin.dsl.module

/**
 * Creates a Koin module for database configuration and dependency injection.
 *
 * This module provides:
 * - KeelDatabase as a singleton
 * - DatabaseFactory for creating database instances
 *
 * @param config The database configuration
 * @param initialize Whether to immediately initialize the database (default: true)
 * @return A Koin Module for database dependencies
 */
fun databaseModule(
    config: DatabaseConfig,
    initialize: Boolean = true
) = module {
    // Register DatabaseFactory as a factory (creates new instances)
    single { DatabaseFactory.fromConfig(config) }

    // Register KeelDatabase as singleton - initialized on startup
    single {
        val factory: DatabaseFactory = get()
        factory.init()
    }
}

/**
 * Creates a Koin module for an already initialized KeelDatabase.
 * Use this when the database is initialized externally.
 *
 * @param database The already initialized KeelDatabase instance
 * @return A Koin Module for database dependencies
 */
fun databaseModule(database: KeelDatabase) = module {
    single { database }
}

/**
 * Creates a Koin module for H2 in-memory database (useful for testing).
 *
 * @param name Database name (default: "testdb")
 * @param username Username (default: "sa")
 * @param password Password (default: "")
 * @param poolSize Connection pool size (default: 10)
 * @return A Koin Module for database dependencies
 */
fun h2MemoryDatabaseModule(
    name: String = "testdb",
    username: String = "sa",
    password: String = "",
    poolSize: Int = 10
) = databaseModule(
    config = DatabaseConfig.h2Memory(name, username, password, poolSize),
    initialize = true
)

/**
 * Creates a Koin module for SQLite database.
 *
 * @param filePath Path to the SQLite file
 * @return A Koin Module for database dependencies
 */
fun sqliteDatabaseModule(filePath: String) = databaseModule(
    config = DatabaseConfig.sqlite(filePath),
    initialize = true
)
