package com.keel.kernel.api

import com.keel.kernel.config.KeelConstants
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * DSL wrapper for system API routes.
 * Automatically prefixes all nested routes with `/api/_system`.
 *
 * Usage:
 * ```kotlin
 * routing {
 *     systemApi {
 *         get("/health") { ... }
 *         route("/plugins") { ... }
 *     }
 * }
 * ```
 *
 * @param block Route configuration block
 */
fun Route.systemApi(block: Route.() -> Unit) {
    route(KeelConstants.SYSTEM_API_PREFIX) {
        attributes.put(TypedRouteBasePathKey, KeelConstants.SYSTEM_API_PREFIX)
        block()
    }
}
