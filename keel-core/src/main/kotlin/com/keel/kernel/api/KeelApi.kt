package com.keel.kernel.api

/**
 * Primary annotation for documenting Ktor route handlers in Keel.
 *
 * Place on each `get/post/put/delete` handler to include it in the
 * auto-generated OpenAPI specification.
 *
 * @param summary Short human-readable summary of what this endpoint does
 * @param description Optional longer description with details
 * @param tags OpenAPI tags for grouping endpoints (e.g., ["notes", "crud"])
 * @param successStatus Success HTTP status documented for this endpoint
 * @param errorStatuses Error HTTP statuses documented for this endpoint
 * @param responseEnvelope Whether the response schema should be wrapped as KeelResponse<T>
 */
@Target(AnnotationTarget.EXPRESSION, AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.SOURCE)
@Deprecated(
    message = "Use DSL doc = OpenApiDoc(...) instead of @KeelApi.",
    level = DeprecationLevel.ERROR
)
annotation class KeelApi(
    val summary: String = "",
    val description: String = "",
    val tags: Array<String> = [],
    val successStatus: Int = 200,
    val errorStatuses: IntArray = [],
    val responseEnvelope: Boolean = false
)
