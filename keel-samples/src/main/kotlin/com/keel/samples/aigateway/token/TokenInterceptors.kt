package com.keel.samples.aigateway.token

import com.keel.contract.ai.AiPrincipal
import com.keel.contract.ai.JwtPrincipalVerifier
import com.keel.kernel.plugin.KeelInterceptorResult
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor
import com.keel.kernel.plugin.PluginApiException

class TokenJwtAuthInterceptor(
    private val verifier: JwtPrincipalVerifier
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult {
        val principal = verifier.verifyAuthorizationHeader(context.requestHeaders["Authorization"]?.firstOrNull())
            ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        context.principal = principal
        context.attributes["ai.userId"] = principal.userId
        context.attributes["ai.role"] = principal.role
        context.attributes["ai.groupId"] = principal.groupId
        return next()
    }
}

class TokenAdminInterceptor(
    private val verifier: JwtPrincipalVerifier
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult {
        val principal = verifier.verifyAuthorizationHeader(context.requestHeaders["Authorization"]?.firstOrNull())
            ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        if (principal.role != "admin") return KeelInterceptorResult.reject(403, "Forbidden")
        context.principal = principal
        context.attributes["ai.userId"] = principal.userId
        context.attributes["ai.role"] = principal.role
        context.attributes["ai.groupId"] = principal.groupId
        return next()
    }
}

fun KeelRequestContext.requireAiPrincipal(): AiPrincipal {
    return principal as? AiPrincipal ?: throw PluginApiException(401, "Unauthorized")
}
