package com.keel.samples.aigateway.airelay.protocol.anthropic

object AnthropicErrorMapper {
    fun semanticStatus(errorType: String?, transportStatus: Int? = null): Int {
        return when (errorType) {
            "authentication_error" -> 401
            "invalid_request_error" -> 400
            "permission_error" -> 403
            "not_found_error" -> 404
            "request_too_large" -> 413
            "rate_limit_error" -> 429
            "overloaded_error" -> 529
            "insufficient_quota" -> 402
            "api_error" -> transportStatus?.takeIf { it >= 400 } ?: 500
            else -> transportStatus?.takeIf { it >= 400 } ?: 500
        }
    }

    fun errorTypeForStatus(status: Int): String = when (status) {
        400 -> "invalid_request_error"
        401 -> "authentication_error"
        402 -> "insufficient_quota"
        403 -> "permission_error"
        404 -> "not_found_error"
        413 -> "request_too_large"
        429 -> "rate_limit_error"
        529 -> "overloaded_error"
        else -> "api_error"
    }
}
