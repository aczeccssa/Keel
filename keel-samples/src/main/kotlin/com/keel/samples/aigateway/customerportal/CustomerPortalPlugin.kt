package com.keel.samples.aigateway.customerportal

import com.keel.contract.ai.JwtPrincipalVerifier
import com.keel.contract.ai.ModelPricingDirectory
import com.keel.contract.ai.ModelPricingSummary
import com.keel.contract.customer.CreditLedger
import com.keel.contract.customer.CustomerApiKeyVerifier
import com.keel.contract.customer.CustomerDirectory
import com.keel.contract.customer.VerifiedCustomerKey
import com.keel.db.database.DatabaseFactory
import com.keel.kernel.plugin.*
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiDoc
import com.keel.samples.aigateway.GatewayDataPaths
import com.keel.samples.aigateway.customerportal.auth.*
import org.koin.dsl.module

@KeelApiPlugin(
    pluginId = "customer-portal",
    title = "Customer Portal Plugin",
    description = "End-customer self-service: signup, API keys, Credits, redemption, usage",
    version = "1.0.0",
)
class CustomerPortalPlugin : StandardKeelPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "customer-portal",
        version = "1.0.0",
        displayName = "Customer Portal Plugin",
        defaultRuntimeMode = PluginRuntimeMode.IN_PROCESS,
        supportedRuntimeModes = setOf(PluginRuntimeMode.IN_PROCESS),
    )

    private lateinit var repository: CustomerPortalRepository
    private lateinit var dbFactory: DatabaseFactory
    private lateinit var jwtService: CustomerJwtService
    private lateinit var jwtAuthInterceptor: CustomerJwtAuthInterceptor
    private lateinit var adminInterceptor: CustomerAdminInterceptor
    private lateinit var contextKernelKoin: org.koin.core.Koin

    override fun modules() = listOf(
        module {
            single { repository }
            single { jwtAuthInterceptor }
            single { adminInterceptor }
        }
    )

    override suspend fun onInit(context: PluginInitContext) {
        contextKernelKoin = context.kernelKoin
        jwtService = CustomerJwtService()
        dbFactory = DatabaseFactory.h2File(
            filePath = GatewayDataPaths.databasePath("customer_portal"),
            poolSize = 5,
        )
        val db = dbFactory.init()
        repository = CustomerPortalRepository(db)
        repository.initializeSchema()
        jwtAuthInterceptor = CustomerJwtAuthInterceptor(jwtService)
        val adminVerifier = context.kernelKoin.get<JwtPrincipalVerifier>()
        adminInterceptor = CustomerAdminInterceptor(adminVerifier)

        context.kernelKoin.loadModules(
            listOf(
                module {
                    single<CustomerApiKeyVerifier> { repository }
                    single<CreditLedger> { repository }
                    single<CustomerDirectory> { repository }
                }
            )
        )
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        dbFactory.close()
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        // ---- auth (no interceptors) ----
        route("/v1/customer/auth") {
            noInterceptors()

            post<CustomerRegisterRequest, CustomerAuthResponse>(
                "/register",
                doc = OpenApiDoc(summary = "Register a customer account", tags = listOf("customer-portal", "auth"), errorStatuses = setOf(400, 409)),
            ) { request ->
                PluginResult(body = repository.register(request))
            }

            post<CustomerLoginRequest, CustomerAuthResponse>(
                "/login",
                doc = OpenApiDoc(summary = "Customer login", tags = listOf("customer-portal", "auth"), errorStatuses = setOf(401, 403)),
            ) { request ->
                PluginResult(body = repository.login(request))
            }

            post<CustomerRefreshRequest, CustomerAuthResponse>(
                "/refresh",
                doc = OpenApiDoc(summary = "Refresh customer access token", tags = listOf("customer-portal", "auth"), errorStatuses = setOf(401)),
            ) { request ->
                PluginResult(body = repository.refresh(request))
            }

            post<CustomerOAuthStubRequest, CustomerAuthResponse>(
                "/oauth/stub",
                doc = OpenApiDoc(summary = "OAuth stub — creates or logs in via mock provider", tags = listOf("customer-portal", "auth"), errorStatuses = setOf(400)),
            ) { request ->
                PluginResult(body = repository.oauthStub(request))
            }

            route("/me") {
                interceptors(CustomerJwtAuthInterceptor::class)
                get<CustomerProfile>(
                    doc = OpenApiDoc(summary = "Get current customer profile", tags = listOf("customer-portal", "auth"), errorStatuses = setOf(401)),
                ) {
                    PluginResult(body = repository.me(requireCustomerPrincipal(this)))
                }
            }
        }

        // ---- keys (JWT protected) ----
        route("/v1/customer/keys") {
            interceptors(CustomerJwtAuthInterceptor::class)

            get<CustomerKeyListResponse>(
                doc = OpenApiDoc(summary = "List current customer's API keys", tags = listOf("customer-portal", "keys"), errorStatuses = setOf(401)),
            ) {
                PluginResult(body = repository.listKeys(requireCustomerPrincipal(this).customerId))
            }

            post<CreateCustomerKeyRequest, CustomerKeyCreatedResponse>(
                doc = OpenApiDoc(summary = "Create an API key", tags = listOf("customer-portal", "keys"), errorStatuses = setOf(400, 401)),
            ) { request ->
                PluginResult(body = repository.createKey(requireCustomerPrincipal(this).customerId, request))
            }

            route("/{keyId}") {
                get<CustomerKeyView>(
                    doc = OpenApiDoc(summary = "Get API key details", tags = listOf("customer-portal", "keys"), errorStatuses = setOf(401, 403, 404)),
                ) {
                    PluginResult(body = repository.getKey(requireCustomerPrincipal(this).customerId, requireKeyId()))
                }
                put<UpdateCustomerKeyRequest, CustomerKeyView>(
                    doc = OpenApiDoc(summary = "Update API key", tags = listOf("customer-portal", "keys"), errorStatuses = setOf(400, 401, 403, 404)),
                ) { request ->
                    PluginResult(body = repository.updateKey(requireCustomerPrincipal(this).customerId, requireKeyId(), request))
                }
                delete<CustomerKeyView>(
                    doc = OpenApiDoc(summary = "Delete API key", tags = listOf("customer-portal", "keys"), errorStatuses = setOf(401, 403, 404)),
                ) {
                    PluginResult(body = repository.deleteKey(requireCustomerPrincipal(this).customerId, requireKeyId()))
                }
            }
        }

        // ---- credits (JWT protected) ----
        route("/v1/customer/credits") {
            interceptors(CustomerJwtAuthInterceptor::class)

            get<CreditBalanceResponse>(
                doc = OpenApiDoc(summary = "Get credit balance", tags = listOf("customer-portal", "credits"), errorStatuses = setOf(401)),
            ) {
                PluginResult(body = repository.customerBalance(requireCustomerPrincipal(this).customerId))
            }

            get<CreditLedgerResponse>(
                "/ledger",
                doc = OpenApiDoc(summary = "List credit ledger entries", tags = listOf("customer-portal", "credits"), errorStatuses = setOf(401)),
            ) {
                val cursor = queryParameters["cursor"]?.firstOrNull()
                val limit = queryParameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
                PluginResult(body = repository.customerLedger(requireCustomerPrincipal(this).customerId, cursor, limit))
            }

            post<RedeemCodeRequest, RedeemCodeResponse>(
                "/redeem",
                doc = OpenApiDoc(summary = "Redeem a credit code", tags = listOf("customer-portal", "credits"), errorStatuses = setOf(400, 404, 409, 410)),
            ) { request ->
                PluginResult(body = repository.redeemCode(requireCustomerPrincipal(this).customerId, request))
            }
        }

        route("/v1/customer/key-credits") {
            noInterceptors()

            get<CreditBalanceResponse>(
                "/balance",
                doc = OpenApiDoc(summary = "Get credit balance using customer API key", tags = listOf("customer-portal", "credits"), errorStatuses = setOf(401)),
            ) {
                val verified = requireVerifiedCustomerKey(this)
                PluginResult(body = repository.customerBalance(verified.customerId))
            }

            get<CreditLedgerResponse>(
                "/ledger",
                doc = OpenApiDoc(summary = "List credit ledger entries using customer API key", tags = listOf("customer-portal", "credits"), errorStatuses = setOf(401)),
            ) {
                val verified = requireVerifiedCustomerKey(this)
                val cursor = queryParameters["cursor"]?.firstOrNull()
                val limit = queryParameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
                PluginResult(body = repository.customerLedger(verified.customerId, cursor, limit))
            }
        }

        // ---- usage (JWT protected) ----
        route("/v1/customer/usage") {
            interceptors(CustomerJwtAuthInterceptor::class)

            get<CustomerUsageListResponse>(
                doc = OpenApiDoc(summary = "List customer usage records", tags = listOf("customer-portal", "usage"), errorStatuses = setOf(401)),
            ) {
                val cursor = queryParameters["cursor"]?.firstOrNull()
                val limit = queryParameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
                PluginResult(body = repository.customerUsage(requireCustomerPrincipal(this).customerId, cursor, limit))
            }
        }

        route("/v1/customer/key-usage") {
            noInterceptors()

            get<CustomerUsageListResponse>(
                doc = OpenApiDoc(summary = "List usage records using customer API key", tags = listOf("customer-portal", "usage"), errorStatuses = setOf(401)),
            ) {
                val verified = requireVerifiedCustomerKey(this)
                val cursor = queryParameters["cursor"]?.firstOrNull()
                val limit = queryParameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
                PluginResult(body = repository.customerUsage(verified.customerId, cursor, limit))
            }
        }

        // ---- model pricing (JWT protected, no auth interceptor needed for read) ----
        route("/v1/customer/pricing") {
            interceptors(CustomerJwtAuthInterceptor::class)
            get<ModelPricingListResponse>(
                doc = OpenApiDoc(summary = "List available model pricing", tags = listOf("customer-portal", "pricing"), errorStatuses = setOf(401)),
            ) {
                val directory = runCatching { contextKernelKoin.get<ModelPricingDirectory>() }.getOrNull()
                    ?: return@get PluginResult(body = ModelPricingListResponse(summaries = emptyList()))
                PluginResult(body = ModelPricingListResponse(directory.listActive()))
            }
        }

        // ---- admin (B-end JWT protected) ----
        route("/admin") {
            interceptors(CustomerAdminInterceptor::class)

            route("/codes") {
                get<RedemptionCodeListResponse>(
                    doc = OpenApiDoc(summary = "List redemption codes", tags = listOf("customer-portal", "admin"), errorStatuses = setOf(401, 403)),
                ) {
                    PluginResult(body = repository.listRedemptionCodes())
                }
                post<CreateRedemptionCodeRequest, RedemptionCodeView>(
                    doc = OpenApiDoc(summary = "Create redemption code", tags = listOf("customer-portal", "admin"), errorStatuses = setOf(400, 401, 403, 409)),
                ) { request ->
                    PluginResult(body = repository.createRedemptionCode(request, requireAdminUserId(this)))
                }
                delete<DeleteResponse>(
                    "/{code}",
                    doc = OpenApiDoc(summary = "Revoke redemption code", tags = listOf("customer-portal", "admin"), errorStatuses = setOf(401, 403, 404)),
                ) {
                    val code = pathParameters["code"] ?: throw PluginApiException(400, "Missing code")
                    repository.revokeRedemptionCode(code)
                    PluginResult(body = DeleteResponse("Code revoked"))
                }
            }

            get<CustomerListResponse>(
                "/customers",
                doc = OpenApiDoc(summary = "List all customers", tags = listOf("customer-portal", "admin"), errorStatuses = setOf(401, 403)),
            ) {
                val summaries = repository.listAll()
                val views = summaries.map { s ->
                    CustomerListView(
                        customerId = s.customerId,
                        email = s.email,
                        displayName = s.displayName,
                        status = s.status,
                        balanceCredits = s.balanceCredits,
                        totalKeys = s.totalKeys,
                        createdAt = s.createdAt,
                    )
                }
                PluginResult(body = CustomerListResponse(views, views.size))
            }

            get<CustomerAdminDetailView>(
                "/customers/{customerId}",
                doc = OpenApiDoc(summary = "Get customer detail", tags = listOf("customer-portal", "admin"), errorStatuses = setOf(401, 403, 404)),
            ) {
                val customerId = pathParameters["customerId"] ?: throw PluginApiException(400, "Missing customerId")
                PluginResult(body = repository.adminCustomerDetail(customerId))
            }

            put<UpdateCustomerRequest, CustomerAdminDetailView>(
                "/customers/{customerId}",
                doc = OpenApiDoc(summary = "Update customer profile or status", tags = listOf("customer-portal", "admin"), errorStatuses = setOf(400, 401, 403, 404)),
            ) { request ->
                val customerId = pathParameters["customerId"] ?: throw PluginApiException(400, "Missing customerId")
                PluginResult(body = repository.updateCustomer(customerId, request))
            }

            delete<CustomerAdminDetailView>(
                "/customers/{customerId}",
                doc = OpenApiDoc(summary = "Soft-delete customer", tags = listOf("customer-portal", "admin"), errorStatuses = setOf(401, 403, 404)),
            ) {
                val customerId = pathParameters["customerId"] ?: throw PluginApiException(400, "Missing customerId")
                PluginResult(body = repository.softDeleteCustomer(customerId))
            }

            post<AdjustCustomerCreditsRequest, CustomerAdminDetailView>(
                "/customers/{customerId}/credits",
                doc = OpenApiDoc(summary = "Adjust customer credits", tags = listOf("customer-portal", "admin"), errorStatuses = setOf(400, 401, 403, 404)),
            ) { request ->
                val customerId = pathParameters["customerId"] ?: throw PluginApiException(400, "Missing customerId")
                PluginResult(body = repository.adjustCustomerCredits(customerId, request))
            }

            delete<CustomerKeyView>(
                "/customers/{customerId}/keys/{keyId}",
                doc = OpenApiDoc(summary = "Revoke a customer key as admin", tags = listOf("customer-portal", "admin"), errorStatuses = setOf(401, 403, 404)),
            ) {
                val customerId = pathParameters["customerId"] ?: throw PluginApiException(400, "Missing customerId")
                val keyId = pathParameters["keyId"] ?: throw PluginApiException(400, "Missing keyId")
                PluginResult(body = repository.adminRevokeCustomerKey(customerId, keyId))
            }
        }

        // ---- C-end UI ----
        staticResources(
            path = "/ui",
            basePackage = "ui/customer-portal-ui",
            doc = OpenApiDoc(summary = "Customer self-service portal UI", tags = listOf("customer-portal")),
            index = "index.html",
        )
    }

    private fun KeelRequestContext.requireKeyId(): String =
        pathParameters["keyId"] ?: throw PluginApiException(400, "Missing keyId")

    private suspend fun requireVerifiedCustomerKey(context: KeelRequestContext): VerifiedCustomerKey {
        val raw = context.requestHeaders["Authorization"]?.firstOrNull()
            ?.removePrefix("Bearer ")
            ?.takeIf { it.startsWith("sk-keel-") }
            ?: context.requestHeaders["x-api-key"]?.firstOrNull()?.takeIf { it.startsWith("sk-keel-") }
            ?: throw PluginApiException(401, "Missing customer API key")
        return repository.verifyCustomerKey(raw) ?: throw PluginApiException(401, "Invalid customer API key")
    }
}
