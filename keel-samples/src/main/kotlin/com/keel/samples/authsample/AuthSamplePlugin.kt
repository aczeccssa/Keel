package com.keel.samples.authsample

import com.keel.kernel.plugin.KeelInterceptorResult
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.annotations.KeelInterceptors
import com.keel.openapi.annotations.KeelRouteInterceptors
import com.keel.samples.commerce.CommerceDemoIdentity
import com.keel.samples.commerce.CommercePrincipal
import com.keel.samples.commerce.CommerceTokenPair
import kotlinx.serialization.Serializable
import org.koin.dsl.module

@KeelApiPlugin(
    pluginId = "authsample",
    title = "Auth Sample Plugin",
    description = "Owns login, refresh, profile lookup, and the commerce showcase UI without moving auth policy into the framework",
    version = "1.0.0"
)
@KeelInterceptors(AuthInterceptor::class)
@KeelRouteInterceptors(method = "GET", path = "/admin", value = [AdminAuthInterceptor::class])
class AuthSamplePlugin : StandardKeelPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "authsample",
        version = "1.0.0",
        displayName = "Auth Sample Plugin",
        defaultRuntimeMode = PluginRuntimeMode.EXTERNAL_JVM
    )

    private lateinit var authService: AuthService

    override fun modules() = listOf(
        module {
            single { AuthService() }
            single { AuthInterceptor(get()) }
            single { AdminAuthInterceptor(get()) }
        }
    )

    override suspend fun onStart(context: PluginRuntimeContext) {
        authService = context.privateScope.get()
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        interceptors(AuthInterceptor::class)

        route("/public") {
            noInterceptors()
            get<PublicMessage> {
                PluginResult(
                    body = PublicMessage(
                        "Public commerce showcase. Sign in as user/user123 or admin/admin123 to explore catalog, cart, checkout, and admin order tools."
                    )
                )
            }
        }

        route("/session") {
            noInterceptors()

            post<LoginRequest, LoginResponse>("/login") { request ->
                PluginResult(body = authService.login(request.username, request.password))
            }

            post<RefreshTokenRequest, TokenPairData>("/refresh") { request ->
                PluginResult(body = authService.refreshToken(request.refreshToken))
            }
        }

        get<UserProfileData>("/profile") {
            val authPrincipal = principal as? CommercePrincipal ?: throw PluginApiException(401, "Unauthorized")
            PluginResult(body = authService.getUserProfile(authPrincipal.userId))
        }

        route("/admin") {
            interceptors(AdminAuthInterceptor::class)
            get<AdminMessage> {
                val authPrincipal = principal as? CommercePrincipal ?: throw PluginApiException(401, "Unauthorized")
                PluginResult(body = AdminMessage("admin:${authPrincipal.userId}"))
            }
        }

        staticResources("/showcase", "authsample-ui", index = "index.html")
    }
}

class AuthInterceptor(
    private val authService: AuthService
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult {
        val principal = authService.verifyToken(context.requestHeaders["Authorization"]?.firstOrNull())
            ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        if (!authService.hasPermission(principal, context.rawPath, context.method)) {
            return KeelInterceptorResult.reject(403, "Forbidden")
        }
        context.principal = principal
        context.attributes["principal"] = principal
        context.attributes["auth.role"] = principal.role
        return next()
    }
}

class AdminAuthInterceptor(
    private val authService: AuthService
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
        context.attributes["principal"] = principal
        context.attributes["auth.role"] = principal.role
        return next()
    }
}

class AuthService {
    fun login(username: String, password: String): LoginResponse {
        val account = CommerceDemoIdentity.authenticate(username, password)
            ?: throw PluginApiException(401, "Invalid username or password")
        return LoginResponse(
            profile = account.principal.toProfile(),
            tokenPair = account.tokenPair.toResponse(),
            authorizationHeader = "Bearer ${account.tokenPair.accessToken}"
        )
    }

    fun verifyToken(authHeader: String?): CommercePrincipal? {
        return CommerceDemoIdentity.principalFromAuthorization(authHeader)
    }

    fun hasPermission(principal: CommercePrincipal, path: String, method: String): Boolean {
        if (method != "GET") return true
        return !path.endsWith("/admin") || principal.role == "admin"
    }

    fun refreshToken(refreshToken: String): TokenPairData {
        return CommerceDemoIdentity.tokenPairFromRefresh(refreshToken)?.toResponse()
            ?: throw PluginApiException(401, "Invalid refresh token")
    }

    fun getUserProfile(userId: String): UserProfileData {
        return CommerceDemoIdentity.principalByUserId(userId)?.toProfile()
            ?: throw PluginApiException(404, "Profile not found")
    }
}

@Serializable
data class PublicMessage(
    val message: String
)

@Serializable
data class AdminMessage(
    val message: String
)

@Serializable
data class UserProfileData(
    val userId: String,
    val displayName: String,
    val role: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val profile: UserProfileData,
    val tokenPair: TokenPairData,
    val authorizationHeader: String
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

@Serializable
data class TokenPairData(
    val accessToken: String,
    val refreshToken: String
)

private fun CommercePrincipal.toProfile(): UserProfileData {
    return UserProfileData(
        userId = userId,
        displayName = displayName,
        role = role
    )
}

private fun CommerceTokenPair.toResponse(): TokenPairData {
    return TokenPairData(
        accessToken = accessToken,
        refreshToken = refreshToken
    )
}
