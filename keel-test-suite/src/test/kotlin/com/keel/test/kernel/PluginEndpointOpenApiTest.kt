package com.keel.test.kernel

import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiAggregator
import com.keel.openapi.runtime.OpenApiDoc
import com.keel.openapi.runtime.OpenApiRegistry
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class PluginEndpointOpenApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var koinStarted = false

    @AfterTest
    fun teardown() {
        OpenApiRegistry.clear()
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun `sse and static resources appear in OpenAPI with non json content types`() {
        OpenApiRegistry.clear()
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(TestPlugin())

        io.ktor.server.testing.testApplication {
            application {
                install(SSE)
                routing {
                    manager.mountRoutes(this)
                }
                kotlinx.coroutines.runBlocking {
                    manager.startPlugin("route-test")
                }
            }

            client.get("/api/plugins/route-test")
            val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
            val sseContent = spec["paths"]!!.jsonObject["/api/plugins/route-test/stream"]!!
                .jsonObject["get"]!!.jsonObject["responses"]!!.jsonObject["200"]!!
                .jsonObject["content"]!!.jsonObject
            assertTrue("text/event-stream" in sseContent)
            assertTrue("application/json" !in sseContent)

            val staticContent = spec["paths"]!!.jsonObject["/api/plugins/route-test/ui"]!!
                .jsonObject["get"]!!.jsonObject["responses"]!!.jsonObject["200"]!!
                .jsonObject["content"]!!.jsonObject
            assertTrue("application/octet-stream" in staticContent)
            assertTrue("text/html" in staticContent)
            assertTrue("application/json" !in staticContent)
        }
    }

    private class TestPlugin : StandardKeelPlugin {
        private val events = MutableSharedFlow<String>(extraBufferCapacity = 1)

        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "route-test",
            version = "1.0.0",
            displayName = "route-test"
        )

        override suspend fun onInit(context: PluginInitContext) {
            events.tryEmit("""{"type":"ready"}""")
        }

        override suspend fun onStop(context: PluginRuntimeContext) = Unit

        override fun endpoints() = PluginEndpointBuilders.pluginEndpoints(descriptor.pluginId) {
            get<RedirectMessage>(doc = OpenApiDoc(summary = "Open UI")) {
                PluginResult(
                    status = 302,
                    headers = mapOf(HttpHeaders.Location to listOf("/api/plugins/route-test/ui/index.html")),
                    body = RedirectMessage("Open UI")
                )
            }

            sse("/stream", doc = OpenApiDoc(summary = "Stream events")) {
                try {
                    events.collect { payload ->
                        send(ServerSentEvent(data = payload, event = "event"))
                    }
                } catch (_: ClosedWriteChannelException) {
                    // Client disconnected.
                }
            }

            staticResources(
                path = "/ui",
                basePackage = "static",
                doc = OpenApiDoc(summary = "Static UI"),
                index = "index.html"
            )
        }
    }

    @kotlinx.serialization.Serializable
    private data class RedirectMessage(
        val message: String
    )
}
