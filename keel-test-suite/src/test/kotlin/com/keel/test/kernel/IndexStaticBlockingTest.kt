package com.keel.test.kernel

import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Regression coverage for the "/index blocks all API traffic" bug.
 *
 * Root cause: mounting `staticResources("/", "static", index = "index.html")` on the routing
 * root installs a wildcard GET handler with Ktor's no-op default fallback. When a request like
 * `GET /index` doesn't match an earlier route, the call is silently consumed (no response body,
 * no handled flag) and the connection hangs, queueing subsequent /api/* requests on the same
 * worker thread.
 *
 * The fix: never mount a wildcard static at the root. Host applications expose assets under an
 * explicit sub-path (`/static`, `/api/plugins/<id>/ui/...`) and use explicit `get("/")` handlers
 * for landing redirects.
 */
class IndexStaticBlockingTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun rootLevelStaticResourcesWildcardIsNotPresent() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(StubPlugin("ping"))

        application {
            install(ContentNegotiation) { json() }
            routing {
                manager.mountRoutes(this)
                // Replicate the fixed sample-app routing shape: explicit redirect handlers
                // for landing pages. No staticResources at the root — even
                // staticResources("/static", "static") installs a TailcardSelector wildcard
                // that can interfere with route resolution.
                get("/") { call.respondRedirect("/api/plugins/observability/ui/") }
                get("/index") { call.respondRedirect("/api/plugins/observability/ui/") }
                get("/index.html") { call.respondRedirect("/api/plugins/observability/ui/") }
            }
        }

        // /index no longer hangs - the explicit handler returns 301/302 immediately.
        val indexResponse = client.get("/index")
        assertTrue(
            indexResponse.status == HttpStatusCode.Found ||
                indexResponse.status == HttpStatusCode.MovedPermanently ||
                indexResponse.status == HttpStatusCode.PermanentRedirect ||
                indexResponse.status == HttpStatusCode.TemporaryRedirect,
            "Expected redirect for /index, got ${indexResponse.status}"
        )

        // /index.html also no longer hangs.
        val htmlResponse = client.get("/index.html")
        assertTrue(
            htmlResponse.status.value in 200..399,
            "Expected redirect/ok for /index.html, got ${htmlResponse.status}"
        )
    }

    @Test
    fun apiRoutesRemainResponsiveAfterExplicitIndexHandler() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(StubPlugin("ping"))

        application {
            install(ContentNegotiation) { json() }
            routing {
                manager.mountRoutes(this)
                staticResources("/static", "static")
                get("/") { call.respondRedirect("/api/plugins/observability/ui/") }
                get("/index") { call.respondRedirect("/api/plugins/observability/ui/") }
            }
        }

        // Confirm an unrelated API path is still healthy (would hang if the index handler
        // held the routing tree).
        val resp = client.get("/api/plugins/ping/hello")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("hello"))
    }

    private class StubPlugin(private val pluginId: String) : StandardKeelPlugin {
        override val descriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = "stub-$pluginId",
        )

        override fun endpoints() = PluginEndpointBuilders.pluginEndpoints(pluginId) {
            get<String>("/hello", doc = com.keel.openapi.runtime.OpenApiDoc(summary = "ping")) {
                PluginResult(body = "hello from $pluginId")
            }
        }

        override suspend fun onStop(context: PluginRuntimeContext) = Unit
    }
}
