package com.keel.test.perf.ordersystem

import com.keel.db.table.PluginTable
import org.jetbrains.exposed.sql.Column

/**
 * Database tables for the OrderSystem stress test module.
 * Simulates an enterprise-level order management system with 4 related tables.
 */

object ProductsTable : PluginTable("ordersys", "products") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 200)
    val price: Column<Long> = long("price")  // price in cents to avoid floating point
    val stock: Column<Int> = integer("stock")
    override val primaryKey = PrimaryKey(id)
}

object CustomersTable : PluginTable("ordersys", "customers") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 100)
    val email: Column<String> = varchar("email", 200)
    val level: Column<String> = varchar("level", 20).default("STANDARD")  // STANDARD, VIP, ENTERPRISE
    override val primaryKey = PrimaryKey(id)
}

object OrdersTable : PluginTable("ordersys", "orders") {
    val id: Column<Int> = integer("id").autoIncrement()
    val customerId: Column<Int> = integer("customer_id").references(CustomersTable.id)
    val status: Column<String> = varchar("status", 20).default("PENDING")  // PENDING, CONFIRMED, SHIPPED, COMPLETED, CANCELLED
    val totalAmount: Column<Long> = long("total_amount").default(0)  // in cents
    val createdAt: Column<Long> = long("created_at")  // epoch millis
    override val primaryKey = PrimaryKey(id)
}

object OrderItemsTable : PluginTable("ordersys", "order_items") {
    val id: Column<Int> = integer("id").autoIncrement()
    val orderId: Column<Int> = integer("order_id").references(OrdersTable.id)
    val productId: Column<Int> = integer("product_id").references(ProductsTable.id)
    val quantity: Column<Int> = integer("quantity")
    val unitPrice: Column<Long> = long("unit_price")  // snapshot of price at time of order, in cents
    override val primaryKey = PrimaryKey(id)
}
