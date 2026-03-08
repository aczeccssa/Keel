package com.keel.test.perf

import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import com.keel.db.table.PluginTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object DatabaseThresholds {
    const val SEQ_INSERT_5K_MAX_MS = 15_000L
    const val CONCURRENT_INSERT_4K_MAX_MS = 10_000L
    const val MIXED_RW_3K_MAX_MS = 10_000L
    const val RETRY_100_MAX_MS = 5_000L
    const val POOL_EXHAUST_1K_MAX_MS = 10_000L
}

/**
 * Stress tests for the Database layer (DatabaseFactory + KeelDatabase + PluginTable).
 *
 * Validates:
 *   - HikariCP connection pool behavior under concurrent transactions
 *   - Exposed transaction throughput with H2 in-memory database
 *   - PluginTable prefix isolation overhead
 *   - Connection pool exhaustion and queuing behavior
 */
class DatabaseStressTest {

    private var dbFactory: DatabaseFactory? = null

    @AfterTest
    fun teardown() {
        dbFactory?.close()
        dbFactory = null
    }

    // ─── Test Table ──────────────────────────────────────────

    object StressNotes : PluginTable("stresstest", "notes") {
        val id: Column<Int> = integer("id").autoIncrement()
        val title: Column<String> = varchar("title", 255)
        val content: Column<String> = text("content")
        override val primaryKey = PrimaryKey(id)
    }

    // ─────────────────────────────────────────────────────────
    //  Test 1: Sequential insert throughput
    // ─────────────────────────────────────────────────────────

    @Test
    fun sequentialInsertThroughput() = runTest {
        val factory = DatabaseFactory.h2Memory(name = "stress-seq", poolSize = 5)
        dbFactory = factory
        val db = factory.init()
        db.createTables(StressNotes)

        val insertCount = 5000

        val elapsedMs = measureTimeMillis {
            repeat(insertCount) { i ->
                db.suspendTransaction {
                    StressNotes.insert {
                        it[title] = "Note $i"
                        it[content] = "Content for note $i during sequential stress test"
                    }
                }
            }
        }

        val count = db.suspendTransaction {
            StressNotes.selectAll().count()
        }
        assertEquals(insertCount.toLong(), count, "Should have inserted $insertCount records")
        assertTrue(elapsedMs < DatabaseThresholds.SEQ_INSERT_5K_MAX_MS,
            "[CI GATE] Sequential insert took ${elapsedMs}ms, max=${DatabaseThresholds.SEQ_INSERT_5K_MAX_MS}ms")
        println("[PERF] Sequential insert: $insertCount rows in ${elapsedMs}ms (${insertCount * 1000.0 / elapsedMs} inserts/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 2: Concurrent insert stress (tests connection pool)
    // ─────────────────────────────────────────────────────────

    @Test
    fun concurrentInsertStress() = runTest {
        val poolSize = 5
        val factory = DatabaseFactory.h2Memory(name = "stress-concurrent", poolSize = poolSize)
        dbFactory = factory
        val db = factory.init()
        db.createTables(StressNotes)

        val concurrency = 20  // 4x pool size to force connection queuing
        val insertsPerCoroutine = 200
        val totalExpected = concurrency * insertsPerCoroutine

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                (0 until concurrency).map { cid ->
                    async(Dispatchers.IO) {
                        repeat(insertsPerCoroutine) { i ->
                            db.suspendTransaction {
                                StressNotes.insert {
                                    it[title] = "Concurrent-$cid-$i"
                                    it[content] = "Concurrent stress content"
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val count = db.suspendTransaction {
            StressNotes.selectAll().count()
        }
        assertEquals(totalExpected.toLong(), count, "Should have inserted $totalExpected records")
        assertTrue(elapsedMs < DatabaseThresholds.CONCURRENT_INSERT_4K_MAX_MS,
            "[CI GATE] Concurrent insert took ${elapsedMs}ms, max=${DatabaseThresholds.CONCURRENT_INSERT_4K_MAX_MS}ms")
        println("[PERF] Concurrent insert ($concurrency coroutines, pool=$poolSize): $totalExpected rows in ${elapsedMs}ms (${totalExpected * 1000.0 / elapsedMs} inserts/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 3: Mixed read/write concurrent workload
    // ─────────────────────────────────────────────────────────

    @Test
    fun mixedReadWriteWorkload() = runTest {
        val factory = DatabaseFactory.h2Memory(name = "stress-mixed", poolSize = 10)
        dbFactory = factory
        val db = factory.init()
        db.createTables(StressNotes)

        // Pre-populate with some data
        repeat(500) { i ->
            db.suspendTransaction {
                StressNotes.insert {
                    it[title] = "Seed-$i"
                    it[content] = "Seed content $i"
                }
            }
        }

        val writerCount = 5
        val readerCount = 10
        val opsPerWorker = 200

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                // Writers
                val writers = (0 until writerCount).map { wid ->
                    async(Dispatchers.IO) {
                        repeat(opsPerWorker) { i ->
                            db.suspendTransaction {
                                StressNotes.insert {
                                    it[title] = "Write-$wid-$i"
                                    it[content] = "Written during mixed workload"
                                }
                            }
                        }
                    }
                }

                // Readers
                val readers = (0 until readerCount).map { rid ->
                    async(Dispatchers.IO) {
                        repeat(opsPerWorker) {
                            db.suspendTransaction {
                                StressNotes.selectAll().count()
                            }
                        }
                    }
                }

                (writers + readers).awaitAll()
            }
        }

        val totalOps = (writerCount + readerCount) * opsPerWorker
        assertTrue(elapsedMs < DatabaseThresholds.MIXED_RW_3K_MAX_MS,
            "[CI GATE] Mixed R/W took ${elapsedMs}ms, max=${DatabaseThresholds.MIXED_RW_3K_MAX_MS}ms")
        println("[PERF] Mixed R/W ($writerCount writers, $readerCount readers, $opsPerWorker ops each): $totalOps total ops in ${elapsedMs}ms (${totalOps * 1000.0 / elapsedMs} ops/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 4: Transaction with retry under contention
    // ─────────────────────────────────────────────────────────

    @Test
    fun transactionWithRetryStress() = runTest {
        val factory = DatabaseFactory.h2Memory(name = "stress-retry", poolSize = 3)
        dbFactory = factory
        val db = factory.init()
        db.createTables(StressNotes)

        val retryCount = 100

        val elapsedMs = measureTimeMillis {
            repeat(retryCount) { i ->
                db.transactionWithRetry(maxRetries = 3, delayMillis = 10) {
                    StressNotes.insert {
                        it[title] = "Retry-$i"
                        it[content] = "Testing retry logic under stress"
                    }
                }
            }
        }

        val count = db.suspendTransaction {
            StressNotes.selectAll().count()
        }
        assertEquals(retryCount.toLong(), count, "Should have $retryCount rows")
        assertTrue(elapsedMs < DatabaseThresholds.RETRY_100_MAX_MS,
            "[CI GATE] Transaction with retry took ${elapsedMs}ms, max=${DatabaseThresholds.RETRY_100_MAX_MS}ms")
        println("[PERF] Transaction with retry: $retryCount ops in ${elapsedMs}ms (${retryCount * 1000.0 / elapsedMs} ops/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 5: Connection pool exhaustion behavior
    //         (more concurrent txns than pool size)
    // ─────────────────────────────────────────────────────────

    @Test
    fun connectionPoolExhaustion() = runTest {
        val poolSize = 2
        val factory = DatabaseFactory.h2Memory(name = "stress-exhaust", poolSize = poolSize)
        dbFactory = factory
        val db = factory.init()
        db.createTables(StressNotes)

        val concurrency = 20  // 10x pool size
        val insertsPerCoroutine = 50

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                (0 until concurrency).map { cid ->
                    async(Dispatchers.IO) {
                        repeat(insertsPerCoroutine) { i ->
                            db.suspendTransaction {
                                StressNotes.insert {
                                    it[title] = "Exhaust-$cid-$i"
                                    it[content] = "Pool exhaustion test"
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val expected = (concurrency * insertsPerCoroutine).toLong()
        val count = db.suspendTransaction { StressNotes.selectAll().count() }
        assertEquals(expected, count, "All inserts should succeed even with pool exhaustion")
        assertTrue(elapsedMs < DatabaseThresholds.POOL_EXHAUST_1K_MAX_MS,
            "[CI GATE] Pool exhaustion took ${elapsedMs}ms, max=${DatabaseThresholds.POOL_EXHAUST_1K_MAX_MS}ms")
        println("[PERF] Pool exhaustion (pool=$poolSize, ${concurrency}x concurrency): $expected rows in ${elapsedMs}ms (${expected * 1000.0 / elapsedMs} inserts/sec)")
    }
}
