package com.keel.test.kernel

import com.keel.kernel.plugin.JvmCommunicationMode
import com.keel.kernel.plugin.JvmCommunicationStrategy
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginRecoveryPolicy
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.PluginServiceType
import com.keel.kernel.plugin.UnifiedPluginManager
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class ExternalJvmE2EIntegrationTest {
    private val pluginId = "external-e2e"

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun processStartupAndHandshakeInventoryAndStaticSseProxy() = testApplication {
        val manager = newManager()
        manager.registerPlugin(ExternalE2EPlugin())

        application {
            install(SSE)
            routing {
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin(pluginId)
            }
        }
        startApplication()

        val snapshot = manager.getRuntimeSnapshot(pluginId)
        assertNotNull(snapshot)
        assertEquals(PluginRuntimeMode.EXTERNAL_JVM, snapshot.runtimeMode)
        assertEquals(JvmCommunicationMode.UDS, snapshot.diagnostics.activeCommunicationMode)
        assertTrue(snapshot.diagnostics.processAlive == true)

        val staticResp = client.get("/api/plugins/$pluginId/ui/index.html")
        assertEquals(HttpStatusCode.OK, staticResp.status)
        assertTrue(staticResp.bodyAsText().contains("external-e2e-static"))
        kotlinx.coroutines.runBlocking { manager.stopAll() }
    }

    @Test
    fun highConcurrencyNoPoolExhaustion() = testApplication {
        val manager = newManager()
        manager.registerPlugin(ExternalE2EPlugin())

        application {
            install(SSE)
            routing {
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin(pluginId)
            }
        }
        startApplication()

        val statuses = coroutineScope {
            (1..200).map {
                async {
                    client.get("/api/plugins/$pluginId/ping").status.value
                }
            }.awaitAll()
        }
        val successCount = statuses.count { it in 200..299 }
        assertTrue(statuses.none { it >= 500 }, "Expected no 5xx responses, but got ${statuses.groupingBy { it }.eachCount()}")
        assertTrue(successCount >= 180, "Expected >=180 successful responses, got $successCount")
        val snapshot = manager.getRuntimeSnapshot(pluginId)
        assertNotNull(snapshot)
        assertTrue(snapshot.diagnostics.processAlive == true)
        kotlinx.coroutines.runBlocking { manager.stopAll() }
    }

    @Test
    fun childCrashAutoRestartWithRuntimeFallbackToTcp() = testApplication {
        val manager = newManager()
        manager.registerPlugin(ExternalE2EPlugin())

        application {
            install(SSE)
            routing {
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin(pluginId)
            }
        }
        startApplication()

        val before = manager.getRuntimeSnapshot(pluginId)
        val pidBefore = before?.processId
        assertNotNull(pidBefore)
        manager.getProcessHandle(pluginId)?.destroyForcibly()

        waitUntil(8000) {
            val current = manager.getRuntimeSnapshot(pluginId) ?: return@waitUntil false
            current.processId != null &&
                current.processId != pidBefore &&
                current.diagnostics.processAlive == true &&
                current.diagnostics.activeCommunicationMode == JvmCommunicationMode.TCP
        }

        val response = client.get("/api/plugins/$pluginId/ping")
        assertEquals(HttpStatusCode.OK, response.status)
        kotlinx.coroutines.runBlocking { manager.stopAll() }
    }

    @Test
    fun startupFallbackUdsToTcp() = runBlocking {
        val longRoot = File("/tmp/" + "uds-fail-".repeat(20)).absoluteFile
        val manager = newManager(longRoot)
        manager.registerPlugin(ExternalE2EPlugin())
        manager.startPlugin(pluginId)
        val snapshot = manager.getRuntimeSnapshot(pluginId)
        assertNotNull(snapshot)
        assertEquals(JvmCommunicationMode.TCP, snapshot.diagnostics.activeCommunicationMode)
        assertTrue(snapshot.diagnostics.fallbackActivated)
        manager.stopAll()
    }

    @Test
    fun serviceDeclarationEnforced() = runBlocking {
        val manager = newManager()
        val error = runCatching {
            manager.registerPlugin(InvalidServiceDeclarationPlugin())
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.message?.contains("missing service declarations") == true)
        manager.stopAll()
    }

    private fun newManager(runtimeRoot: File = File("/tmp/keel-test-e2e")): UnifiedPluginManager {
        val koin = startKoin {}.also { koinStarted = true }.koin
        return UnifiedPluginManager(koin, runtimeRoot = runtimeRoot)
    }

    private suspend fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(100)
        }
        error("Condition was not satisfied within ${timeoutMs}ms")
    }

    class ExternalE2EPlugin : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "external-e2e",
            version = "1.0.0",
            displayName = "external-e2e",
            defaultRuntimeMode = PluginRuntimeMode.EXTERNAL_JVM,
            communicationStrategy = JvmCommunicationStrategy(
                preferredMode = JvmCommunicationMode.UDS,
                fallbackMode = JvmCommunicationMode.TCP,
                maxAttempts = 3
            ),
            supportedServices = setOf(
                PluginServiceType.ENDPOINT,
                PluginServiceType.SSE,
                PluginServiceType.STATIC_RESOURCE
            ),
            maxConcurrentCalls = 512,
            recoveryPolicy = PluginRecoveryPolicy(
                maxRestarts = 5,
                baseBackoffMs = 50,
                maxBackoffMs = 500,
                resetWindowMs = 3000
            ),
            healthCheckIntervalMs = 200
        )

        override fun endpoints() = PluginEndpointBuilders.pluginEndpoints(descriptor.pluginId) {
            get<String>("/ping") {
                PluginResult(body = "pong")
            }
            sse("/stream") {
                send(ServerSentEvent(data = "ready", event = "event"))
                send(ServerSentEvent(data = "tail", event = "event"))
            }
            staticResources(path = "/ui", basePackage = "external-e2e", index = "index.html")
        }
    }

    private class InvalidServiceDeclarationPlugin : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "invalid-service-declaration",
            version = "1.0.0",
            displayName = "invalid-service-declaration",
            defaultRuntimeMode = PluginRuntimeMode.EXTERNAL_JVM,
            supportedServices = setOf(PluginServiceType.ENDPOINT)
        )

        override fun endpoints() = PluginEndpointBuilders.pluginEndpoints(descriptor.pluginId) {
            staticResources(path = "/ui", basePackage = "external-e2e", index = "index.html")
        }
    }
}
