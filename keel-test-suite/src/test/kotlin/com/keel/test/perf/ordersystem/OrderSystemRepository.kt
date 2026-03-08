package com.keel.test.perf.ordersystem

import com.keel.db.database.KeelDatabase
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.update

/**
 * Repository layer for OrderSystem stress tests.
 * Provides CRUD operations for Products, Customers, Orders, and OrderItems.
 */
class OrderSystemRepository(private val db: KeelDatabase) {

    // ─── Product Operations ──────────────────────────────────

    fun createProduct(product: Product): Product = db.transaction {
        val id = ProductsTable.insert {
            it[name] = product.name
            it[price] = product.price
            it[stock] = product.stock
        } get ProductsTable.id
        product.copy(id = id)
    }

    fun getProduct(id: Int): Product? = db.transaction {
        ProductsTable.selectAll().where { ProductsTable.id eq id }.firstOrNull()?.let { row ->
            Product(
                id = row[ProductsTable.id],
                name = row[ProductsTable.name],
                price = row[ProductsTable.price],
                stock = row[ProductsTable.stock]
            )
        }
    }

    fun getStock(productId: Int): Int = db.transaction {
        ProductsTable.selectAll().where { ProductsTable.id eq productId }
            .firstOrNull()?.get(ProductsTable.stock) ?: 0
    }

    /**
     * Deduct stock atomically. Returns the remaining stock, or -1 if insufficient.
     * Uses optimistic concurrency: UPDATE ... WHERE stock >= quantity.
     */
    fun deductStock(productId: Int, quantity: Int): Int = db.transaction {
        val updatedRows = if (quantity > 0) {
            ProductsTable.update({ (ProductsTable.id eq productId) and (ProductsTable.stock greaterEq quantity) }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[stock] = stock - quantity
                }
            }
        } else {
            ProductsTable.update({ ProductsTable.id eq productId }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[stock] = stock - quantity
                }
            }
        }
        if (updatedRows == 0) {
            return@transaction -1
        }
        // Fetch remaining stock
        ProductsTable.selectAll().where { ProductsTable.id eq productId }
            .firstOrNull()?.get(ProductsTable.stock) ?: -1
    }

    fun countProducts(): Long = db.transaction {
        ProductsTable.selectAll().count()
    }

    // ─── Customer Operations ─────────────────────────────────

    fun createCustomer(customer: Customer): Customer = db.transaction {
        val id = CustomersTable.insert {
            it[name] = customer.name
            it[email] = customer.email
            it[level] = customer.level
        } get CustomersTable.id
        customer.copy(id = id)
    }

    // ─── Order Operations ────────────────────────────────────

    fun createOrder(customerId: Int, totalAmount: Long): Int = db.transaction {
        OrdersTable.insert {
            it[OrdersTable.customerId] = customerId
            it[status] = OrderStatus.PENDING.name
            it[OrdersTable.totalAmount] = totalAmount
            it[createdAt] = System.currentTimeMillis()
        } get OrdersTable.id
    }

    fun addOrderItem(orderId: Int, productId: Int, quantity: Int, unitPrice: Long): Int = db.transaction {
        OrderItemsTable.insert {
            it[OrderItemsTable.orderId] = orderId
            it[OrderItemsTable.productId] = productId
            it[OrderItemsTable.quantity] = quantity
            it[OrderItemsTable.unitPrice] = unitPrice
        } get OrderItemsTable.id
    }

    fun updateOrderStatus(orderId: Int, newStatus: OrderStatus): Boolean = db.transaction {
        OrdersTable.update({ OrdersTable.id eq orderId }) {
            it[status] = newStatus.name
        } > 0
    }

    fun getOrderStatus(orderId: Int): OrderStatus? = db.transaction {
        OrdersTable.selectAll().where { OrdersTable.id eq orderId }
            .firstOrNull()?.get(OrdersTable.status)?.let { OrderStatus.valueOf(it) }
    }

    fun countOrders(): Long = db.transaction {
        OrdersTable.selectAll().count()
    }

    fun countOrdersByStatus(status: OrderStatus): Long = db.transaction {
        OrdersTable.selectAll().where { OrdersTable.status eq status.name }.count()
    }

    fun getOrderItems(orderId: Int): List<OrderItem> = db.transaction {
        OrderItemsTable.selectAll().where { OrderItemsTable.orderId eq orderId }.map { row ->
            OrderItem(
                id = row[OrderItemsTable.id],
                orderId = row[OrderItemsTable.orderId],
                productId = row[OrderItemsTable.productId],
                quantity = row[OrderItemsTable.quantity],
                unitPrice = row[OrderItemsTable.unitPrice]
            )
        }
    }

    fun countOrderItems(): Long = db.transaction {
        OrderItemsTable.selectAll().count()
    }
}
