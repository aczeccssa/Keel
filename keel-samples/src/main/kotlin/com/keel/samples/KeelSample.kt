package com.keel.samples

import com.keel.kernel.config.KeelEngine
import com.keel.kernel.config.runKeel
import com.keel.samples.dbdemo.DbDemoPlugin
import com.keel.samples.helloworld.HelloWorldPlugin
import com.keel.samples.observability.ObservabilityPlugin
import io.ktor.server.http.content.*

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
    plugin(HelloWorldPlugin())
    plugin(DbDemoPlugin())
    plugin(ObservabilityPlugin())

    // Disable hot reload
    enablePluginHotReload(false)

    // Global: Register global static resources
    routing {
        staticResources("/", "static")
    }
}
