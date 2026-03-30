package com.keel.samples.ordersample

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column

object ProductsTable : AuditPluginTable("ordersample", "products") {
    val productId: Column<String> = varchar("product_id", 64)
    val title: Column<String> = varchar("title", 160)
    val description: Column<String> = text("description").default("")
    val priceCents: Column<Int> = integer("price_cents")
    val badge: Column<String> = varchar("badge", 40).default("Featured")

    override val primaryKey = PrimaryKey(productId)
}

object CartItemsTable : AuditPluginTable("ordersample", "cart_items") {
    val id: Column<Int> = integer("id").autoIncrement()
    val ownerUserId: Column<String> = varchar("owner_user_id", 64)
    val productId: Column<String> = varchar("product_id", 64)
    val quantity: Column<Int> = integer("quantity")

    override val primaryKey = PrimaryKey(id)
}

object OrdersTable : AuditPluginTable("ordersample", "orders") {
    val orderId: Column<String> = varchar("order_id", 64)
    val ownerUserId: Column<String> = varchar("owner_user_id", 64)
    val ownerDisplayName: Column<String> = varchar("owner_display_name", 120)
    val status: Column<String> = varchar("status", 32)
    val totalCents: Column<Int> = integer("total_cents")
    val note: Column<String> = text("note").default("")

    override val primaryKey = PrimaryKey(orderId)
}

object OrderItemsTable : AuditPluginTable("ordersample", "order_items") {
    val id: Column<Int> = integer("id").autoIncrement()
    val orderId: Column<String> = varchar("order_id", 64)
    val productId: Column<String> = varchar("product_id", 64)
    val title: Column<String> = varchar("title", 160)
    val quantity: Column<Int> = integer("quantity")
    val unitPriceCents: Column<Int> = integer("unit_price_cents")

    override val primaryKey = PrimaryKey(id)
}

enum class CommerceOrderStatus {
    PLACED,
    PROCESSING,
    FULFILLED,
    CANCELLED;

    companion object {
        fun parse(value: String): CommerceOrderStatus {
            return runCatching { valueOf(value.trim().uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unsupported status: $value") }
        }
    }
}
