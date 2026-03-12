package com.keel.test.kernel

import com.keel.kernel.config.KeelServerConfig
import com.keel.kernel.config.buildKeel
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginKtorConfig
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.routing.UnifiedSystemRouteInstaller
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorPluginScopeConfigTest {
    @AfterTest
    fun teardown() {
        runCatching {
            stopKoin()
        }
    }

    @Test
    fun globalKtorPluginAppliesToPluginAndSystemRoutes() = testApplication {
        val koin = startKoin {}.koin
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
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(PingPlugin("plug-a", serviceScopeHeader = "plug-a"))
        manager.registerPlugin(PingPlugin("plug-b"))

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            manager.installConfiguredPluginApplicationKtorPlugins(this)
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
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(PingPlugin("plug-a", serviceScopeHeader = "plug-a"))
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
            manager.installConfiguredPluginApplicationKtorPlugins(this)
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
    fun pluginApplicationScopeAppliesToExternalJvmPluginRoutesAndSystemRoutes() = testApplication {
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(
            PingPlugin(
                pluginId = "plug-external",
                applicationScopeHeader = "external-scope",
                runtimeMode = PluginRuntimeMode.EXTERNAL_JVM
            )
        )

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            manager.installConfiguredPluginApplicationKtorPlugins(this)
            routing {
                manager.mountRoutes(this)
                UnifiedSystemRouteInstaller.install(this, manager, DefaultPluginLoader(), null)
            }
        }

        val pluginResponse = client.get("/api/plugins/plug-external/ping")
        val systemResponse = client.get("/api/_system/health")

        assertEquals(HttpStatusCode.ServiceUnavailable, pluginResponse.status)
        assertEquals("external-scope", pluginResponse.headers["X-Plugin-App-Scope"])
        assertEquals("external-scope", systemResponse.headers["X-Plugin-App-Scope"])
    }

    @Test
    fun kernelBuilderPluginSignatureSupportsPluginAutonomyWithoutHostScopeBlock() {
        val kernel = buildKeel {
            plugin(PingPlugin("plain"))
            plugin(PingPlugin("scoped", serviceScopeHeader = "scoped"))
            enablePluginHotReload(false)
        }

        assertEquals(true, kernel.pluginManager().getRuntimeConfig("plain")?.enabled)
        assertEquals(true, kernel.pluginManager().getRuntimeConfig("scoped")?.enabled)
    }

    @Test
    fun pluginApplicationInstallOrderIsDeterministicByPluginId() = testApplication {
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(PingPlugin("plug-b", applicationScopeHeader = "plug-b"))
        manager.registerPlugin(PingPlugin("plug-a", applicationScopeHeader = "plug-a"))

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            manager.installConfiguredPluginApplicationKtorPlugins(this)
            routing {
                manager.mountRoutes(this)
                UnifiedSystemRouteInstaller.install(this, manager, DefaultPluginLoader(), null)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("plug-a")
                manager.startPlugin("plug-b")
            }
        }

        val systemResponse = client.get("/api/_system/health")
        assertEquals(listOf("plug-a", "plug-b"), systemResponse.headers.getAll("X-Plugin-App-Scope"))
    }

    @Test
    fun disabledPluginApplicationScopeIsNotInstalled() = testApplication {
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(PingPlugin("plug-enabled", applicationScopeHeader = "enabled-header"))
        manager.registerPlugin(
            plugin = PingPlugin("plug-disabled", applicationScopeHeader = "disabled-header"),
            enabledOverride = false
        )

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            manager.installConfiguredPluginApplicationKtorPlugins(this)
            routing {
                manager.mountRoutes(this)
                UnifiedSystemRouteInstaller.install(this, manager, DefaultPluginLoader(), null)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("plug-enabled")
            }
        }

        val systemResponse = client.get("/api/_system/health")
        assertEquals(listOf("enabled-header"), systemResponse.headers.getAll("X-Plugin-App-Scope"))
    }

    @Test
    fun disabledPluginDoesNotCauseDuplicateApplicationInstallConflict() = testApplication {
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(DuplicateAppScopePlugin("dup-enabled"))
        manager.registerPlugin(plugin = DuplicateAppScopePlugin("dup-disabled"), enabledOverride = false)

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            manager.installConfiguredPluginApplicationKtorPlugins(this)
            routing {
                manager.mountRoutes(this)
                UnifiedSystemRouteInstaller.install(this, manager, DefaultPluginLoader(), null)
            }
        }

        val failure = runCatching { startApplication() }.exceptionOrNull()
        assertNull(failure)
    }

    @Test
    fun duplicatePluginApplicationInstallFailsFastWithPluginIdContext() = testApplication {
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(DuplicateAppScopePlugin("dup-a"))
        manager.registerPlugin(DuplicateAppScopePlugin("dup-b"))

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            manager.installConfiguredPluginApplicationKtorPlugins(this)
            routing {
                manager.mountRoutes(this)
                UnifiedSystemRouteInstaller.install(this, manager, DefaultPluginLoader(), null)
            }
        }

        val failure = runCatching { startApplication() }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure.message?.contains("pluginId=dup-b") == true)
    }

    private class PingPlugin(
        pluginId: String,
        private val applicationScopeHeader: String? = null,
        private val serviceScopeHeader: String? = null,
        runtimeMode: PluginRuntimeMode = PluginRuntimeMode.IN_PROCESS
    ) : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId,
            defaultRuntimeMode = runtimeMode
        )

        override suspend fun onStop(context: PluginRuntimeContext) = Unit

        override fun ktorPlugins(): PluginKtorConfig = PluginKtorConfig().apply {
            if (applicationScopeHeader != null) {
                application {
                    install(pluginApplicationHeaderPlugin(applicationScopeHeader))
                }
            }
            if (serviceScopeHeader != null) {
                service {
                    install(serviceHeaderPlugin(serviceScopeHeader))
                }
            }
        }

        override fun endpoints() = PluginEndpointBuilders.pluginEndpoints(descriptor.pluginId) {
            get<String>("/ping") {
                PluginResult(body = "pong")
            }
        }
    }

    private class DuplicateAppScopePlugin(
        pluginId: String
    ) : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId
        )

        override fun ktorPlugins(): PluginKtorConfig = PluginKtorConfig().apply {
            application {
                install(duplicatedApplicationPlugin)
            }
        }

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

        private fun pluginApplicationHeaderPlugin(headerValue: String) = createApplicationPlugin("plugin-app-scope-$headerValue") {
            onCall { call ->
                call.response.headers.append("X-Plugin-App-Scope", headerValue)
            }
        }

        private fun serviceHeaderPlugin(headerValue: String) = createRouteScopedPlugin("service-scope-$headerValue") {
            onCall { call ->
                call.response.headers.append("X-Service-Scope", headerValue)
            }
        }

        private val duplicatedApplicationPlugin = createApplicationPlugin("duplicated-application-plugin") {}
    }
}
