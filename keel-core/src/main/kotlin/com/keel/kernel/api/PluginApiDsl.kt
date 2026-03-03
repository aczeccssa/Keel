package com.keel.kernel.api

import com.keel.kernel.config.KeelConstants
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.route

/**
 * DSL wrapper for plugin API routes.
 * Automatically prefixes all nested routes with `/api/plugins/{pluginId}`.
 *
 * Usage:
 * ```kotlin
 * override suspend fun onEnable(routing: Routing) {
 *     routing.pluginApi(pluginId) {
 *         get("/notes") { ... }
 *         post("/notes") { ... }
 *     }
 * }
 * ```
 *
 * @param pluginId The unique plugin identifier used for route prefixing
 * @param block Route configuration block
 */
fun Routing.pluginApi(pluginId: String, block: Route.() -> Unit) {
    val basePath = "${KeelConstants.PLUGIN_API_PREFIX}/$pluginId"
    route(basePath) {
        attributes.put(TypedRouteBasePathKey, basePath)
        block()
    }
}
