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
            call.respondText(SWAGGER_UI_HTML, ContentType.Text.Html)
        }

        get("/") {
            call.respondText(SWAGGER_UI_HTML, ContentType.Text.Html)
        }
    }
}

private val SWAGGER_UI_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Keel API Documentation</title>
    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
    <style>
        body { margin: 0; padding: 0; }
        #swagger-ui { max-width: 1200px; margin: 0 auto; }
    </style>
</head>
<body>
    <div id="swagger-ui"></div>
    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
    <script>
        SwaggerUIBundle({
            url: '/api/_system/docs/openapi.json',
            dom_id: '#swagger-ui',
            presets: [
                SwaggerUIBundle.presets.apis,
                SwaggerUIBundle.SwaggerUIStandalonePreset
            ],
            layout: 'BaseLayout',
            deepLinking: true,
            defaultModelsExpandDepth: 1
        });
    </script>
</body>
</html>
""".trimIndent()
