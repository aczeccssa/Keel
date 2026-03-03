package com.keel.test.kernel

import com.keel.kernel.config.KeelConstants
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.plugin.KPlugin
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginManager
import com.keel.kernel.routing.systemRoutes
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.scope.Scope
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemRoutesTest {

    @Test
    fun systemHealthEndpointWorks() = testApplication {
        val koin = startKoin {}.koin
        val manager = PluginManager(koin)
        val loader = DefaultPluginLoader()

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            routing {
                systemRoutes(manager, loader)
            }
        }

        val response = client.get("${KeelConstants.SYSTEM_API_PREFIX}/health")
        assertEquals(HttpStatusCode.OK, response.status)

        stopKoin()
    }

    @Test
    fun pluginsListIncludesRegisteredPlugins() = testApplication {
        val koin = startKoin {}.koin
        val manager = PluginManager(koin)
        val loader = DefaultPluginLoader()
        manager.registerPlugin(NoopPlugin("plug-x"))

        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            routing {
                systemRoutes(manager, loader)
            }
        }

        val response = client.get("${KeelConstants.SYSTEM_API_PREFIX}/plugins")
        assertEquals(HttpStatusCode.OK, response.status)

        stopKoin()
    }

    private class NoopPlugin(id: String) : KPlugin {
        override val pluginId: String = id
        override val version: String = "1.0.0"
        override suspend fun onInit(context: PluginInitContext) {}
        override suspend fun onInstall(scope: Scope) {}
        override suspend fun onEnable(routing: io.ktor.server.routing.Routing) {}
        override suspend fun onDisable() {}
    }
}
