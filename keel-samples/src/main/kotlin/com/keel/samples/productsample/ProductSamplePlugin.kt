package com.keel.samples.productsample

import com.keel.kernel.plugin.KeelInterceptorResult
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiDoc
import com.keel.samples.commerce.CommerceCatalog
import com.keel.samples.commerce.CommerceCatalogUpsert
import com.keel.samples.commerce.CommerceDemoIdentity
import com.keel.samples.commerce.CommercePrincipal
import kotlinx.serialization.Serializable
import org.koin.dsl.module

@KeelApiPlugin(
    pluginId = "productsample",
    title = "Product Sample Plugin",
    description = "Owns the storefront catalog and merchandising summary for the commerce sample",
    version = "1.0.0"
)
class ProductSamplePlugin : StandardKeelPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "productsample",
        version = "1.0.0",
        displayName = "Product Sample Plugin"
    )

    override fun modules() = listOf(
        module {
            single { ProductAuthService() }
            single { ProductAdminInterceptor(get()) }
        }
    )

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        route("/catalog") {
            noInterceptors()

            get<ProductCatalogData>(
                doc = OpenApiDoc(summary = "List storefront products and collections", tags = listOf("commerce", "products"), responseEnvelope = true)
            ) {
                PluginResult(
                    body = ProductCatalogData(
                        items = CommerceCatalog.products().map { it.toDto() },
                        collections = CommerceCatalog.collections().map { ProductCollectionData(it.name, it.copy) },
                        featuredCollection = "Workspace"
                    )
                )
            }

            route("/{productId}") {
                get<ProductCardData>(
                    doc = OpenApiDoc(summary = "Get a single storefront product", tags = listOf("commerce", "products"), errorStatuses = setOf(404), responseEnvelope = true)
                ) {
                    val productId = pathParameters["productId"] ?: throw PluginApiException(400, "Missing productId")
                    val product = CommerceCatalog.product(productId) ?: throw PluginApiException(404, "Product not found")
                    PluginResult(body = product.toDto())
                }
            }
        }

        route("/admin") {
            interceptors(ProductAdminInterceptor::class)

            get<ProductAdminCatalogData>(
                "/catalog",
                doc = OpenApiDoc(summary = "List products for product administration", tags = listOf("commerce", "products", "admin"), responseEnvelope = true)
            ) {
                val snapshot = CommerceCatalog.snapshot()
                PluginResult(
                    body = ProductAdminCatalogData(
                        items = snapshot.products.map { it.toAdminDto() },
                        collections = snapshot.collections.map { ProductCollectionData(it.name, it.copy) }
                    )
                )
            }

            post<ProductUpsertRequest, ProductCardData>(
                "/products",
                doc = OpenApiDoc(summary = "Create a new product for the commerce sample", tags = listOf("commerce", "products", "admin"), errorStatuses = setOf(400), responseEnvelope = true)
            ) { request ->
                PluginResult(body = upsertProduct(request).toDto())
            }

            put<ProductUpsertRequest, ProductCardData>(
                "/products/{productId}",
                doc = OpenApiDoc(summary = "Update an existing product for the commerce sample", tags = listOf("commerce", "products", "admin"), errorStatuses = setOf(400, 404), responseEnvelope = true)
            ) { request ->
                val productId = pathParameters["productId"] ?: throw PluginApiException(400, "Missing productId")
                if (CommerceCatalog.product(productId) == null) {
                    throw PluginApiException(404, "Product not found")
                }
                PluginResult(body = upsertProduct(request.copy(productId = productId)).toDto())
            }
            
            delete<ProductMutationResult>(
                "/products/{productId}",
                doc = OpenApiDoc(summary = "Archive a product from the commerce sample", tags = listOf("commerce", "products", "admin"), errorStatuses = setOf(404), responseEnvelope = true)
            ) {
                val productId = pathParameters["productId"] ?: throw PluginApiException(400, "Missing productId")
                val removed = CommerceCatalog.remove(productId) ?: throw PluginApiException(404, "Product not found")
                PluginResult(
                    body = ProductMutationResult(
                        productId = removed.productId,
                        message = "Product archived from the storefront"
                    )
                )
            }

            get<ProductMerchandisingData>(
                "/merchandising",
                doc = OpenApiDoc(summary = "Get merchandising and collection highlights", tags = listOf("commerce", "products", "admin"), responseEnvelope = true)
            ) {
                val snapshot = CommerceCatalog.snapshot()
                PluginResult(
                    body = ProductMerchandisingData(
                        totalProducts = snapshot.products.size,
                        totalCollections = snapshot.collections.size,
                        featuredCollections = snapshot.collections.map { it.name },
                        featuredProducts = snapshot.products.take(3).map { it.title }
                    )
                )
            }
        }
    }

    private fun upsertProduct(request: ProductUpsertRequest) = CommerceCatalog.upsert(
        CommerceCatalogUpsert(
            productId = request.productId,
            title = request.title.trim().also {
                if (it.isBlank()) throw PluginApiException(400, "Title is required")
            },
            description = request.description.trim().also {
                if (it.isBlank()) throw PluginApiException(400, "Description is required")
            },
            priceCents = request.priceCents.also {
                if (it <= 0) throw PluginApiException(400, "priceCents must be greater than 0")
            },
            badge = request.badge.trim(),
            collection = request.collection.trim()
        )
    )
}

class ProductAuthService {
    suspend fun verifyToken(authHeader: String?): CommercePrincipal? {
        return CommerceDemoIdentity.principalFromAuthorization(authHeader)
    }
}

class ProductAdminInterceptor(
    private val authService: ProductAuthService
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
        return next()
    }
}

@Serializable
data class ProductCatalogData(
    val items: List<ProductCardData>,
    val collections: List<ProductCollectionData>,
    val featuredCollection: String
)

@Serializable
data class ProductCardData(
    val productId: String,
    val title: String,
    val description: String,
    val priceCents: Int,
    val badge: String,
    val collection: String
)

@Serializable
data class ProductCollectionData(
    val name: String,
    val copy: String
)

@Serializable
data class ProductMerchandisingData(
    val totalProducts: Int,
    val totalCollections: Int,
    val featuredCollections: List<String>,
    val featuredProducts: List<String>
)

@Serializable
data class ProductAdminCatalogData(
    val items: List<ProductAdminItemData>,
    val collections: List<ProductCollectionData>
)

@Serializable
data class ProductAdminItemData(
    val productId: String,
    val title: String,
    val description: String,
    val priceCents: Int,
    val badge: String,
    val collection: String
)

@Serializable
data class ProductUpsertRequest(
    val productId: String? = null,
    val title: String,
    val description: String,
    val priceCents: Int,
    val badge: String,
    val collection: String
)

@Serializable
data class ProductMutationResult(
    val productId: String,
    val message: String
)

private fun com.keel.samples.commerce.CommerceCatalogProduct.toDto(): ProductCardData {
    return ProductCardData(
        productId = productId,
        title = title,
        description = description,
        priceCents = priceCents,
        badge = badge,
        collection = collection
    )
}

private fun com.keel.samples.commerce.CommerceCatalogProduct.toAdminDto(): ProductAdminItemData {
    return ProductAdminItemData(
        productId = productId,
        title = title,
        description = description,
        priceCents = priceCents,
        badge = badge,
        collection = collection
    )
}
