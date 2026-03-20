package com.keel.samples.ordersample

import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.KeelInterceptorResult
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.annotations.KeelInterceptors
import com.keel.openapi.runtime.OpenApiDoc
import com.keel.samples.commerce.CommerceDemoIdentity
import com.keel.samples.commerce.CommercePrincipal
import org.koin.dsl.module

@KeelApiPlugin(
    pluginId = "ordersample",
    title = "Order Sample Plugin",
    description = "A database-backed commerce sample with cart, checkout, orders, and admin order operations",
    version = "1.0.0"
)
@KeelInterceptors(OrderAuthInterceptor::class)
class OrderSamplePlugin : StandardKeelPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "ordersample",
        version = "1.0.0",
        displayName = "Order Sample Plugin",
        defaultRuntimeMode = PluginRuntimeMode.IN_PROCESS
    )

    private lateinit var database: KeelDatabase
    private lateinit var dbFactory: DatabaseFactory
    private lateinit var repository: OrderRepository

    override fun modules() = listOf(
        module {
            single { OrderAuthService() }
            single { OrderAuthInterceptor(get()) }
            single { OrderAdminInterceptor(get()) }
        }
    )

    override suspend fun onInit(context: PluginInitContext) {
        dbFactory = DatabaseFactory.h2Memory(name = "ordersample", poolSize = 5)
        database = dbFactory.init()
        repository = OrderRepository(database)
        repository.initializeSchema()
        repository.seedData()
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        interceptors(OrderAuthInterceptor::class)

        get<CartData>(
            "/cart",
            doc = OpenApiDoc(summary = "Get the current user's cart", tags = listOf("commerce", "cart"), responseEnvelope = true)
        ) {
            val viewer = requireViewer()
            PluginResult(body = repository.getCart(viewer.userId))
        }

        post<AddCartItemRequest, CartData>(
            "/cart/items",
            doc = OpenApiDoc(summary = "Add an item to the cart", tags = listOf("commerce", "cart"), errorStatuses = setOf(400, 404), responseEnvelope = true)
        ) { request ->
            PluginResult(body = repository.addCartItem(requireViewer(), request))
        }

        route("/cart/items/{productId}") {
            delete<CartData>(
                doc = OpenApiDoc(summary = "Remove an item from the cart", tags = listOf("commerce", "cart"), errorStatuses = setOf(404), responseEnvelope = true)
            ) {
                val productId = pathParameters["productId"] ?: throw PluginApiException(400, "Missing productId")
                PluginResult(body = repository.removeCartItem(requireViewer(), productId))
            }
        }

        post<CheckoutRequest, CheckoutResponse>(
            "/checkout",
            doc = OpenApiDoc(summary = "Checkout the current cart into an order", tags = listOf("commerce", "checkout"), errorStatuses = setOf(400), responseEnvelope = true)
        ) { request ->
            PluginResult(body = repository.checkout(requireViewer(), request))
        }

        route("/orders") {
            get<OrderListData>(
                doc = OpenApiDoc(summary = "List orders for the current user", tags = listOf("commerce", "orders"), responseEnvelope = true)
            ) {
                PluginResult(body = repository.listOrdersFor(requireViewer()))
            }

            route("/{orderId}") {
                get<OrderDetailData>(
                    doc = OpenApiDoc(summary = "Get order detail for the owner or an admin", tags = listOf("commerce", "orders"), errorStatuses = setOf(403, 404), responseEnvelope = true)
                ) {
                    val orderId = pathParameters["orderId"] ?: throw PluginApiException(400, "Missing orderId")
                    PluginResult(body = repository.getOrderDetail(orderId, requireViewer()))
                }

                post<OrderDetailData>(
                    "cancel",
                    doc = OpenApiDoc(summary = "Cancel a placed order owned by the caller", tags = listOf("commerce", "orders"), errorStatuses = setOf(403, 404, 409), responseEnvelope = true)
                ) {
                    val orderId = pathParameters["orderId"] ?: throw PluginApiException(400, "Missing orderId")
                    PluginResult(body = repository.cancelOrder(orderId, requireViewer()))
                }
            }
        }

        route("/admin") {
            interceptors(OrderAdminInterceptor::class)

            get<OrderListData>(
                "/orders",
                doc = OpenApiDoc(summary = "List all orders for administrators", tags = listOf("commerce", "admin"), responseEnvelope = true)
            ) {
                PluginResult(body = repository.listAllOrders())
            }

            post<OrderStatusUpdateRequest, OrderDetailData>(
                "/orders/{orderId}/status",
                doc = OpenApiDoc(summary = "Move an order to the next admin status", tags = listOf("commerce", "admin"), errorStatuses = setOf(400, 404, 409), responseEnvelope = true)
            ) { request ->
                val orderId = pathParameters["orderId"] ?: throw PluginApiException(400, "Missing orderId")
                PluginResult(body = repository.updateOrderStatus(orderId, request, requireViewer()))
            }

            get<OrderAdminSummary>(
                "/summary",
                doc = OpenApiDoc(summary = "Get the admin commerce summary", tags = listOf("commerce", "admin"), responseEnvelope = true)
            ) {
                PluginResult(body = repository.adminSummary())
            }
        }

        get<OrderStatsData>(
            "/stats",
            doc = OpenApiDoc(summary = "Expose database stats and table metadata for the commerce sample", tags = listOf("commerce", "database"), responseEnvelope = true)
        ) {
            PluginResult(body = repository.stats())
        }
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        dbFactory.close()
    }
}

class OrderAuthInterceptor(
    private val authService: OrderAuthService
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult {
        val principal = authService.verifyToken(context.requestHeaders["Authorization"]?.firstOrNull())
            ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        context.principal = principal
        context.attributes["commerce.role"] = principal.role
        context.attributes["commerce.userId"] = principal.userId
        return next()
    }
}

class OrderAdminInterceptor(
    private val authService: OrderAuthService
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult {
        val principal = authService.verifyToken(context.requestHeaders["Authorization"]?.firstOrNull())
            ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        if (principal.role != "admin") {
            return KeelInterceptorResult.reject(403, "Forbidden")
        }
        context.principal = principal
        context.attributes["commerce.role"] = principal.role
        context.attributes["commerce.userId"] = principal.userId
        return next()
    }
}

class OrderAuthService {
    fun verifyToken(authHeader: String?): CommercePrincipal? {
        return CommerceDemoIdentity.principalFromAuthorization(authHeader)
    }
}

private fun KeelRequestContext.requireViewer(): CommercePrincipal {
    return principal as? CommercePrincipal ?: throw PluginApiException(401, "Unauthorized")
}
