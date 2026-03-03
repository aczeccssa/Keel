package com.keel.contract.dto

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

/**
 * Standard Keel API response with unified format.
 *
 * All API responses should use this format:
 * {
 *   "code": 200,
 *   "message": "success",
 *   "data": {...},
 *   "timestamp": 1234567890
 * }
 *
 * @param code HTTP-style status code (200=success, 400=bad request, 401=unauthorized, 403=forbidden, 404=not found, 500=error)
 * @param message Human-readable message
 * @param data Response payload
 * @param timestamp Unix timestamp in milliseconds
 */
@Serializable
data class KeelResponse<T>(
    val code: Int,
    val message: String? = null,
    val data: T? = null,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
) {
    companion object {
        /**
         * Create a successful response.
         */
        fun <T> success(data: T? = null, message: String = "success"): KeelResponse<T> {
            return KeelResponse(code = 200, message = message, data = data)
        }

        /**
         * Create a failure response.
         */
        fun <T> failure(code: Int, message: String): KeelResponse<T> {
            return KeelResponse(code = code, message = message)
        }
    }
}
