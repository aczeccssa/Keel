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
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

// Regression coverage for the "/index blocks all API traffic" bug.
//
// Root cause: mounting static resources at the routing root installs a wildcard GET handler.
// Requests like GET /index can be consumed without producing a useful response and then block
// unrelated API traffic behind them.
//
// The fix: avoid root-level wildcard static resources and use explicit redirect handlers for
// landing routes such as "/" and "/index".
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
                // for landing pages. No staticResources at the root. Even a "/static"
                // mount installs a wildcard selector that can interfere with resolution.
                get("/") { call.respondRedirect("/api/plugins/observability/ui/") }
                get("/index") { call.respondRedirect("/api/plugins/observability/ui/") }
                get("/index.html") { call.respondRedirect("/api/plugins/observability/ui/") }
            }
            runBlocking { manager.startPlugin("ping") }
        }

        // /index no longer hangs. The test client follows redirects, so because no
        // observability plugin is mounted here the final status is 404 rather than a
        // raw 3xx. The important regression guard is that we get a prompt response.
        val indexResponse = client.get("/index")
        assertTrue(
            indexResponse.status == HttpStatusCode.NotFound || indexResponse.status.value in 300..399,
            "Expected redirect chain or 404 for /index, got ${indexResponse.status}"
        )

        // /index.html also no longer hangs.
        val htmlResponse = client.get("/index.html")
        assertTrue(
            htmlResponse.status == HttpStatusCode.NotFound || htmlResponse.status.value in 300..399,
            "Expected redirect chain or 404 for /index.html, got ${htmlResponse.status}"
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
                staticResources("/observability-ui", "ui/observability-ui")
                get("/") { call.respondRedirect("/api/plugins/observability/ui/") }
                get("/index") { call.respondRedirect("/api/plugins/observability/ui/") }
            }
            runBlocking { manager.startPlugin("ping") }
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
