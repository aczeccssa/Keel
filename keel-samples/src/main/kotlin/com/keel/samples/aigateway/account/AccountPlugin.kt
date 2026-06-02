package com.keel.samples.aigateway.account

import com.keel.contract.ai.JwtPrincipalVerifier
import com.keel.contract.ai.UserDirectory
import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiDoc
import org.koin.dsl.module

@KeelApiPlugin(
    pluginId = "account",
    title = "AI Gateway Account Plugin",
    description = "Accounts, JWT auth, user groups, and roles for the AI Gateway sample",
    version = "1.0.0"
)
class AccountPlugin : StandardKeelPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "account",
        version = "1.0.0",
        displayName = "AI Gateway Account Plugin"
    )

    private lateinit var dbFactory: DatabaseFactory
    private lateinit var database: KeelDatabase
    private lateinit var repository: AccountRepository

    override fun modules() = listOf(
        module {
            single { repository }
            single { JwtAuthInterceptor(get()) }
            single { AdminOnlyInterceptor(get()) }
        }
    )

    override suspend fun onInit(context: PluginInitContext) {
        dbFactory = DatabaseFactory.h2Memory(name = "aigateway_account", poolSize = 5)
        database = dbFactory.init()
        repository = AccountRepository(database, PasswordHasher(), JwtTokenService())
        repository.initializeSchema()
        repository.seedDefaults()
        context.kernelKoin.loadModules(
            listOf(
                module {
                    single<UserDirectory> { repository }
                    single<JwtPrincipalVerifier> { repository }
                }
            )
        )
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        route("/v1/auth") {
            noInterceptors()
            post<RegisterRequest, AuthResponse>(
                "/register",
                doc = OpenApiDoc(summary = "Register an AI Gateway account", tags = listOf("ai-gateway", "account"), errorStatuses = setOf(400, 409))
            ) { request ->
                PluginResult(body = repository.register(request))
            }
            post<LoginRequest, AuthResponse>(
                "/login",
                doc = OpenApiDoc(summary = "Login and receive JWT tokens", tags = listOf("ai-gateway", "account"), errorStatuses = setOf(401, 403))
            ) { request ->
                PluginResult(body = repository.login(request))
            }
            post<RefreshRequest, AuthResponse>(
                "/refresh",
                doc = OpenApiDoc(summary = "Refresh a JWT access token", tags = listOf("ai-gateway", "account"), errorStatuses = setOf(401, 403))
            ) { request ->
                PluginResult(body = repository.refresh(request))
            }
            route("/me") {
                interceptors(JwtAuthInterceptor::class)
                get<AccountUserView>(
                    doc = OpenApiDoc(summary = "Get current account", tags = listOf("ai-gateway", "account"), errorStatuses = setOf(401))
                ) {
                    PluginResult(body = repository.me(requireAccountPrincipal()))
                }
            }
        }

        route("/admin") {
            interceptors(AdminOnlyInterceptor::class)
            get<AccountUserListResponse>(
                "/users",
                doc = OpenApiDoc(summary = "List AI Gateway users", tags = listOf("ai-gateway", "account", "admin"), errorStatuses = setOf(401, 403))
            ) {
                PluginResult(body = repository.listUsers())
            }
            get<AccountUserView>(
                "/users/{userId}",
                doc = OpenApiDoc(summary = "Get AI Gateway user", tags = listOf("ai-gateway", "account", "admin"), errorStatuses = setOf(401, 403, 404))
            ) {
                PluginResult(body = repository.getUser(pathParameters["userId"] ?: throw com.keel.kernel.plugin.PluginApiException(400, "Missing userId")))
            }
            put<UpdateUserRequest, AccountUserView>(
                "/users/{userId}",
                doc = OpenApiDoc(summary = "Update AI Gateway user", tags = listOf("ai-gateway", "account", "admin"), errorStatuses = setOf(400, 401, 403, 404))
            ) { request ->
                PluginResult(body = repository.updateUser(pathParameters["userId"] ?: throw com.keel.kernel.plugin.PluginApiException(400, "Missing userId"), request, requireAccountPrincipal()))
            }
            post<AccountUserView>(
                "/users/{userId}/suspend",
                doc = OpenApiDoc(summary = "Suspend AI Gateway user", tags = listOf("ai-gateway", "account", "admin"), errorStatuses = setOf(401, 403, 404))
            ) {
                PluginResult(body = repository.updateStatus(pathParameters["userId"] ?: throw com.keel.kernel.plugin.PluginApiException(400, "Missing userId"), AccountStatuses.SUSPENDED, requireAccountPrincipal()))
            }
            post<AccountUserView>(
                "/users/{userId}/activate",
                doc = OpenApiDoc(summary = "Activate AI Gateway user", tags = listOf("ai-gateway", "account", "admin"), errorStatuses = setOf(401, 403, 404))
            ) {
                PluginResult(body = repository.updateStatus(pathParameters["userId"] ?: throw com.keel.kernel.plugin.PluginApiException(400, "Missing userId"), AccountStatuses.ACTIVE, requireAccountPrincipal()))
            }
            get<AccountGroupListResponse>(
                "/groups",
                doc = OpenApiDoc(summary = "List AI Gateway user groups", tags = listOf("ai-gateway", "account", "admin"), errorStatuses = setOf(401, 403))
            ) {
                PluginResult(body = repository.listGroups())
            }
            post<CreateGroupRequest, AccountGroupView>(
                "/groups",
                doc = OpenApiDoc(summary = "Create AI Gateway user group", tags = listOf("ai-gateway", "account", "admin"), errorStatuses = setOf(400, 401, 403, 409))
            ) { request ->
                PluginResult(body = repository.createGroup(request, requireAccountPrincipal()))
            }
        }
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        dbFactory.close()
    }
}
