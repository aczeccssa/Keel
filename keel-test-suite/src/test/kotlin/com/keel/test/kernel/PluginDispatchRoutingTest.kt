package com.keel.test.kernel

import com.keel.kernel.plugin.EndpointExecutionPolicy
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.plugin.pluginEndpoints
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginDispatchRoutingTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun endpointTimeoutOverrideReturns504() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(TimeoutPlugin())

        application {
            install(ContentNegotiation) {
                json()
            }
            routing {
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.enablePlugin("timeout-plugin")
            }
        }

        val response = client.get("/api/plugins/timeout-plugin")
        assertEquals(HttpStatusCode.GatewayTimeout, response.status)
    }

    @Test
    fun oversizedRequestPayloadReturns413() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(PayloadPlugin())

        application {
            install(ContentNegotiation) {
                json()
            }
            routing {
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.enablePlugin("payload-plugin")
            }
        }

        val response = client.post("/api/plugins/payload-plugin") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("\"abcdef\"")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    private class TimeoutPlugin : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "timeout-plugin",
            version = "1.0.0",
            displayName = "timeout-plugin"
        )

        override suspend fun onStop(context: PluginRuntimeContext) = Unit

        override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
            get<String>(executionPolicy = EndpointExecutionPolicy(timeoutMs = 50)) {
                delay(150)
                PluginResult(body = "ok")
            }
        }
    }

    private class PayloadPlugin : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "payload-plugin",
            version = "1.0.0",
            displayName = "payload-plugin"
        )

        override suspend fun onStop(context: PluginRuntimeContext) = Unit

        override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
            post<String, String>(
                executionPolicy = EndpointExecutionPolicy(maxPayloadBytes = 4)
            ) { request ->
                PluginResult(body = request)
            }
        }
    }
}
