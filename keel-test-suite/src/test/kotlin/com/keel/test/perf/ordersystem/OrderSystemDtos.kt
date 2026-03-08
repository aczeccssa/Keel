package com.keel.test.perf.ordersystem

import com.keel.contract.events.KeelEvent

/**
 * DTOs and Events for the OrderSystem stress test module.
 */

// ─── DTOs ────────────────────────────────────────────────

data class Product(
    val id: Int = 0,
    val name: String,
    val price: Long,
    val stock: Int
)

data class Customer(
    val id: Int = 0,
    val name: String,
    val email: String,
    val level: String = "STANDARD"
)

enum class OrderStatus {
    PENDING, CONFIRMED, SHIPPED, COMPLETED, CANCELLED
}

data class Order(
    val id: Int = 0,
    val customerId: Int,
    val status: OrderStatus = OrderStatus.PENDING,
    val totalAmount: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val items: List<OrderItem> = emptyList()
)

data class OrderItem(
    val id: Int = 0,
    val orderId: Int = 0,
    val productId: Int,
    val quantity: Int,
    val unitPrice: Long = 0
)

data class CreateOrderRequest(
    val customerId: Int,
    val items: List<OrderItemRequest>
)

data class OrderItemRequest(
    val productId: Int,
    val quantity: Int
)

// ─── Events ──────────────────────────────────────────────

data class OrderCreatedEvent(
    val orderId: Int,
    val customerId: Int,
    val totalAmount: Long,
    val itemCount: Int
) : KeelEvent

data class OrderCancelledEvent(
    val orderId: Int,
    val reason: String
) : KeelEvent

data class InventoryDeductedEvent(
    val productId: Int,
    val quantityDeducted: Int,
    val remainingStock: Int
) : KeelEvent
