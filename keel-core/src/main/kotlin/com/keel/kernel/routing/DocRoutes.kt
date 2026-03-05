package com.keel.kernel.routing

import com.keel.kernel.api.systemApi
import com.keel.openapi.runtime.openApiRoutes
import io.ktor.server.routing.Route

/**
 * Mounts the OpenAPI documentation routes under /api/_system/docs.
 * Provides:
 * - GET /api/_system/docs — Swagger UI
 * - GET /api/_system/docs/openapi.json — OpenAPI 3.1.0 spec
 */
fun Route.docRoutes() {
    systemApi {
        openApiRoutes()
    }
}
