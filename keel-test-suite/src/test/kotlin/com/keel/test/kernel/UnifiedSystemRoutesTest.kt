package com.keel.test.kernel

import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.LifecyclePlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.routing.unifiedSystemRoutes
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedSystemRoutesTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun pluginDetailExposesLifecycleAndDiagnostics() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(NoopPlugin("plug-a"))
        runBlocking {
            manager.startPlugin("plug-a")
        }

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            routing {
                unifiedSystemRoutes(manager, DefaultPluginLoader())
            }
        }

        val response = client.get("/api/_system/plugins/plug-a")
        assertEquals(HttpStatusCode.OK, response.status)

        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals("IN_PROCESS", data["runtimeMode"]!!.jsonPrimitive.content)
        assertEquals("RUNNING", data["lifecycleState"]!!.jsonPrimitive.content)
        assertEquals("HEALTHY", data["healthState"]!!.jsonPrimitive.content)
        assertEquals(1L, data["generation"]!!.jsonPrimitive.content.toLong())
        assertEquals("UNKNOWN", data["adminChannelHealth"]!!.jsonPrimitive.content)
        assertEquals("UNKNOWN", data["eventChannelHealth"]!!.jsonPrimitive.content)
    }

    @Test
    fun lifecycleEndpointsOperateThroughUnifiedManager() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(NoopPlugin("plug-b"))
        runBlocking {
            manager.startPlugin("plug-b")
        }

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            routing {
                unifiedSystemRoutes(manager, DefaultPluginLoader())
            }
        }

        assertEquals(HttpStatusCode.OK, client.post("/api/_system/plugins/plug-b/stop").status)
        assertEquals("STOPPED", healthLifecycle("plug-b"))

        assertEquals(HttpStatusCode.OK, client.post("/api/_system/plugins/plug-b/start").status)
        assertEquals("RUNNING", healthLifecycle("plug-b"))

        assertEquals(HttpStatusCode.OK, client.post("/api/_system/plugins/plug-b/reload").status)
        assertEquals(2L, healthGeneration("plug-b"))

        assertEquals(HttpStatusCode.OK, client.post("/api/_system/plugins/plug-b/replace").status)
        assertEquals(3L, healthGeneration("plug-b"))

        assertEquals(HttpStatusCode.OK, client.post("/api/_system/plugins/plug-b/dispose").status)
        assertEquals("DISPOSED", healthLifecycle("plug-b"))
    }

    @Test
    fun legacyEnableDisableEndpointsAreNotExposed() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(NoopPlugin("plug-c"))

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            routing {
                unifiedSystemRoutes(manager, DefaultPluginLoader())
            }
        }

        assertEquals(HttpStatusCode.NotFound, client.post("/api/_system/plugins/plug-c/enable").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/_system/plugins/plug-c/disable").status)
    }

    @Test
    fun versionEndpointReturnsFrameworkVersion() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            routing {
                unifiedSystemRoutes(manager, DefaultPluginLoader())
            }
        }

        val response = client.get("/api/_system/version")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
        val frameworkVersion = data["frameworkVersion"]!!.jsonPrimitive.content
        kotlin.test.assertTrue(frameworkVersion.isNotBlank())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.healthLifecycle(pluginId: String): String {
        val response = client.get("/api/_system/plugins/$pluginId/health")
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
        return data["lifecycleState"]!!.jsonPrimitive.content
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.healthGeneration(pluginId: String): Long {
        val response = client.get("/api/_system/plugins/$pluginId/health")
        val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
        return data["generation"]!!.jsonPrimitive.content.toLong()
    }

    private class NoopPlugin(id: String) : KeelPlugin, LifecyclePlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = id,
            version = "1.0.0",
            displayName = id
        )

        override suspend fun onStop(context: PluginRuntimeContext) = Unit
    }
}
