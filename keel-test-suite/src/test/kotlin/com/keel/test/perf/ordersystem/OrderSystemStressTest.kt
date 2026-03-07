package com.keel.test.perf.ordersystem

import com.keel.db.database.DatabaseFactory
import com.keel.kernel.events.DefaultKeelEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enterprise CI/CD Benchmark Thresholds for OrderSystem.
 */
private object OrderSystemThresholds {
    const val BULK_ORDER_MAX_MS = 30_000L
    const val INVENTORY_CONTENTION_MAX_MS = 15_000L
    const val MIXED_RW_MAX_MS = 20_000L
    const val LIFECYCLE_MAX_MS = 10_000L
    const val EVENT_MIN_DELIVERY_RATE = 0.90
}

/**
 * End-to-end stress tests for the OrderSystem enterprise business module.
 *
 * Validates:
 *   1. Bulk order placement throughput
 *   2. Inventory contention (no overselling under concurrent access)
 *   3. Mixed read/write workload on the full business stack
 *   4. EventBus cascade (order events → inventory events)
 *   5. Full order lifecycle state machine correctness under load
 */
class OrderSystemStressTest {

    private var dbFactory: DatabaseFactory? = null

    @AfterTest
    fun teardown() {
        dbFactory?.close()
        dbFactory = null
    }

    private fun setupDatabase(): Pair<OrderSystemRepository, com.keel.db.database.KeelDatabase> {
        val factory = DatabaseFactory.h2Memory(name = "ordersys-${System.nanoTime()}", poolSize = 10)
        dbFactory = factory
        val db = factory.init()
        db.createTables(ProductsTable, CustomersTable, OrdersTable, OrderItemsTable)
        return OrderSystemRepository(db) to db
    }

    private fun seedData(repo: OrderSystemRepository, productCount: Int = 20, customerCount: Int = 10): Pair<List<Product>, List<Customer>> {
        val products = (1..productCount).map { i ->
            repo.createProduct(Product(name = "Product-$i", price = (1000 + i * 100).toLong(), stock = 10000))
        }
        val customers = (1..customerCount).map { i ->
            repo.createCustomer(Customer(name = "Customer-$i", email = "customer$i@test.com", level = if (i % 3 == 0) "VIP" else "STANDARD"))
        }
        return products to customers
    }

    // ─────────────────────────────────────────────────────────
    //  Test 1: Bulk order placement throughput
    // ─────────────────────────────────────────────────────────

    @Test
    fun bulkOrderPlacement() = runTest {
        val (repo, _) = setupDatabase()
        val eventBus = DefaultKeelEventBus()
        val service = OrderSystemService(repo, eventBus)
        val (products, customers) = seedData(repo)

        val concurrency = 20
        val ordersPerCoroutine = 50
        val totalOrders = concurrency * ordersPerCoroutine
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                (0 until concurrency).map { cid ->
                    async(Dispatchers.IO) {
                        repeat(ordersPerCoroutine) { i ->
                            try {
                                val product = products[(cid * ordersPerCoroutine + i) % products.size]
                                val customer = customers[(cid + i) % customers.size]
                                service.placeOrder(CreateOrderRequest(
                                    customerId = customer.id,
                                    items = listOf(OrderItemRequest(product.id, 1))
                                ))
                                successCount.incrementAndGet()
                            } catch (e: Exception) {
                                failCount.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val dbOrderCount = repo.countOrders()
        assertEquals(successCount.get().toLong(), dbOrderCount, "DB order count should match success count")
        assertTrue(successCount.get() > 0, "Should have created at least some orders")

        assertTrue(elapsedMs < OrderSystemThresholds.BULK_ORDER_MAX_MS,
            "[CI GATE] Bulk order placement took ${elapsedMs}ms, max=${OrderSystemThresholds.BULK_ORDER_MAX_MS}ms")
        println("[PERF] Bulk orders: $totalOrders attempted, ${successCount.get()} succeeded, ${failCount.get()} failed in ${elapsedMs}ms (${successCount.get() * 1000.0 / elapsedMs} orders/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 2: Inventory contention — no overselling
    // ─────────────────────────────────────────────────────────

    @Test
    fun inventoryContentionNoOverselling() = runTest {
        val (repo, _) = setupDatabase()
        val eventBus = DefaultKeelEventBus()
        val service = OrderSystemService(repo, eventBus)

        // Create a single product with limited stock
        val initialStock = 100
        val product = repo.createProduct(Product(name = "Limited-Edition", price = 9900, stock = initialStock))
        val customer = repo.createCustomer(Customer(name = "Buyer", email = "buyer@test.com"))

        val concurrency = 100  // More buyers than stock
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                (0 until concurrency).map {
                    async(Dispatchers.IO) {
                        try {
                            service.placeOrder(CreateOrderRequest(
                                customerId = customer.id,
                                items = listOf(OrderItemRequest(product.id, 1))
                            ))
                            successCount.incrementAndGet()
                        } catch (e: InsufficientStockException) {
                            failCount.incrementAndGet()
                        } catch (e: Exception) {
                            failCount.incrementAndGet()
                        }
                    }
                }.awaitAll()
            }
        }

        val finalStock = repo.getStock(product.id)
        assertTrue(finalStock >= 0, "Stock must never go negative! Got: $finalStock")
        assertTrue(successCount.get() <= initialStock,
            "Successful orders (${successCount.get()}) must not exceed initial stock ($initialStock)")
        assertEquals(initialStock - successCount.get(), finalStock,
            "Remaining stock should equal initial - successful orders")

        assertTrue(elapsedMs < OrderSystemThresholds.INVENTORY_CONTENTION_MAX_MS,
            "[CI GATE] Inventory contention test took ${elapsedMs}ms, max=${OrderSystemThresholds.INVENTORY_CONTENTION_MAX_MS}ms")
        println("[PERF] Inventory contention: $concurrency contestants for $initialStock stock, ${successCount.get()} won, ${failCount.get()} rejected in ${elapsedMs}ms")
        println("[PERF] Final stock: $finalStock (expected: ${initialStock - successCount.get()})")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 3: Mixed read/write business workload
    // ─────────────────────────────────────────────────────────

    @Test
    fun mixedBusinessWorkload() = runTest {
        val (repo, _) = setupDatabase()
        val eventBus = DefaultKeelEventBus()
        val service = OrderSystemService(repo, eventBus)
        val (products, customers) = seedData(repo)

        val writerCount = 10
        val readerCount = 20
        val opsPerWorker = 50
        val successfulOrders = AtomicInteger(0)

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                // Writers: place orders
                val writers = (0 until writerCount).map { wid ->
                    async(Dispatchers.IO) {
                        repeat(opsPerWorker) { i ->
                            try {
                                val product = products[(wid * opsPerWorker + i) % products.size]
                                val customer = customers[(wid + i) % customers.size]
                                service.placeOrder(CreateOrderRequest(
                                    customerId = customer.id,
                                    items = listOf(OrderItemRequest(product.id, 1))
                                ))
                                successfulOrders.incrementAndGet()
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Readers: query counts and stats
                val readers = (0 until readerCount).map {
                    async(Dispatchers.IO) {
                        repeat(opsPerWorker) {
                            repo.countOrders()
                            repo.countProducts()
                            repo.countOrderItems()
                        }
                    }
                }

                (writers + readers).awaitAll()
            }
        }

        val totalOps = (writerCount + readerCount) * opsPerWorker
        assertTrue(elapsedMs < OrderSystemThresholds.MIXED_RW_MAX_MS,
            "[CI GATE] Mixed business workload took ${elapsedMs}ms, max=${OrderSystemThresholds.MIXED_RW_MAX_MS}ms")
        println("[PERF] Mixed business workload: $totalOps total ops ($writerCount writers, $readerCount readers) in ${elapsedMs}ms (${totalOps * 1000.0 / elapsedMs} ops/sec)")
        println("[PERF] Successful orders: ${successfulOrders.get()}")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 4: EventBus cascade — order events trigger processing
    // ─────────────────────────────────────────────────────────

    @Test
    fun eventBusCascade() = kotlinx.coroutines.runBlocking {
        val (repo, _) = setupDatabase()
        val eventBus = DefaultKeelEventBus()
        val service = OrderSystemService(repo, eventBus)
        val (products, customers) = seedData(repo, productCount = 5, customerCount = 3)

        val orderEventCount = AtomicLong(0)
        val inventoryEventCount = AtomicLong(0)
        val orderCount = 100

        // Subscribe to events
        val orderCollector = launch(Dispatchers.Default) {
            eventBus.subscribe(OrderCreatedEvent::class.java).collect {
                orderEventCount.incrementAndGet()
            }
        }
        val inventoryCollector = launch(Dispatchers.Default) {
            eventBus.subscribe(InventoryDeductedEvent::class.java).collect {
                inventoryEventCount.incrementAndGet()
            }
        }
        yield()

        val elapsedMs = measureTimeMillis {
            repeat(orderCount) { i ->
                val product = products[i % products.size]
                val customer = customers[i % customers.size]
                service.placeOrder(CreateOrderRequest(
                    customerId = customer.id,
                    items = listOf(OrderItemRequest(product.id, 1))
                ))
            }
        }

        // Give some time for events to propagate.
        kotlinx.coroutines.delay(2000)
        orderCollector.cancel()
        inventoryCollector.cancel()

        val orderDeliveryRate = orderEventCount.get().toDouble() / orderCount
        val inventoryDeliveryRate = inventoryEventCount.get().toDouble() / orderCount
        assertTrue(orderDeliveryRate >= OrderSystemThresholds.EVENT_MIN_DELIVERY_RATE,
            "[CI GATE] Order event delivery rate ${orderDeliveryRate * 100}% below min")
        assertTrue(inventoryDeliveryRate >= OrderSystemThresholds.EVENT_MIN_DELIVERY_RATE,
            "[CI GATE] Inventory event delivery rate ${inventoryDeliveryRate * 100}% below min")

        println("[PERF] EventBus cascade: $orderCount orders in ${elapsedMs}ms")
        println("[PERF] OrderCreatedEvents: ${orderEventCount.get()}/$orderCount, InventoryDeductedEvents: ${inventoryEventCount.get()}/$orderCount")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 5: Full lifecycle state machine correctness under load
    // ─────────────────────────────────────────────────────────

    @Test
    fun fullLifecycleUnderLoad() = runTest {
        val (repo, _) = setupDatabase()
        val eventBus = DefaultKeelEventBus()
        val service = OrderSystemService(repo, eventBus)
        val (products, customers) = seedData(repo, productCount = 5, customerCount = 3)

        val concurrency = 20
        val ordersPerCoroutine = 10

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                (0 until concurrency).map { cid ->
                    async(Dispatchers.IO) {
                        repeat(ordersPerCoroutine) { i ->
                            val product = products[(cid + i) % products.size]
                            val customer = customers[(cid + i) % customers.size]

                            // Place order
                            val orderId = service.placeOrder(CreateOrderRequest(
                                customerId = customer.id,
                                items = listOf(OrderItemRequest(product.id, 1))
                            ))

                            // Advance through lifecycle: PENDING → CONFIRMED → SHIPPED → COMPLETED
                            assertEquals(OrderStatus.CONFIRMED, service.advanceOrder(orderId))
                            assertEquals(OrderStatus.SHIPPED, service.advanceOrder(orderId))
                            assertEquals(OrderStatus.COMPLETED, service.advanceOrder(orderId))

                            // Verify final state
                            assertEquals(OrderStatus.COMPLETED, repo.getOrderStatus(orderId))
                        }
                    }
                }.awaitAll()
            }
        }

        val totalOrders = concurrency * ordersPerCoroutine
        val completedOrders = repo.countOrdersByStatus(OrderStatus.COMPLETED)
        assertEquals(totalOrders.toLong(), completedOrders, "All orders should be COMPLETED")

        assertTrue(elapsedMs < OrderSystemThresholds.LIFECYCLE_MAX_MS,
            "[CI GATE] Lifecycle test took ${elapsedMs}ms, max=${OrderSystemThresholds.LIFECYCLE_MAX_MS}ms")
        println("[PERF] Full lifecycle: $totalOrders orders through PENDING→CONFIRMED→SHIPPED→COMPLETED in ${elapsedMs}ms (${totalOrders * 1000.0 / elapsedMs} orders/sec)")
    }
}
