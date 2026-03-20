package com.keel.samples.ordersample

import com.keel.db.database.KeelDatabase
import com.keel.db.repository.BaseRepository
import com.keel.db.table.AuditTable
import com.keel.db.table.SoftDeletableTable
import com.keel.db.table.TimestampTable
import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.commerce.CommerceCatalog
import com.keel.samples.commerce.CommerceDemoIdentity
import com.keel.samples.commerce.CommercePrincipal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class OrderRepository(
    database: KeelDatabase
) : BaseRepository<OrdersTable>(database, OrdersTable) {

    fun initializeSchema() {
        database.createTables(
            ProductsTable,
            CartItemsTable,
            OrdersTable,
            OrderItemsTable
        )
    }

    fun seedData() {
        transaction {
            val now = Clock.System.now()
            if (ProductsTable.selectAll().count() == 0L) {
                synchronizeCatalog(now = now, actor = "system")
            }
            if (OrdersTable.selectAll().count() == 0L) {
                seedOrder(
                    orderId = "ord-1001",
                    owner = requireNotNull(CommerceDemoIdentity.principalByUserId("user-1")),
                    status = CommerceOrderStatus.PLACED,
                    note = "Leave at the front desk.",
                    items = listOf(
                        SeedOrderItem("sku-desk-lamp", "Desk Lamp", 1, 5_900),
                        SeedOrderItem("sku-mech-keyboard", "Mechanical Keyboard", 1, 12_900)
                    ),
                    actor = "seed-user",
                    now = now
                )
                seedOrder(
                    orderId = "ord-2001",
                    owner = requireNotNull(CommerceDemoIdentity.principalByUserId("admin-1")),
                    status = CommerceOrderStatus.PROCESSING,
                    note = "Prioritize this workstation refresh.",
                    items = listOf(
                        SeedOrderItem("sku-monitor-arm", "Monitor Arm", 1, 8_400),
                        SeedOrderItem("sku-headphones", "Studio Headphones", 1, 9_900)
                    ),
                    actor = "seed-admin",
                    now = now
                )
            }
        }
    }

    fun listCatalog(): CatalogListData = transaction {
        synchronizeCatalog()
        val items = ProductsTable.selectAll()
            .where { ProductsTable.deletedAt.isNull() }
            .orderBy(ProductsTable.title to SortOrder.ASC)
            .map(::toCatalogItem)
        CatalogListData(items = items, total = items.size)
    }

    fun getCart(userId: String): CartData = transaction {
        synchronizeCatalog()
        buildCart(userId)
    }

    fun addCartItem(principal: CommercePrincipal, request: AddCartItemRequest): CartData = transaction {
        synchronizeCatalog(actor = principal.userId)
        val requestedQuantity = request.quantity.coerceAtLeast(0)
        if (requestedQuantity <= 0) {
            throw PluginApiException(400, "Quantity must be greater than 0")
        }

        val product = ProductsTable.selectAll()
            .where { (ProductsTable.productId eq request.productId) and ProductsTable.deletedAt.isNull() }
            .firstOrNull()
            ?: throw PluginApiException(404, "Product not found")

        val now = Clock.System.now()
        val existing = CartItemsTable.selectAll()
            .where {
                (CartItemsTable.ownerUserId eq principal.userId) and
                    (CartItemsTable.productId eq request.productId)
            }
            .firstOrNull()

        if (existing == null) {
            CartItemsTable.insert {
                it[ownerUserId] = principal.userId
                it[productId] = product[ProductsTable.productId]
                it[quantity] = requestedQuantity
                it[createdAt] = now
                it[updatedAt] = now
                it[createdBy] = principal.userId
                it[updatedBy] = principal.userId
                it[deletedAt] = null
            }
        } else {
            val existingQuantity = if (existing[CartItemsTable.deletedAt] == null) existing[CartItemsTable.quantity] else 0
            CartItemsTable.update({ CartItemsTable.id eq existing[CartItemsTable.id] }) {
                it[quantity] = existingQuantity + requestedQuantity
                it[updatedAt] = now
                it[updatedBy] = principal.userId
                it[deletedAt] = null
            }
        }

        buildCart(principal.userId)
    }

    fun removeCartItem(principal: CommercePrincipal, productIdValue: String): CartData = transaction {
        synchronizeCatalog(actor = principal.userId)
        val now = Clock.System.now()
        val updated = CartItemsTable.update({
            (CartItemsTable.ownerUserId eq principal.userId) and
                (CartItemsTable.productId eq productIdValue) and
                CartItemsTable.deletedAt.isNull()
        }) {
            it[deletedAt] = now
            it[updatedAt] = now
            it[updatedBy] = principal.userId
        }

        if (updated == 0) {
            throw PluginApiException(404, "Cart item not found")
        }

        buildCart(principal.userId)
    }

    fun checkout(principal: CommercePrincipal, request: CheckoutRequest): CheckoutResponse = transaction {
        synchronizeCatalog(actor = principal.userId)
        val cartRows = activeCartRows(principal.userId)
        if (cartRows.isEmpty()) {
            throw PluginApiException(400, "Cart is empty")
        }

        val now = Clock.System.now()
        val orderId = nextOrderId()
        val totalCents = cartRows.sumOf { row ->
            row[CartItemsTable.quantity] * row[ProductsTable.priceCents]
        }
        val trimmedNote = request.note.trim()

        OrdersTable.insert {
            it[OrdersTable.orderId] = orderId
            it[ownerUserId] = principal.userId
            it[ownerDisplayName] = principal.displayName
            it[status] = CommerceOrderStatus.PLACED.name
            it[OrdersTable.totalCents] = totalCents
            it[note] = trimmedNote
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = principal.userId
            it[updatedBy] = principal.userId
            it[deletedAt] = null
        }

        cartRows.forEach { row ->
            OrderItemsTable.insert {
                it[OrderItemsTable.orderId] = orderId
                it[productId] = row[ProductsTable.productId]
                it[title] = row[ProductsTable.title]
                it[quantity] = row[CartItemsTable.quantity]
                it[unitPriceCents] = row[ProductsTable.priceCents]
                it[createdAt] = now
                it[updatedAt] = now
                it[createdBy] = principal.userId
                it[updatedBy] = principal.userId
                it[deletedAt] = null
            }
        }

        CartItemsTable.update({
            (CartItemsTable.ownerUserId eq principal.userId) and CartItemsTable.deletedAt.isNull()
        }) {
            it[deletedAt] = now
            it[updatedAt] = now
            it[updatedBy] = principal.userId
        }

        CheckoutResponse(
            orderId = orderId,
            status = CommerceOrderStatus.PLACED.name,
            totalCents = totalCents,
            note = trimmedNote,
            remainingCartItems = 0
        )
    }

    fun listOrdersFor(principal: CommercePrincipal): OrderListData = transaction {
        synchronizeCatalog(actor = principal.userId)
        val rows = OrdersTable.selectAll()
            .where {
                (OrdersTable.ownerUserId eq principal.userId) and OrdersTable.deletedAt.isNull()
            }
            .orderBy(OrdersTable.createdAt to SortOrder.DESC)
            .toList()

        val orders = rows.map { toOrderSummary(it, principal) }
        OrderListData(orders = orders, total = orders.size)
    }

    fun getOrderDetail(orderIdValue: String, viewer: CommercePrincipal): OrderDetailData = transaction {
        synchronizeCatalog(actor = viewer.userId)
        val orderRow = findOrder(orderIdValue)
        ensureVisible(orderRow, viewer)
        buildOrderDetail(orderRow)
    }

    fun cancelOrder(orderIdValue: String, viewer: CommercePrincipal): OrderDetailData = transaction {
        synchronizeCatalog(actor = viewer.userId)
        val orderRow = findOrder(orderIdValue)
        if (orderRow[OrdersTable.ownerUserId] != viewer.userId) {
            throw PluginApiException(403, "Only the owner can cancel this order")
        }
        if (orderRow[OrdersTable.status] != CommerceOrderStatus.PLACED.name) {
            throw PluginApiException(409, "Only placed orders can be cancelled")
        }

        val now = Clock.System.now()
        OrdersTable.update({ OrdersTable.orderId eq orderIdValue }) {
            it[status] = CommerceOrderStatus.CANCELLED.name
            it[updatedAt] = now
            it[updatedBy] = viewer.userId
        }

        buildOrderDetail(findOrder(orderIdValue))
    }

    fun listAllOrders(): OrderListData = transaction {
        synchronizeCatalog(actor = "admin-1")
        val rows = OrdersTable.selectAll()
            .where { OrdersTable.deletedAt.isNull() }
            .orderBy(OrdersTable.createdAt to SortOrder.DESC)
            .toList()

        val adminViewer = requireNotNull(CommerceDemoIdentity.principalByUserId("admin-1"))
        val orders = rows.map { toOrderSummary(it, adminViewer) }
        OrderListData(orders = orders, total = orders.size)
    }

    fun updateOrderStatus(orderIdValue: String, request: OrderStatusUpdateRequest, actor: CommercePrincipal): OrderDetailData = transaction {
        synchronizeCatalog(actor = actor.userId)
        val orderRow = findOrder(orderIdValue)
        val currentStatus = CommerceOrderStatus.parse(orderRow[OrdersTable.status])
        val nextStatus = try {
            CommerceOrderStatus.parse(request.status)
        } catch (_: IllegalArgumentException) {
            throw PluginApiException(400, "Unsupported status")
        }

        val allowed = when (currentStatus) {
            CommerceOrderStatus.PLACED -> nextStatus == CommerceOrderStatus.PROCESSING
            CommerceOrderStatus.PROCESSING -> nextStatus == CommerceOrderStatus.FULFILLED
            CommerceOrderStatus.FULFILLED,
            CommerceOrderStatus.CANCELLED -> false
        }
        if (!allowed) {
            throw PluginApiException(409, "Invalid status transition")
        }

        val now = Clock.System.now()
        OrdersTable.update({ OrdersTable.orderId eq orderIdValue }) {
            it[status] = nextStatus.name
            it[updatedAt] = now
            it[updatedBy] = actor.userId
        }

        buildOrderDetail(findOrder(orderIdValue))
    }

    fun adminSummary(): OrderAdminSummary = transaction {
        synchronizeCatalog(actor = "admin-1")
        val rows = OrdersTable.selectAll()
            .where { OrdersTable.deletedAt.isNull() }
            .toList()

        OrderAdminSummary(
            totalOrders = rows.size,
            placedOrders = rows.count { it[OrdersTable.status] == CommerceOrderStatus.PLACED.name },
            processingOrders = rows.count { it[OrdersTable.status] == CommerceOrderStatus.PROCESSING.name },
            fulfilledOrders = rows.count { it[OrdersTable.status] == CommerceOrderStatus.FULFILLED.name },
            cancelledOrders = rows.count { it[OrdersTable.status] == CommerceOrderStatus.CANCELLED.name },
            totalRevenueCents = rows
                .filterNot { it[OrdersTable.status] == CommerceOrderStatus.CANCELLED.name }
                .sumOf { it[OrdersTable.totalCents] }
        )
    }

    fun stats(): OrderStatsData = transaction {
        synchronizeCatalog()
        OrderStatsData(
            productCount = ProductsTable.selectAll().where { ProductsTable.deletedAt.isNull() }.count().toInt(),
            cartItemCount = CartItemsTable.selectAll().where { CartItemsTable.deletedAt.isNull() }.count().toInt(),
            orderCount = OrdersTable.selectAll().where { OrdersTable.deletedAt.isNull() }.count().toInt(),
            processingCount = OrdersTable.selectAll().where {
                (OrdersTable.status eq CommerceOrderStatus.PROCESSING.name) and OrdersTable.deletedAt.isNull()
            }.count().toInt(),
            tableNames = listOf(
                ProductsTable.physicalName(),
                CartItemsTable.physicalName(),
                OrdersTable.physicalName(),
                OrderItemsTable.physicalName()
            ),
            auditColumns = listOf(
                TimestampTable.CREATED_AT_COLUMN,
                TimestampTable.UPDATED_AT_COLUMN,
                SoftDeletableTable.DELETED_AT_COLUMN,
                AuditTable.CREATED_BY_COLUMN,
                AuditTable.UPDATED_BY_COLUMN
            ),
            databaseType = "H2 In-Memory"
        )
    }

    private fun seedProduct(
        productIdValue: String,
        titleValue: String,
        descriptionValue: String,
        priceCentsValue: Int,
        badgeValue: String,
        now: Instant
    ) {
        ProductsTable.insert {
            it[productId] = productIdValue
            it[title] = titleValue
            it[description] = descriptionValue
            it[priceCents] = priceCentsValue
            it[badge] = badgeValue
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = "system"
            it[updatedBy] = "system"
            it[deletedAt] = null
        }
    }

    private fun synchronizeCatalog(
        now: Instant = Clock.System.now(),
        actor: String = "system"
    ) {
        val snapshot = CommerceCatalog.products()
        val activeIds = snapshot.map { it.productId }.toSet()
        val existingRows = ProductsTable.selectAll().toList().associateBy { it[ProductsTable.productId] }

        snapshot.forEach { product ->
            val existing = existingRows[product.productId]
            if (existing == null) {
                seedProduct(
                    productIdValue = product.productId,
                    titleValue = product.title,
                    descriptionValue = product.description,
                    priceCentsValue = product.priceCents,
                    badgeValue = product.badge,
                    now = now
                )
            } else {
                ProductsTable.update({ ProductsTable.productId eq product.productId }) {
                    it[title] = product.title
                    it[description] = product.description
                    it[priceCents] = product.priceCents
                    it[badge] = product.badge
                    it[updatedAt] = now
                    it[updatedBy] = actor
                    it[deletedAt] = null
                }
            }
        }

        existingRows.keys.filterNot(activeIds::contains).forEach { productIdValue ->
            ProductsTable.update({ ProductsTable.productId eq productIdValue }) {
                it[deletedAt] = now
                it[updatedAt] = now
                it[updatedBy] = actor
            }
        }
    }

    private fun seedOrder(
        orderId: String,
        owner: CommercePrincipal,
        status: CommerceOrderStatus,
        note: String,
        items: List<SeedOrderItem>,
        actor: String,
        now: Instant
    ) {
        OrdersTable.insert {
            it[OrdersTable.orderId] = orderId
            it[ownerUserId] = owner.userId
            it[ownerDisplayName] = owner.displayName
            it[OrdersTable.status] = status.name
            it[totalCents] = items.sumOf { item -> item.quantity * item.unitPriceCents }
            it[OrdersTable.note] = note
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = actor
            it[updatedBy] = actor
            it[deletedAt] = null
        }

        items.forEach { item ->
            OrderItemsTable.insert {
                it[OrderItemsTable.orderId] = orderId
                it[productId] = item.productId
                it[title] = item.title
                it[quantity] = item.quantity
                it[unitPriceCents] = item.unitPriceCents
                it[createdAt] = now
                it[updatedAt] = now
                it[createdBy] = actor
                it[updatedBy] = actor
                it[deletedAt] = null
            }
        }
    }

    private fun buildCart(userId: String): CartData {
        val rows = activeCartRows(userId)
        val items = rows.map { row ->
            val quantity = row[CartItemsTable.quantity]
            val unitPrice = row[ProductsTable.priceCents]
            CartItemData(
                productId = row[ProductsTable.productId],
                title = row[ProductsTable.title],
                badge = row[ProductsTable.badge],
                quantity = quantity,
                unitPriceCents = unitPrice,
                lineTotalCents = quantity * unitPrice
            )
        }
        return CartData(
            ownerUserId = userId,
            items = items,
            totalItems = items.sumOf { it.quantity },
            subtotalCents = items.sumOf { it.lineTotalCents }
        )
    }

    private fun activeCartRows(userId: String): List<ResultRow> {
        return CartItemsTable.join(
            ProductsTable,
            JoinType.INNER,
            additionalConstraint = { CartItemsTable.productId eq ProductsTable.productId }
        )
            .selectAll()
            .where {
                (CartItemsTable.ownerUserId eq userId) and
                    CartItemsTable.deletedAt.isNull() and
                    ProductsTable.deletedAt.isNull()
            }
            .orderBy(CartItemsTable.createdAt to SortOrder.ASC)
            .toList()
    }

    private fun findOrder(orderIdValue: String): ResultRow {
        return OrdersTable.selectAll()
            .where { (OrdersTable.orderId eq orderIdValue) and OrdersTable.deletedAt.isNull() }
            .firstOrNull()
            ?: throw PluginApiException(404, "Order not found")
    }

    private fun ensureVisible(orderRow: ResultRow, viewer: CommercePrincipal) {
        val isOwner = orderRow[OrdersTable.ownerUserId] == viewer.userId
        if (!isOwner && viewer.role != "admin") {
            throw PluginApiException(403, "Forbidden")
        }
    }

    private fun buildOrderDetail(orderRow: ResultRow): OrderDetailData {
        val items = OrderItemsTable.selectAll()
            .where { (OrderItemsTable.orderId eq orderRow[OrdersTable.orderId]) and OrderItemsTable.deletedAt.isNull() }
            .orderBy(OrderItemsTable.createdAt to SortOrder.ASC)
            .map { row ->
                OrderItemData(
                    productId = row[OrderItemsTable.productId],
                    title = row[OrderItemsTable.title],
                    quantity = row[OrderItemsTable.quantity],
                    unitPriceCents = row[OrderItemsTable.unitPriceCents],
                    lineTotalCents = row[OrderItemsTable.quantity] * row[OrderItemsTable.unitPriceCents]
                )
            }

        return OrderDetailData(
            orderId = orderRow[OrdersTable.orderId],
            ownerUserId = orderRow[OrdersTable.ownerUserId],
            ownerDisplayName = orderRow[OrdersTable.ownerDisplayName],
            status = orderRow[OrdersTable.status],
            totalCents = orderRow[OrdersTable.totalCents],
            note = orderRow[OrdersTable.note],
            items = items,
            audit = OrderAuditData(
                createdAt = orderRow[OrdersTable.createdAt].toString(),
                updatedAt = orderRow[OrdersTable.updatedAt].toString(),
                createdBy = orderRow[OrdersTable.createdBy],
                updatedBy = orderRow[OrdersTable.updatedBy]
            )
        )
    }

    private fun toCatalogItem(row: ResultRow): CatalogItemData {
        return CatalogItemData(
            productId = row[ProductsTable.productId],
            title = row[ProductsTable.title],
            description = row[ProductsTable.description],
            priceCents = row[ProductsTable.priceCents],
            badge = row[ProductsTable.badge]
        )
    }

    private fun toOrderSummary(row: ResultRow, viewer: CommercePrincipal): OrderSummaryData {
        val orderIdValue = row[OrdersTable.orderId]
        val itemCount = OrderItemsTable.selectAll()
            .where { (OrderItemsTable.orderId eq orderIdValue) and OrderItemsTable.deletedAt.isNull() }
            .count()
            .toInt()
        val isOwner = row[OrdersTable.ownerUserId] == viewer.userId
        val canCancel = isOwner && row[OrdersTable.status] == CommerceOrderStatus.PLACED.name

        return OrderSummaryData(
            orderId = orderIdValue,
            ownerUserId = row[OrdersTable.ownerUserId],
            ownerDisplayName = row[OrdersTable.ownerDisplayName],
            status = row[OrdersTable.status],
            totalCents = row[OrdersTable.totalCents],
            itemCount = itemCount,
            canCancel = canCancel,
            createdAt = row[OrdersTable.createdAt].toString(),
            note = row[OrdersTable.note]
        )
    }

    private fun nextOrderId(): String {
        val nextNumber = OrdersTable.selectAll()
            .where { OrdersTable.deletedAt.isNull() }
            .count()
            .toInt() + 1001
        return "ord-$nextNumber"
    }

    private data class SeedOrderItem(
        val productId: String,
        val title: String,
        val quantity: Int,
        val unitPriceCents: Int
    )
}
