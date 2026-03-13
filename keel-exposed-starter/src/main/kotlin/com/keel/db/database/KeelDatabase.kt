package com.keel.db.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import com.keel.db.logging.DbScopeLogger

/**
 * Database connection manager for the Keel framework.
 * Provides connection pooling, transaction management, and query helpers.
 */
class KeelDatabase(
    private val database: Database
) {
    private val logger = DbScopeLogger.getLogger("KeelDatabase")

    /**
     * Create tables for a plugin.
     * This should be called during plugin initialization.
     *
     * @param tables The tables to create
     */
    fun createTables(vararg tables: Table) {
        transaction(database) {
            SchemaUtils.create(*tables)
            logger.info("Created ${tables.size} tables")
        }
    }

    /**
     * Drop tables for a plugin.
     *
     * @param tables The tables to drop
     */
    @Suppress("unused")
    fun dropTables(vararg tables: Table) {
        transaction(database) {
            SchemaUtils.drop(*tables)
            logger.info("Dropped ${tables.size} tables")
        }
    }

    /**
     * Execute a transaction with error handling.
     *
     * @param block The transaction block
     * @return Result containing the transaction result or an error
     */
    @Suppress("unused")
    fun <T> transactionWithResult(block: Transaction.() -> T): Result<T> {
        return try {
            Result.success(transaction(database, block))
        } catch (e: Exception) {
            logger.error("Transaction failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Execute a transaction with retry logic.
     *
     * @param maxRetries Maximum number of retries on failure
     * @param delayMillis Delay between retries in milliseconds
     * @param block The transaction block
     * @return The transaction result
     * @throws Exception if all retries are exhausted
     */
    fun <T> transactionWithRetry(
        maxRetries: Int = 3,
        delayMillis: Long = 100,
        block: Transaction.() -> T
    ): T {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return transaction(database, block)
            } catch (e: Exception) {
                lastException = e
                logger.warn("Transaction attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < maxRetries - 1) {
                    Thread.sleep(delayMillis)
                }
            }
        }
        throw lastException ?: IllegalStateException("Transaction failed without exception")
    }

    /**
     * Execute a suspending transaction.
     * This can be used from coroutine contexts.
     *
     * @param block The suspending transaction block
     * @return The transaction result
     */
    suspend fun <T> suspendTransaction(block: Transaction.() -> T): T = withContext(Dispatchers.IO) {
        transaction(database) {
            block()
        }
    }

    /**
     * Execute a suspending transaction with error handling.
     *
     * @param block The suspending transaction block
     * @return Result containing the transaction result or an error
     */
    @Suppress("unused")
    suspend fun <T> suspendTransactionWithResult(block: Transaction.() -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(transaction(database) { block() })
        } catch (e: Exception) {
            logger.error("Suspending transaction failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Execute a transaction (basic version).
     *
     * @param block The transaction block
     * @return The transaction result
     */
    fun <T> transaction(block: Transaction.() -> T): T {
        return transaction(database, block)
    }

    /**
     * Get the underlying database instance.
     */
    @Suppress("unused")
    fun getDatabase(): Database = database
}
