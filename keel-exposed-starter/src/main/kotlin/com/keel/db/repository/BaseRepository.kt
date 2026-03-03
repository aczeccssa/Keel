package com.keel.db.repository

import com.keel.db.database.KeelDatabase
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.selectAll
import com.keel.db.logging.DbScopeLogger

/**
 * Base repository providing common database operations.
 * This is the foundation for all repositories in the framework.
 *
 * @param T The table type this repository operates on
 * @property database The KeelDatabase instance for executing transactions
 * @property table The table this repository manages
 */
abstract class BaseRepository<T : Table>(
    protected val database: KeelDatabase,
    protected val table: T
) {
    private val logger = DbScopeLogger.getLogger("BaseRepository")

    /**
     * Execute a transaction with the database.
     *
     * @param block The transaction block
     * @return The result of the transaction
     */
    protected fun <R> transaction(block: Transaction.() -> R): R {
        return database.transaction(block)
    }

    /**
     * Execute a transaction with error handling.
     *
     * @param block The transaction block
     * @return Result containing the transaction result or an error
     */
    protected fun <R> transactionWithResult(block: Transaction.() -> R): Result<R> {
        return database.transactionWithResult(block)
    }

    /**
     * Count all records in the table.
     *
     * @return The total number of records
     */
    fun count(): Int {
        return transaction {
            table.selectAll().count().toInt()
        }
    }

    /**
     * Check if any records exist in the table.
     *
     * @return true if the table has at least one record
     */
    fun exists(): Boolean {
        return count() > 0
    }

    /**
     * Log an error and return a failure result.
     */
    protected fun <R> logError(message: String, e: Exception? = null): Result<R> {
        if (e != null) {
            logger.error(message, e)
        } else {
            logger.error(message)
        }
        return Result.failure(e ?: IllegalStateException(message))
    }
}
