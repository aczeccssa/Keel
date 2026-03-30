package com.keel.samples.ordersample

import com.keel.openapi.annotations.KeelApiField
import com.keel.openapi.annotations.KeelApiSchema
import kotlinx.serialization.Serializable

@KeelApiSchema
@Serializable
data class CatalogListData(
    @KeelApiField(description = "Public catalog entries", example = "[]")
    val items: List<CatalogItemData>,
    @KeelApiField(description = "Total catalog items", example = "4")
    val total: Int
)

@KeelApiSchema
@Serializable
data class CatalogItemData(
    @KeelApiField(description = "Product identifier", example = "sku-desk-lamp")
    val productId: String,
    @KeelApiField(description = "Display title", example = "Desk Lamp")
    val title: String,
    @KeelApiField(description = "Product copy", example = "Warm lighting for late-night coding.")
    val description: String,
    @KeelApiField(description = "Unit price in cents", example = "5900")
    val priceCents: Int,
    @KeelApiField(description = "Visual badge shown in the UI", example = "Workspace")
    val badge: String
)

@KeelApiSchema
@Serializable
data class AddCartItemRequest(
    @KeelApiField(description = "Product identifier to add", example = "sku-desk-lamp")
    val productId: String,
    @KeelApiField(description = "How many units to add", example = "1")
    val quantity: Int = 1
)

@KeelApiSchema
@Serializable
data class CartData(
    @KeelApiField(description = "Current cart owner", example = "user-1")
    val ownerUserId: String,
    @KeelApiField(description = "Items currently in the cart", example = "[]")
    val items: List<CartItemData>,
    @KeelApiField(description = "Number of active line items", example = "2")
    val totalItems: Int,
    @KeelApiField(description = "Subtotal amount in cents", example = "15400")
    val subtotalCents: Int
)

@KeelApiSchema
@Serializable
data class CartItemData(
    @KeelApiField(description = "Product identifier", example = "sku-desk-lamp")
    val productId: String,
    @KeelApiField(description = "Display title", example = "Desk Lamp")
    val title: String,
    @KeelApiField(description = "Badge from the catalog", example = "Workspace")
    val badge: String,
    @KeelApiField(description = "Quantity in cart", example = "2")
    val quantity: Int,
    @KeelApiField(description = "Unit price in cents", example = "5900")
    val unitPriceCents: Int,
    @KeelApiField(description = "Line total in cents", example = "11800")
    val lineTotalCents: Int
)

@KeelApiSchema
@Serializable
data class CheckoutRequest(
    @KeelApiField(description = "Optional checkout note saved with the order", example = "Ship with the next batch.")
    val note: String = ""
)

@KeelApiSchema
@Serializable
data class CheckoutResponse(
    @KeelApiField(description = "Created order identifier", example = "ord-1003")
    val orderId: String,
    @KeelApiField(description = "Resulting order status", example = "PLACED")
    val status: String,
    @KeelApiField(description = "Order total in cents", example = "15400")
    val totalCents: Int,
    @KeelApiField(description = "Persisted checkout note", example = "Ship with the next batch.")
    val note: String,
    @KeelApiField(description = "How many cart items remain after checkout", example = "0")
    val remainingCartItems: Int
)

@KeelApiSchema
@Serializable
data class OrderListData(
    @KeelApiField(description = "Visible orders for the current viewer", example = "[]")
    val orders: List<OrderSummaryData>,
    @KeelApiField(description = "Total visible orders", example = "3")
    val total: Int
)

@KeelApiSchema
@Serializable
data class OrderSummaryData(
    @KeelApiField(description = "Order identifier", example = "ord-1002")
    val orderId: String,
    @KeelApiField(description = "Owner user ID", example = "user-1")
    val ownerUserId: String,
    @KeelApiField(description = "Owner display name", example = "Sample User")
    val ownerDisplayName: String,
    @KeelApiField(description = "Order status", example = "PROCESSING")
    val status: String,
    @KeelApiField(description = "Total amount in cents", example = "15400")
    val totalCents: Int,
    @KeelApiField(description = "How many distinct line items are in the order", example = "2")
    val itemCount: Int,
    @KeelApiField(description = "Whether the viewer can cancel the order", example = "false")
    val canCancel: Boolean,
    @KeelApiField(description = "Creation timestamp", example = "2026-03-18T08:00:00Z")
    val createdAt: String,
    @KeelApiField(description = "Optional customer note", example = "Please ship before Friday.")
    val note: String
)

@KeelApiSchema
@Serializable
data class OrderDetailData(
    @KeelApiField(description = "Order identifier", example = "ord-1002")
    val orderId: String,
    @KeelApiField(description = "Owner user ID", example = "user-1")
    val ownerUserId: String,
    @KeelApiField(description = "Owner display name", example = "Sample User")
    val ownerDisplayName: String,
    @KeelApiField(description = "Order status", example = "PROCESSING")
    val status: String,
    @KeelApiField(description = "Total amount in cents", example = "15400")
    val totalCents: Int,
    @KeelApiField(description = "Optional checkout note", example = "Please ship before Friday.")
    val note: String,
    @KeelApiField(description = "Line items for the order", example = "[]")
    val items: List<OrderItemData>,
    @KeelApiField(description = "Audit information copied from AuditPluginTable", example = "{}")
    val audit: OrderAuditData
)

@KeelApiSchema
@Serializable
data class OrderItemData(
    @KeelApiField(description = "Product identifier", example = "sku-desk-lamp")
    val productId: String,
    @KeelApiField(description = "Display title", example = "Desk Lamp")
    val title: String,
    @KeelApiField(description = "Quantity for this line", example = "2")
    val quantity: Int,
    @KeelApiField(description = "Unit price in cents", example = "5900")
    val unitPriceCents: Int,
    @KeelApiField(description = "Line total in cents", example = "11800")
    val lineTotalCents: Int
)

@KeelApiSchema
@Serializable
data class OrderAuditData(
    @KeelApiField(description = "Creation timestamp", example = "2026-03-18T08:00:00Z")
    val createdAt: String,
    @KeelApiField(description = "Last update timestamp", example = "2026-03-18T08:10:00Z")
    val updatedAt: String,
    @KeelApiField(description = "User or system that created the record", example = "user-1")
    val createdBy: String?,
    @KeelApiField(description = "User or system that last updated the record", example = "admin-1")
    val updatedBy: String?
)

@KeelApiSchema
@Serializable
data class OrderStatusUpdateRequest(
    @KeelApiField(description = "Target admin status", example = "PROCESSING")
    val status: String
)

@KeelApiSchema
@Serializable
data class OrderAdminSummary(
    @KeelApiField(description = "Total persisted orders", example = "3")
    val totalOrders: Int,
    @KeelApiField(description = "Orders waiting for admin action", example = "1")
    val placedOrders: Int,
    @KeelApiField(description = "Orders currently processing", example = "1")
    val processingOrders: Int,
    @KeelApiField(description = "Completed orders", example = "1")
    val fulfilledOrders: Int,
    @KeelApiField(description = "Cancelled orders", example = "0")
    val cancelledOrders: Int,
    @KeelApiField(description = "Revenue across non-cancelled orders in cents", example = "24400")
    val totalRevenueCents: Int
)

@KeelApiSchema
@Serializable
data class OrderStatsData(
    @KeelApiField(description = "Catalog item count", example = "4")
    val productCount: Int,
    @KeelApiField(description = "Active cart line count", example = "2")
    val cartItemCount: Int,
    @KeelApiField(description = "Persisted order count", example = "3")
    val orderCount: Int,
    @KeelApiField(description = "Orders currently in processing state", example = "1")
    val processingCount: Int,
    @KeelApiField(description = "Physical table names created through PluginTable prefixing", example = "[\"ordersample_products\"]")
    val tableNames: List<String>,
    @KeelApiField(description = "Audit columns provided by AuditPluginTable", example = "[\"created_at\",\"updated_at\"]")
    val auditColumns: List<String>,
    @KeelApiField(description = "Database engine name", example = "H2 In-Memory")
    val databaseType: String
)
