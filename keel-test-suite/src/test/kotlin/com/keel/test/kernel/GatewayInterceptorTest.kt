package com.keel.test.kernel

import com.keel.contract.dto.KeelResponse
import com.keel.kernel.plugin.KPlugin
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginManager
import com.keel.kernel.routing.GatewayInterceptor
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayInterceptorTest {

    @Test
    fun blocksRequestsForDisabledPlugin() = testApplication {
        val koin = startKoin {}.koin
        val manager = PluginManager(koin)
        val plugin = NoopPlugin("plug-a")
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
                get("/api/plugins/plug-a") {
                    call.respond(KeelResponse.success(data = "ok"))
                }
            }
        }

        val response = client.get("/api/plugins/plug-a")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)

        stopKoin()
    }

    @Test
    fun allowsRequestsForEnabledPlugin() = testApplication {
        val koin = startKoin {}.koin
        val manager = PluginManager(koin)
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
                get("/api/plugins/plug-b") {
                    call.respond(KeelResponse.success(data = "ok"))
                }
            }

            run {
                manager.initPlugin(plugin.pluginId)
                val scope = koin.getOrCreateScope(plugin.pluginId, named(plugin.pluginId))
                manager.installPlugin(plugin.pluginId, scope)
                manager.enablePlugin(plugin.pluginId, routing { })
            }
        }

        val response = client.get("/api/plugins/plug-b")
        assertEquals(HttpStatusCode.OK, response.status)

        stopKoin()
    }

    private class NoopPlugin(id: String) : KPlugin {
        override val pluginId: String = id
        override val version: String = "1.0.0"

        override suspend fun onInit(context: PluginInitContext) {}
        override suspend fun onInstall(scope: Scope) {}
        override suspend fun onEnable(routing: Routing) {}
        override suspend fun onDisable() {}
    }
}
