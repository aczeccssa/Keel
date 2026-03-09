package com.keel.test.kernel

import com.keel.kernel.config.KeelServerConfig
import com.keel.kernel.config.buildKeel
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.routing.UnifiedSystemRouteInstaller
import io.ktor.client.request.get
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KtorPluginScopeConfigTest {
    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun globalKtorPluginAppliesToPluginAndSystemRoutes() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(PingPlugin("plug-global"))

        val serverConfig = KeelServerConfig().apply {
            globalKtorPlugin {
                install(globalHeaderPlugin)
            }
        }

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            serverConfig.installConfiguredGlobalKtorPlugins(this)
            routing {
                manager.mountRoutes(this)
                UnifiedSystemRouteInstaller.install(this, manager, DefaultPluginLoader(), null)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("plug-global")
            }
        }

        val pluginResponse = client.get("/api/plugins/plug-global/ping")
        val systemResponse = client.get("/api/_system/health")
        assertEquals("enabled", pluginResponse.headers["X-Global-Scope"])
        assertEquals("enabled", systemResponse.headers["X-Global-Scope"])
    }

    @Test
    fun serviceScopedKtorPluginOnlyAppliesToConfiguredPluginRouteTree() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(
            plugin = PingPlugin("plug-a"),
            serviceRouteInstallers = listOf({
                install(plugAServiceScopePlugin)
            })
        )
        manager.registerPlugin(PingPlugin("plug-b"))

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            routing {
                manager.mountRoutes(this)
                UnifiedSystemRouteInstaller.install(this, manager, DefaultPluginLoader(), null)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("plug-a")
                manager.startPlugin("plug-b")
            }
        }

        val pluginA = client.get("/api/plugins/plug-a/ping")
        val pluginB = client.get("/api/plugins/plug-b/ping")
        val system = client.get("/api/_system/health")

        assertEquals("plug-a", pluginA.headers["X-Service-Scope"])
        assertNull(pluginB.headers["X-Service-Scope"])
        assertNull(system.headers["X-Service-Scope"])
    }

    @Test
    fun scopeResponsibilitiesAreSeparatedBetweenGlobalAndServiceScopes() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(
            plugin = PingPlugin("plug-a"),
            serviceRouteInstallers = listOf({
                install(plugAServiceScopePlugin)
            })
        )
        manager.registerPlugin(PingPlugin("plug-b"))

        val serverConfig = KeelServerConfig().apply {
            globalKtorPlugin {
                install(globalHeaderPlugin)
            }
        }

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            serverConfig.installConfiguredGlobalKtorPlugins(this)
            routing {
                manager.mountRoutes(this)
                UnifiedSystemRouteInstaller.install(this, manager, DefaultPluginLoader(), null)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("plug-a")
                manager.startPlugin("plug-b")
            }
        }

        val pluginA = client.get("/api/plugins/plug-a/ping")
        val pluginB = client.get("/api/plugins/plug-b/ping")
        val system = client.get("/api/_system/health")

        assertEquals("enabled", pluginA.headers["X-Global-Scope"])
        assertEquals("plug-a", pluginA.headers["X-Service-Scope"])

        assertEquals("enabled", pluginB.headers["X-Global-Scope"])
        assertNull(pluginB.headers["X-Service-Scope"])

        assertEquals("enabled", system.headers["X-Global-Scope"])
        assertNull(system.headers["X-Service-Scope"])
    }

    @Test
    fun kernelBuilderPluginSignatureRemainsCompatibleWithAndWithoutScopeConfig() {
        val kernel = buildKeel {
            plugin(PingPlugin("plain"))
            plugin(PingPlugin("scoped")) {
                install(noopServiceScopePlugin)
            }
            enablePluginHotReload(false)
        }

        assertEquals(true, kernel.pluginManager().getRuntimeConfig("plain")?.enabled)
        assertEquals(true, kernel.pluginManager().getRuntimeConfig("scoped")?.enabled)
    }

    private class PingPlugin(
        pluginId: String
    ) : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId
        )

        override suspend fun onStop(context: PluginRuntimeContext) = Unit

        override fun endpoints() = PluginEndpointBuilders.pluginEndpoints(descriptor.pluginId) {
            get<String>("/ping") {
                PluginResult(body = "pong")
            }
        }
    }

    companion object {
        private val globalHeaderPlugin = createApplicationPlugin("global-header") {
            onCall { call ->
                call.response.headers.append("X-Global-Scope", "enabled")
            }
        }

        private val plugAServiceScopePlugin = createRouteScopedPlugin("plug-a-scope") {
            onCall { call ->
                call.response.headers.append("X-Service-Scope", "plug-a")
            }
        }

        private val noopServiceScopePlugin = createRouteScopedPlugin("noop-scope") {}
    }
}
