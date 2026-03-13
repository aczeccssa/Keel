package com.keel.db.di

import com.keel.db.database.DatabaseConfig
import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import com.keel.contract.di.KeelDiQualifiers
import org.koin.core.module.Module
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
): Module = module {
    // Register DatabaseFactory as a factory (creates new instances)
    single { DatabaseFactory.fromConfig(config) }

    // Register KeelDatabase as singleton - initialized on startup
    single(qualifier = KeelDiQualifiers.keelDatabaseQualifier) {
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
fun databaseModule(database: KeelDatabase): Module = module {
    single(qualifier = KeelDiQualifiers.keelDatabaseQualifier) { database }
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
@Suppress("unused")
fun h2MemoryDatabaseModule(
    name: String = "testdb",
    username: String = "sa",
    password: String = "",
    poolSize: Int = 10
): Module = databaseModule(
    config = DatabaseConfig.h2Memory(name, username, password, poolSize),
    initialize = true
)

/**
 * Creates a Koin module for SQLite database.
 *
 * @param filePath Path to the SQLite file
 * @return A Koin Module for database dependencies
 */
@Suppress("unused")
fun sqliteDatabaseModule(filePath: String): Module = databaseModule(
    config = DatabaseConfig.sqlite(filePath),
    initialize = true
)
