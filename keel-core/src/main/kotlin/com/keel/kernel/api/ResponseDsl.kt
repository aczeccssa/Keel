package com.keel.kernel.api

import io.ktor.http.HttpStatusCode

/**
 * DSL marker class holding OpenAPI response metadata for a route handler.
 * These functions are no-ops at runtime and only serve route-documentation metadata.
 */
object ResponseDsl {

    /**
     * Declares a successful (200 OK) response returning `T` wrapped in `KeelResponse<T>`.
     *
     * @param T The data type returned in KeelResponse.data
     * @param description Human-readable description of the success response
     */
    inline fun <reified T> respondsOk(description: String = "Successful operation") {
        // No-op at runtime. Serves as a marker for OpenAPI spec generation.
    }

    /**
     * Declares a 201 Created response returning `T` wrapped in `KeelResponse<T>`.
     *
     * @param T The data type returned in KeelResponse.data
     * @param description Human-readable description of the created response
     */
    inline fun <reified T> respondsCreated(description: String = "Resource created") {
        // No-op at runtime.
    }

    /**
     * Declares an error response with the given status code.
     *
     * @param status HTTP status code for the error
     * @param description Human-readable description of the error response
     */
    fun respondsError(
        status: HttpStatusCode = HttpStatusCode.InternalServerError,
        description: String = "Error response"
    ) {
        // No-op at runtime.
    }

    /**
     * Declares the standard set of error responses (400, 404, 500)
     * that most Keel endpoints can return.
     */
    fun respondsStandardErrors() {
        // No-op at runtime. Expands to:
        // 400 Bad Request — "Invalid request parameters"
        // 404 Not Found — "Resource not found"
        // 500 Internal Server Error — "Internal server error"
    }
}
