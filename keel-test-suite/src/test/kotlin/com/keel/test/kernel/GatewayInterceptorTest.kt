package com.keel.test.kernel

import com.keel.contract.dto.KeelResponse
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.routing.GatewayInterceptor
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class GatewayInterceptorTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun blocksRequestsForUnavailablePluginWith503() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(NoopPlugin("plug-a"))

        application {
            install(ContentNegotiation) {
                json()
            }
            val interceptor = GatewayInterceptor(manager)
            intercept(ApplicationCallPipeline.Plugins) {
                if (interceptor.intercept(call)) finish()
            }
            routing {
                manager.mountRoutes(this)
            }
        }

        val response = client.get("/api/plugins/plug-a")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun allowsRequestsForRunningPlugin() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val plugin = NoopPlugin("plug-b")
        manager.registerPlugin(plugin)

        application {
            install(ContentNegotiation) {
                json()
            }
            val interceptor = GatewayInterceptor(manager)
            intercept(ApplicationCallPipeline.Plugins) {
                if (interceptor.intercept(call)) finish()
            }
            routing {
                manager.mountRoutes(this)
            }

            kotlinx.coroutines.runBlocking {
                manager.startPlugin(plugin.descriptor.pluginId)
            }
        }

        val response = client.get("/api/plugins/plug-b")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun unknownPluginFallsThroughTo404() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)

        application {
            install(ContentNegotiation) {
                json()
            }
            val interceptor = GatewayInterceptor(manager)
            intercept(ApplicationCallPipeline.Plugins) {
                if (interceptor.intercept(call)) finish()
            }
            routing {
                get("/healthz") {
                    call.respond(KeelResponse.success("ok"))
                }
            }
        }

        val response = client.get("/api/plugins/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    private class NoopPlugin(id: String) : StandardKeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = id,
            version = "1.0.0",
            displayName = id
        )

        override suspend fun onStop(context: PluginRuntimeContext) = Unit

        override fun endpoints() = PluginEndpointBuilders.pluginEndpoints(descriptor.pluginId) {
            get<String> {
                PluginResult(body = "ok")
            }
        }
    }
}
