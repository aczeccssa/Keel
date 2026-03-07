package com.keel.test.perf.ordersystem

import com.keel.contract.events.KeelEventBus

/**
 * Business service for the OrderSystem stress test module.
 *
 * Implements core enterprise business logic:
 * - Place order: validate stock → deduct inventory → create order → publish event
 * - Cancel order: update status → publish event
 * - Order lifecycle: PENDING → CONFIRMED → SHIPPED → COMPLETED
 */
class OrderSystemService(
    private val repo: OrderSystemRepository,
    private val eventBus: KeelEventBus
) {

    /**
     * Place a new order with inventory check and deduction.
     *
     * Business rules:
     * 1. Validate all items have sufficient stock
     * 2. Deduct stock atomically (optimistic concurrency)
     * 3. Create order and order items
     * 4. Publish OrderCreatedEvent via EventBus
     *
     * @return the created order ID, or throws if any item is out of stock
     */
    suspend fun placeOrder(request: CreateOrderRequest): Int {
        // Step 1: Fetch all products and their prices upfront to avoid race conditions.
        val itemsWithProducts = request.items.map { item ->
            val product = repo.getProduct(item.productId)
                ?: throw IllegalArgumentException("Product ${item.productId} not found")
            item to product
        }

        // Step 2: Deduct stock for each item.
        val deductions = mutableListOf<Pair<Int, Int>>() // productId -> quantity (for rollback)
        var totalAmount = 0L

        for ((item, product) in itemsWithProducts) {
            val remainingStock = repo.deductStock(item.productId, item.quantity)
            if (remainingStock < 0) {
                // Rollback previous deductions (best-effort).
                for ((productId, qty) in deductions) {
                    repo.deductStock(productId, -qty)  // Add stock back.
                }
                throw InsufficientStockException(item.productId, item.quantity)
            }

            deductions.add(item.productId to item.quantity)
            totalAmount += product.price * item.quantity

            // Publish inventory event.
            eventBus.publish(InventoryDeductedEvent(
                productId = item.productId,
                quantityDeducted = item.quantity,
                remainingStock = remainingStock
            ))
        }

        // Step 3: Create the order.
        val orderId = repo.createOrder(request.customerId, totalAmount)

        // Step 4: Create order items using the prices fetched initially.
        for ((item, product) in itemsWithProducts) {
            repo.addOrderItem(orderId, item.productId, item.quantity, product.price)
        }

        // Step 5: Publish the order creation event.
        eventBus.publish(OrderCreatedEvent(
            orderId = orderId,
            customerId = request.customerId,
            totalAmount = totalAmount,
            itemCount = request.items.size
        ))

        return orderId
    }

    /**
     * Cancel an order and publish event.
     */
    suspend fun cancelOrder(orderId: Int, reason: String): Boolean {
        val updated = repo.updateOrderStatus(orderId, OrderStatus.CANCELLED)
        if (updated) {
            eventBus.publish(OrderCancelledEvent(orderId, reason))
        }
        return updated
    }

    /**
     * Advance order through the lifecycle: PENDING → CONFIRMED → SHIPPED → COMPLETED
     */
    fun advanceOrder(orderId: Int): OrderStatus {
        val current = repo.getOrderStatus(orderId) ?: throw IllegalArgumentException("Order $orderId not found")
        val next = when (current) {
            OrderStatus.PENDING -> OrderStatus.CONFIRMED
            OrderStatus.CONFIRMED -> OrderStatus.SHIPPED
            OrderStatus.SHIPPED -> OrderStatus.COMPLETED
            OrderStatus.COMPLETED -> throw IllegalStateException("Order already completed")
            OrderStatus.CANCELLED -> throw IllegalStateException("Cannot advance cancelled order")
        }
        repo.updateOrderStatus(orderId, next)
        return next
    }
}

class InsufficientStockException(val productId: Int, val requestedQuantity: Int) :
    RuntimeException("Insufficient stock for product $productId, requested $requestedQuantity")
