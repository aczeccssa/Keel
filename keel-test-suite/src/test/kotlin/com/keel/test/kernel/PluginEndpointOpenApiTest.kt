package com.keel.test.kernel

import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.kernel.plugin.KeelInterceptorResult
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor
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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlinx.serialization.Serializable

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
                install(ContentNegotiation) { json() }
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

    @Test
    fun `typed post endpoints run interceptors and advertise configured content types`() {
        OpenApiRegistry.clear()
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(RawRoutePlugin())

        io.ktor.server.testing.testApplication {
            application {
                install(SSE)
                routing {
                    manager.mountRoutes(this)
                }
                kotlinx.coroutines.runBlocking {
                    manager.startPlugin("raw-route-test")
                }
            }

            val unauthorized = client.post("/api/plugins/raw-route-test/v1/raw") {
                contentType(ContentType.Application.Json)
                setBody("{\"value\":\"blocked\"}")
            }
            kotlin.test.assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

            val response = client.post("/api/plugins/raw-route-test/v1/raw") {
                header("X-Raw-User", "alice")
                contentType(ContentType.Application.Json)
                setBody("{\"value\":\"ok\"}")
            }
            kotlin.test.assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"principal\":\"alice\""))
            assertTrue(body.contains("\"value\":\"ok\""))

            val oversized = client.post("/api/plugins/raw-route-test/v1/raw") {
                header("X-Raw-User", "alice")
                contentType(ContentType.Application.Json)
                setBody("x".repeat(5000))
            }
            kotlin.test.assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)

            val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
            val rawContent = spec["paths"]!!.jsonObject["/api/plugins/raw-route-test/v1/raw"]!!
                .jsonObject["post"]!!.jsonObject["responses"]!!.jsonObject["200"]!!
                .jsonObject["content"]!!.jsonObject
            assertTrue("application/json" in rawContent)
        }
    }

    private class RawRoutePlugin : StandardKeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "raw-route-test",
            version = "1.0.0",
            displayName = "raw-route-test"
        )

        override fun modules() = listOf(
            org.koin.dsl.module {
                single { RawRouteAuthInterceptor() }
            }
        )

        override fun endpoints() = PluginEndpointBuilders.pluginEndpoints(descriptor.pluginId) {
            interceptors(RawRouteAuthInterceptor::class)
            route("/v1") {
                post<RawRoutePayload, RawRouteResponse>(
                    "/raw",
                    doc = OpenApiDoc(summary = "Raw route", tags = listOf("raw")),
                    executionPolicy = com.keel.kernel.plugin.EndpointExecutionPolicy(maxPayloadBytes = 4096)
                ) { request ->
                    PluginResult(body = RawRouteResponse(principal = principal?.toString(), value = request.value))
                }
            }
        }
    }

    @Serializable
    private data class RawRoutePayload(val value: String)

    @Serializable
    private data class RawRouteResponse(val principal: String?, val value: String)

    private class RawRouteAuthInterceptor : KeelRequestInterceptor {
        override suspend fun intercept(
            context: KeelRequestContext,
            next: suspend () -> KeelInterceptorResult
        ): KeelInterceptorResult {
            val user = context.requestHeaders["X-Raw-User"]?.firstOrNull()
                ?: return KeelInterceptorResult.reject(401, "Unauthorized")
            context.principal = user
            return next()
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
                basePackage = "ui/observability-ui",
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
