package com.keel.openapi.runtime

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Installs OpenAPI documentation routes under the given route scope.
 *
 * - `GET /openapi.json` — serves the aggregated OpenAPI 3.1.0 spec as JSON
 * - `GET /` — serves Swagger UI HTML page
 */
fun Route.openApiRoutes() {
    route("/docs") {
        get("/openapi.json") {
            val portSuffix = when (val port = call.request.port()) {
                80, 443 -> ""
                else -> ":$port"
            }
            val serverUrl = "http://${call.request.host()}$portSuffix"
            val spec = OpenApiAggregator.buildSpec(serverUrl)
            call.respondText(spec, ContentType.Application.Json)
        }

        get {
            call.respondText(ResourceReader.readAsString("/openapi/swagger-ui.html"), ContentType.Text.Html)
        }

        get("/") {
            call.respondText(ResourceReader.readAsString("/openapi/swagger-ui.html"), ContentType.Text.Html)
        }
    }
}
