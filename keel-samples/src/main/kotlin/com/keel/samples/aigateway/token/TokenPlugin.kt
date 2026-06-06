package com.keel.samples.aigateway.token

import com.keel.contract.ai.ApiKeyVerifier
import com.keel.contract.ai.JwtPrincipalVerifier
import com.keel.contract.ai.UsageRecorder
import com.keel.contract.ai.UserDirectory
import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiDoc
import com.keel.samples.aigateway.GatewayDataPaths
import org.koin.dsl.module

@KeelApiPlugin(
    pluginId = "token",
    title = "AI Gateway Token Plugin",
    description = "Virtual API keys, budgets, and usage records for the AI Gateway sample",
    version = "1.0.0"
)
class TokenPlugin : StandardKeelPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "token",
        version = "1.0.0",
        displayName = "AI Gateway Token Plugin"
    )

    private lateinit var dbFactory: DatabaseFactory
    private lateinit var database: KeelDatabase
    private lateinit var repository: TokenRepository
    private lateinit var jwtVerifier: JwtPrincipalVerifier

    override fun modules() = listOf(
        module {
            single { repository }
            single { TokenJwtAuthInterceptor(jwtVerifier) }
            single { TokenAdminInterceptor(jwtVerifier) }
        }
    )

    override suspend fun onInit(context: PluginInitContext) {
        val userDirectory = context.kernelKoin.get<UserDirectory>()
        jwtVerifier = context.kernelKoin.get<JwtPrincipalVerifier>()
        dbFactory = DatabaseFactory.h2File(
            filePath = GatewayDataPaths.databasePath("aigateway_token"),
            poolSize = 5
        )
        database = dbFactory.init()
        repository = TokenRepository(database, userDirectory)
        repository.initializeSchema()
        repository.seedDemoKeyIfNeeded()
        context.kernelKoin.loadModules(
            listOf(
                module {
                    single<ApiKeyVerifier> { repository }
                    single<UsageRecorder> { repository }
                }
            )
        )
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        route("/v1") {
            interceptors(TokenJwtAuthInterceptor::class)
            route("/keys") {
                get<ApiKeyListResponse>(
                    doc = OpenApiDoc(summary = "List current user's virtual API keys", tags = listOf("ai-gateway", "token"), errorStatuses = setOf(401))
                ) {
                    val principal = requireAiPrincipal()
                    PluginResult(body = repository.listKeys(principal.userId, includeAll = false))
                }
                post<CreateApiKeyRequest, ApiKeyCreatedResponse>(
                    doc = OpenApiDoc(summary = "Create a virtual API key", tags = listOf("ai-gateway", "token"), errorStatuses = setOf(400, 401))
                ) { request ->
                    val principal = requireAiPrincipal()
                    PluginResult(body = repository.createKey(principal.userId, request))
                }
                route("/{keyId}") {
                    get<ApiKeyView>(
                        doc = OpenApiDoc(summary = "Get virtual API key", tags = listOf("ai-gateway", "token"), errorStatuses = setOf(401, 403, 404))
                    ) {
                        PluginResult(body = repository.getKey(requireAiPrincipal().userId, requireKeyId(), includeAll = false))
                    }
                    put<UpdateApiKeyRequest, ApiKeyView>(
                        doc = OpenApiDoc(summary = "Update virtual API key", tags = listOf("ai-gateway", "token"), errorStatuses = setOf(400, 401, 403, 404))
                    ) { request ->
                        PluginResult(body = repository.updateKey(requireAiPrincipal().userId, requireKeyId(), request, includeAll = false))
                    }
                    delete<ApiKeyView>(
                        doc = OpenApiDoc(summary = "Delete virtual API key", tags = listOf("ai-gateway", "token"), errorStatuses = setOf(401, 403, 404))
                    ) {
                        PluginResult(body = repository.deleteKey(requireAiPrincipal().userId, requireKeyId(), includeAll = false))
                    }
                    post<TempBudgetRequest, ApiKeyView>(
                        "/temp-budget",
                        doc = OpenApiDoc(summary = "Add temporary budget", tags = listOf("ai-gateway", "token"), errorStatuses = setOf(400, 401, 403, 404))
                    ) { request ->
                        PluginResult(body = repository.addTempBudget(requireAiPrincipal().userId, requireKeyId(), request, includeAll = false))
                    }
                    get<UsageListResponse>(
                        "/usage",
                        doc = OpenApiDoc(summary = "List usage records for a key", tags = listOf("ai-gateway", "token"), errorStatuses = setOf(401, 403, 404))
                    ) {
                        PluginResult(body = repository.usageForKey(requireAiPrincipal().userId, requireKeyId(), includeAll = false))
                    }
                }
            }
        }

        route("/admin") {
            interceptors(TokenAdminInterceptor::class)
            get<ApiKeyListResponse>(
                "/keys",
                doc = OpenApiDoc(summary = "List all virtual API keys", tags = listOf("ai-gateway", "token", "admin"), errorStatuses = setOf(401, 403))
            ) {
                PluginResult(body = repository.listKeys(requireAiPrincipal().userId, includeAll = true))
            }
            get<com.keel.contract.ai.UsageSnapshot>(
                "/usage/global",
                doc = OpenApiDoc(summary = "Get global AI Gateway usage summary", tags = listOf("ai-gateway", "token", "admin"), errorStatuses = setOf(401, 403))
            ) {
                PluginResult(body = repository.snapshot())
            }
            get<UsageListResponse>(
                "/usage/records",
                doc = OpenApiDoc(summary = "List recent usage records with full token + cost breakdown", tags = listOf("ai-gateway", "token", "admin"), errorStatuses = setOf(401, 403))
            ) {
                val limit = queryParameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
                PluginResult(body = repository.recentRecords(limit))
            }
        }
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        dbFactory.close()
    }

    private fun com.keel.kernel.plugin.KeelRequestContext.requireKeyId(): String =
        pathParameters["keyId"] ?: throw PluginApiException(400, "Missing keyId")
}
