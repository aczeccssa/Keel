package com.keel.test.kernel

import com.keel.kernel.plugin.KeelInterceptorResult
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.KtorScopedPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginKtorConfig
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.samples.authsample.AuthSamplePlugin
import com.keel.samples.ordersample.OrderSamplePlugin
import com.keel.samples.productsample.ProductSamplePlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.scope.get
import org.koin.dsl.module

class KeelInterceptorChainIntegrationTest {
    @AfterTest
    fun teardown() {
        runCatching { stopKoin() }
    }

    @Test
    fun dslInterceptorsSupportDefaultOverrideAndNoInterceptorsInProcess() = testApplication {
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(DslInterceptorPlugin("dsl-in-process", PluginRuntimeMode.IN_PROCESS))

        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            manager.installConfiguredPluginApplicationKtorPlugins(this)
            routing { manager.mountRoutes(this) }
            kotlinx.coroutines.runBlocking { manager.startPlugin("dsl-in-process") }
        }

        val publicResponse = client.get("/api/plugins/dsl-in-process/public")
        assertEquals(HttpStatusCode.OK, publicResponse.status)
        assertTrue(publicResponse.bodyAsText().contains("\"chain\":\"none\""))

        val secureUnauthorized = client.get("/api/plugins/dsl-in-process/secure")
        assertEquals(HttpStatusCode.Unauthorized, secureUnauthorized.status)

        val secureAuthorized = client.get("/api/plugins/dsl-in-process/secure") {
            header("X-User", "alice")
            header("X-Role", "user")
        }
        assertEquals(HttpStatusCode.OK, secureAuthorized.status)
        assertEquals("enabled", secureAuthorized.headers["X-Service-Scope"])
        assertTrue(secureAuthorized.bodyAsText().contains("\"principal\":\"alice\""))
        assertTrue(secureAuthorized.bodyAsText().contains("\"tenant\":\"tenant-alice\""))
        assertTrue(secureAuthorized.bodyAsText().contains("\"chain\":\"default\""))

        val adminForbidden = client.get("/api/plugins/dsl-in-process/admin") {
            header("X-User", "alice")
            header("X-Role", "user")
        }
        assertEquals(HttpStatusCode.Forbidden, adminForbidden.status)

        val adminAllowed = client.get("/api/plugins/dsl-in-process/admin") {
            header("X-User", "root")
            header("X-Role", "admin")
        }
        assertEquals(HttpStatusCode.OK, adminAllowed.status)
        assertTrue(adminAllowed.bodyAsText().contains("\"chain\":\"admin\""))
    }

    @Test
    fun commerceShowcaseCoversCatalogCheckoutAdminFlowAndDatabaseStats() = testApplication {
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(AuthSamplePlugin())
        manager.registerPlugin(ProductSamplePlugin())
        manager.registerPlugin(OrderSamplePlugin())

        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing { manager.mountRoutes(this) }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("authsample")
                manager.startPlugin("productsample")
                manager.startPlugin("ordersample")
            }
        }

        val publicResponse = client.get("/api/plugins/authsample/public")
        assertEquals(HttpStatusCode.OK, publicResponse.status)

        val publicCatalog = client.get("/api/plugins/productsample/catalog")
        assertEquals(HttpStatusCode.OK, publicCatalog.status)
        assertTrue(publicCatalog.bodyAsText().contains("\"productId\":\"sku-desk-lamp\""))

        val cartUnauthorized = client.get("/api/plugins/ordersample/cart")
        assertEquals(HttpStatusCode.Unauthorized, cartUnauthorized.status)

        val showcasePage = client.get("/api/plugins/authsample/showcase/index.html")
        assertEquals(HttpStatusCode.OK, showcasePage.status)
        assertTrue(showcasePage.bodyAsText().contains("keel-exposed-starter"))
        assertTrue(showcasePage.bodyAsText().contains("Storefront"))

        val loginResponse = client.post("/api/plugins/authsample/session/login") {
            header("Content-Type", "application/json")
            setBody("""{"username":"user","password":"user123"}""")
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        assertTrue(loginResponse.bodyAsText().contains("\"authorizationHeader\":\"Bearer user-token\""))

        val protectedUnauthorized = client.get("/api/plugins/authsample/profile")
        assertEquals(HttpStatusCode.Unauthorized, protectedUnauthorized.status)

        val protectedAuthorized = client.get("/api/plugins/authsample/profile") {
            header("authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.OK, protectedAuthorized.status)
        assertTrue(protectedAuthorized.bodyAsText().contains("\"userId\":\"user-1\""))

        val addToCart = client.post("/api/plugins/ordersample/cart/items") {
            header("authorization", "Bearer user-token")
            header("Content-Type", "application/json")
            setBody("""{"productId":"sku-desk-lamp","quantity":1}""")
        }
        assertEquals(HttpStatusCode.OK, addToCart.status)
        assertTrue(addToCart.bodyAsText().contains("\"totalItems\":1"))

        val checkout = client.post("/api/plugins/ordersample/checkout") {
            header("Authorization", "Bearer user-token")
            header("Content-Type", "application/json")
            setBody("""{"note":"Ship this with the next batch."}""")
        }
        assertEquals(HttpStatusCode.OK, checkout.status)
        assertTrue(checkout.bodyAsText().contains("\"orderId\":\"ord-1003\""))

        val cartAfterCheckout = client.get("/api/plugins/ordersample/cart") {
            header("Authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.OK, cartAfterCheckout.status)
        assertTrue(cartAfterCheckout.bodyAsText().contains("\"totalItems\":0"))

        val userOrders = client.get("/api/plugins/ordersample/orders") {
            header("Authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.OK, userOrders.status)
        assertTrue(userOrders.bodyAsText().contains("\"orderId\":\"ord-1003\""))

        val orderDetail = client.get("/api/plugins/ordersample/orders/ord-1003") {
            header("Authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.OK, orderDetail.status)
        assertTrue(orderDetail.bodyAsText().contains("\"createdBy\":\"user-1\""))

        val forbiddenOtherOrder = client.get("/api/plugins/ordersample/orders/ord-2001") {
            header("Authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenOtherOrder.status)

        val adminSummaryForbidden = client.get("/api/plugins/ordersample/admin/summary") {
            header("Authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.Forbidden, adminSummaryForbidden.status)

        val merchandisingForbidden = client.get("/api/plugins/productsample/admin/merchandising") {
            header("Authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.Forbidden, merchandisingForbidden.status)

        val adminCatalogForbidden = client.get("/api/plugins/productsample/admin/catalog") {
            header("Authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.Forbidden, adminCatalogForbidden.status)

        val cancelledOrder = client.post("/api/plugins/ordersample/orders/ord-1003/cancel") {
            header("Authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.OK, cancelledOrder.status)
        assertTrue(cancelledOrder.bodyAsText().contains("\"status\":\"CANCELLED\""))

        val stats = client.get("/api/plugins/ordersample/stats") {
            header("Authorization", "Bearer user-token")
        }
        assertEquals(HttpStatusCode.OK, stats.status)
        assertTrue(stats.bodyAsText().contains("\"ordersample_orders\""))
        assertTrue(stats.bodyAsText().contains("\"databaseType\":\"H2 In-Memory\""))

        val adminLogin = client.post("/api/plugins/authsample/session/login") {
            header("Content-Type", "application/json")
            setBody("""{"username":"admin","password":"admin123"}""")
        }
        assertEquals(HttpStatusCode.OK, adminLogin.status)
        assertTrue(adminLogin.bodyAsText().contains("\"authorizationHeader\":\"Bearer admin-token\""))

        val adminOrders = client.get("/api/plugins/ordersample/admin/orders") {
            header("Authorization", "Bearer admin-token")
        }
        assertEquals(HttpStatusCode.OK, adminOrders.status)
        assertTrue(adminOrders.bodyAsText().contains("\"orderId\":\"ord-1001\""))

        val merchandising = client.get("/api/plugins/productsample/admin/merchandising") {
            header("authorization", "Bearer admin-token")
        }
        assertEquals(HttpStatusCode.OK, merchandising.status)
        assertTrue(merchandising.bodyAsText().contains("\"totalProducts\":4"))

        val createProduct = client.post("/api/plugins/productsample/admin/products") {
            header("authorization", "Bearer admin-token")
            header("Content-Type", "application/json")
            setBody(
                """{"title":"Focus Lamp Pro","description":"Premium task lighting for product teams.","priceCents":14900,"badge":"New Arrival","collection":"Workspace"}"""
            )
        }
        assertEquals(HttpStatusCode.OK, createProduct.status)
        assertTrue(createProduct.bodyAsText().contains("\"title\":\"Focus Lamp Pro\""))
        assertTrue(createProduct.bodyAsText().contains("\"productId\":\"sku-focus-lamp-pro\""))

        val adminCatalog = client.get("/api/plugins/productsample/admin/catalog") {
            header("authorization", "Bearer admin-token")
        }
        assertEquals(HttpStatusCode.OK, adminCatalog.status)
        assertTrue(adminCatalog.bodyAsText().contains("\"productId\":\"sku-focus-lamp-pro\""))

        val publicCatalogAfterCreate = client.get("/api/plugins/productsample/catalog")
        assertEquals(HttpStatusCode.OK, publicCatalogAfterCreate.status)
        assertTrue(publicCatalogAfterCreate.bodyAsText().contains("\"productId\":\"sku-focus-lamp-pro\""))

        val buyCreatedProduct = client.post("/api/plugins/ordersample/cart/items") {
            header("Authorization", "Bearer user-token")
            header("Content-Type", "application/json")
            setBody("""{"productId":"sku-focus-lamp-pro","quantity":1}""")
        }
        assertEquals(HttpStatusCode.OK, buyCreatedProduct.status)
        assertTrue(buyCreatedProduct.bodyAsText().contains("\"sku-focus-lamp-pro\""))

        val checkoutCreatedProduct = client.post("/api/plugins/ordersample/checkout") {
            header("Authorization", "Bearer user-token")
            header("Content-Type", "application/json")
            setBody("""{"note":"Buying the admin-created product."}""")
        }
        assertEquals(HttpStatusCode.OK, checkoutCreatedProduct.status)
        assertTrue(checkoutCreatedProduct.bodyAsText().contains("\"orderId\":\"ord-1004\""))

        val movePlacedToProcessing = client.post("/api/plugins/ordersample/admin/orders/ord-1001/status") {
            header("Authorization", "Bearer admin-token")
            header("Content-Type", "application/json")
            setBody("""{"status":"PROCESSING"}""")
        }
        assertEquals(HttpStatusCode.OK, movePlacedToProcessing.status)
        assertTrue(movePlacedToProcessing.bodyAsText().contains("\"status\":\"PROCESSING\""))

        val moveProcessingToFulfilled = client.post("/api/plugins/ordersample/admin/orders/ord-2001/status") {
            header("Authorization", "Bearer admin-token")
            header("Content-Type", "application/json")
            setBody("""{"status":"FULFILLED"}""")
        }
        assertEquals(HttpStatusCode.OK, moveProcessingToFulfilled.status)
        assertTrue(moveProcessingToFulfilled.bodyAsText().contains("\"status\":\"FULFILLED\""))

        val adminAllowed = client.get("/api/plugins/ordersample/admin/summary") {
            header("Authorization", "Bearer admin-token")
        }
        assertEquals(HttpStatusCode.OK, adminAllowed.status)
        assertTrue(adminAllowed.bodyAsText().contains("\"totalOrders\":4"))
        assertTrue(adminAllowed.bodyAsText().contains("\"placedOrders\":1"))
        assertTrue(adminAllowed.bodyAsText().contains("\"processingOrders\":1"))
        assertTrue(adminAllowed.bodyAsText().contains("\"fulfilledOrders\":1"))
        assertTrue(adminAllowed.bodyAsText().contains("\"cancelledOrders\":1"))
    }

    class DslInterceptorPlugin(
        pluginId: String,
        runtimeMode: PluginRuntimeMode
    ) : StandardKeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId,
            defaultRuntimeMode = runtimeMode
        )

        private lateinit var chainService: ChainService

        override fun modules() = listOf(
            module {
                single { ChainService() }
                single { DefaultDslInterceptor(get()) }
                single { AdminDslInterceptor(get()) }
            }
        )

        override suspend fun onStart(context: PluginRuntimeContext) {
            chainService = context.privateScope.get()
        }

        override fun ktorPlugins(): PluginKtorConfig = PluginKtorConfig().apply {
            service {
                install(serviceHeaderPlugin)
            }
        }

        override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
            interceptors(DefaultDslInterceptor::class)

            get<ChainSnapshot>("/secure") {
                PluginResult(body = chainService.snapshot(this))
            }

            route("/public") {
                noInterceptors()
                get<ChainSnapshot> {
                    PluginResult(body = chainService.snapshot(this))
                }
            }

            route("/admin") {
                interceptors(AdminDslInterceptor::class)
                get<ChainSnapshot> {
                    PluginResult(body = chainService.snapshot(this))
                }
            }
        }
    }

    class ChainService {
        fun snapshot(context: KeelRequestContext): ChainSnapshot {
            return ChainSnapshot(
                principal = context.principal?.toString(),
                tenant = context.tenant?.toString(),
                chain = context.attributes["chain"]?.toString() ?: "none"
            )
        }
    }

    class DefaultDslInterceptor(
        private val chainService: ChainService
    ) : KeelRequestInterceptor {
        override suspend fun intercept(
            context: KeelRequestContext,
            next: suspend () -> KeelInterceptorResult
        ): KeelInterceptorResult {
            val user = context.requestHeaders["X-User"]?.firstOrNull()
                ?: return KeelInterceptorResult.reject(401, "Unauthorized")
            context.principal = user
            context.tenant = "tenant-$user"
            context.attributes["chain"] = "default"
            context.attributes["service"] = chainService.hashCode().toString()
            return next()
        }
    }

    class AdminDslInterceptor(
        private val chainService: ChainService
    ) : KeelRequestInterceptor {
        override suspend fun intercept(
            context: KeelRequestContext,
            next: suspend () -> KeelInterceptorResult
        ): KeelInterceptorResult {
            if (context.requestHeaders["X-Role"]?.firstOrNull() != "admin") {
                return KeelInterceptorResult.reject(403, "Forbidden")
            }
            val user = context.requestHeaders["X-User"]?.firstOrNull() ?: "admin"
            context.principal = user
            context.tenant = "tenant-$user"
            context.attributes["chain"] = "admin"
            context.attributes["service"] = chainService.hashCode().toString()
            return next()
        }
    }

    @Serializable
    data class ChainSnapshot(
        val principal: String? = null,
        val tenant: String? = null,
        val chain: String
    )

    companion object {
        private val serviceHeaderPlugin = createRouteScopedPlugin("dsl-service-scope") {
            onCall { call ->
                call.response.headers.append("X-Service-Scope", "enabled")
            }
        }
    }
}
