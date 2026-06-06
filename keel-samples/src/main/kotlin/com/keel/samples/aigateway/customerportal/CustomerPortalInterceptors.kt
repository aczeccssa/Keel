package com.keel.samples.aigateway.customerportal

import com.keel.contract.ai.JwtPrincipalVerifier
import com.keel.kernel.plugin.KeelInterceptorResult
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor
import com.keel.samples.aigateway.customerportal.auth.CustomerJwtService
import com.keel.samples.aigateway.customerportal.auth.CustomerPrincipal

class CustomerJwtAuthInterceptor(
    private val jwtService: CustomerJwtService,
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult,
    ): KeelInterceptorResult {
        val token = context.requestHeaders["Authorization"]?.firstOrNull()?.removePrefix("Bearer ")
            ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        val principal = jwtService.verify(token)
            ?: return KeelInterceptorResult.reject(401, "Invalid or expired token")
        context.principal = principal
        context.attributes["customer.customerId"] = principal.customerId
        context.attributes["customer.email"] = principal.email
        return next()
    }
}

class CustomerAdminInterceptor(
    private val adminJwtVerifier: JwtPrincipalVerifier,
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult,
    ): KeelInterceptorResult {
        val principal = adminJwtVerifier.verifyAuthorizationHeader(
            context.requestHeaders["Authorization"]?.firstOrNull()
        ) ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        if (principal.role != "admin") return KeelInterceptorResult.reject(403, "Forbidden")
        context.principal = principal
        context.attributes["admin.userId"] = principal.userId
        return next()
    }
}

fun requireCustomerPrincipal(context: KeelRequestContext): CustomerPrincipal {
    return context.principal as? CustomerPrincipal
        ?: throw com.keel.kernel.plugin.PluginApiException(401, "Unauthorized")
}

fun requireAdminUserId(context: KeelRequestContext): String {
    return context.attributes["admin.userId"] as? String
        ?: throw com.keel.kernel.plugin.PluginApiException(401, "Unauthorized")
}
