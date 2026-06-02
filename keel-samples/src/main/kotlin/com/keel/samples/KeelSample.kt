package com.keel.samples

import com.keel.kernel.config.runKeel
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.token.TokenPlugin
import com.keel.samples.authsample.AuthSamplePlugin
import com.keel.samples.observability.ObservabilityPlugin
import com.keel.samples.ordersample.OrderSamplePlugin
import com.keel.samples.productsample.ProductSamplePlugin
import io.ktor.server.http.content.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get

/**
 * Sample application demonstrating the Keel framework.
 *
 * This minimal example shows how to:
 * 1. Implement plugins via StandardKeelPlugin (or capability interfaces)
 * 2. Register plugins with per-plugin enable + hot reload switches
 * 3. Let the framework handle:
 *    - Plugin lifecycle (onInit, onStart, onStop, onDispose)
 *    - Plugin route mounting
 *    - System routes at /api/_system/
 *    - Gateway interceptor for plugin status checking
 *    - Config hot-reloader in development mode
 *    - Dev HotReload for plugins that opt in via plugin(..., hotReloadEnabled = true)
 *
 * Configuration:
 * - System Property: -Dkeel.env=development (or production)
 * - Environment Variable: KEEL_ENV=development (or production)
 *
 * Hot reload behavior:
 * - Dev mode enables hot reload globally by default.
 * - Each plugin can opt in/out with plugin(..., hotReloadEnabled = true|false).
 * - Call runKeel { enablePluginHotReload(false) } to disable dev hot reload entirely.
 *
 * Run this app and visit:
 * - http://localhost:8080/api/plugins/helloworld
 * - http://localhost:8080/api/plugins/helloworld/version
 * - http://localhost:8080/api/plugins/helloworld/status
 * - http://localhost:8080/api/_system/plugins
 * - http://localhost:8080/api/_system/health
 *
 * To run with development mode (enables hot reload):
 *   java -Dkeel.env=development -jar keel-samples.jar
 *
 * Or set environment variable:
 *   KEEL_ENV=development java -jar keel-samples.jar
 */
fun main() = runKeel {
    // Mount Plugins
    plugin(AccountPlugin())
    plugin(TokenPlugin())
    plugin(RiskControlPlugin())
    plugin(AIRelayPlugin())
    plugin(AuthSamplePlugin())
    plugin(ProductSamplePlugin())
    plugin(OrderSamplePlugin())
    plugin(ObservabilityPlugin())

    // Disable hot reload
    enablePluginHotReload(false)

    // Global ktor plugin: CORS
    server {
        globalKtorPlugin {
            install(CORS) {
                anyHost()
            }
        }
    }

    // Global: Register static resources under an explicit sub-path.
    //
    // We deliberately avoid `staticResources("/", "static")` here. Mounting a wildcard on the root
    // path swallows every GET that is not matched by an earlier route and falls through Ktor's
    // default no-op fallback, which silently hangs the connection and blocks all subsequent
    // /api/* traffic on the same worker. Static files are served under `/static/*` instead,
    // and `/` redirects users to the Observability UI as a stable landing target.
    routing {
        staticResources("/static", "static")
        get("/") {
            call.respondRedirect("/api/plugins/observability/ui/")
        }
        get("/index") {
            call.respondRedirect("/api/plugins/observability/ui/")
        }
        get("/index.html") {
            call.respondRedirect("/api/plugins/observability/ui/")
        }
    }
}
