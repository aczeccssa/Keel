package com.keel.samples.aigateway.account

import com.keel.kernel.plugin.KeelInterceptorResult
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor

class JwtAuthInterceptor(
    private val repository: AccountRepository
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult {
        val principal = repository.verifyJwt(context.requestHeaders["Authorization"]?.firstOrNull())
            ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        context.principal = principal
        context.attributes["ai.userId"] = principal.userId
        context.attributes["ai.role"] = principal.role
        context.attributes["ai.groupId"] = principal.groupId
        return next()
    }
}

class AdminOnlyInterceptor(
    private val repository: AccountRepository
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult {
        val principal = repository.verifyJwt(context.requestHeaders["Authorization"]?.firstOrNull())
            ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        if (principal.role != AccountRoles.ADMIN) {
            return KeelInterceptorResult.reject(403, "Forbidden")
        }
        context.principal = principal
        context.attributes["ai.userId"] = principal.userId
        context.attributes["ai.role"] = principal.role
        context.attributes["ai.groupId"] = principal.groupId
        return next()
    }
}

fun KeelRequestContext.requireAccountPrincipal(): AccountPrincipal {
    return principal as? AccountPrincipal ?: throw com.keel.kernel.plugin.PluginApiException(401, "Unauthorized")
}
