package com.keel.samples

import com.keel.kernel.config.ConfigHotReloader
import com.keel.kernel.config.KeelConstants
import com.keel.kernel.config.runKernel
import com.keel.samples.observability.ObservabilityPlugin
import com.keel.samples.dbdemo.DbDemoPlugin
import com.keel.samples.helloworld.HelloWorldPlugin
import io.ktor.server.http.content.staticResources

/**
 * Sample Application demonstrating the Keel framework.
 *
 * This minimal example shows how to:
 * 1. Create a plugin by implementing KeelPlugin
 * 2. Register plugins with the Kernel
 * 3. Framework automatically handles:
 *    - Plugin lifecycle (onInit, onStart, onStop, onDispose)
 *    - Plugin route mounting
 *    - System routes at /api/_system/
 *    - Gateway interceptor for plugin status checking
 *    - Config hot-reloader in development mode
 *    - Plugin hot-reload in development mode
 *
 * Configuration Options:
 *
 * Environment Mode:
 * - System Property: -Dkeel.env=development (or production)
 * - Environment Variable: KEEL_ENV=development (or production)
 *
 * Hot Reload:
 * - Enabled by default in development mode
 * - Can be disabled with: runKernel { enablePluginHotReload(false) }
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
fun main() {
    // Server port
    val port = ConfigHotReloader.getServerPort()
    val baseUrl = "http://localhost:$port"

    // Print current environment info
    val isDev = ConfigHotReloader.isDevelopmentMode()
    val env = if (isDev) KeelConstants.ENV_DEVELOPMENT else KeelConstants.ENV_PRODUCTION
    println("========================================")
    println("  Keel Framework - Keel Sample")
    println("========================================")
    println("  Environment : $env")
    println("  Hot Reload  : $isDev")
    println("  WebUI       : $baseUrl/")
    println("  Swagger UI  : $baseUrl/api/_system/docs/")
    println("  OpenAPI JSON: $baseUrl/api/_system/docs/openapi.json")
    println("  System API  : $baseUrl/api/_system/plugins")
    println("  Observe API : $baseUrl/api/plugins/observability/topology")
    println("  Observe UI  : $baseUrl/api/plugins/observability/ui")
    println("  Plugin API  : $baseUrl/api/plugins/helloworld")
    println("  DB Demo API : $baseUrl/api/plugins/dbdemo/notes")
    println("========================================")

    // Build and run the Kernel - framework handles everything automatically
    runKernel(port) {
        plugin(HelloWorldPlugin())
        plugin(DbDemoPlugin())
        plugin(ObservabilityPlugin())

        // Optional: Disable hot reload even in development mode
        enablePluginHotReload(isDev)

        // Global: Register global static resources
        routing {
            staticResources("/", "static")
        }
    }
}
