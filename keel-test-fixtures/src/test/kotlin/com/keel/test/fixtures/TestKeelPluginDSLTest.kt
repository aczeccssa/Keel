package com.keel.test.fixtures

import com.keel.contract.di.KeelDiQualifiers
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.EndpointPlugin
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginLifecycleState
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import io.ktor.http.HttpStatusCode
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KernelService {
    fun getStatus() = "kernel-ready"
}

class PluginService {
    fun getStatus() = "plugin-ready"
}

class ExtraPrivateService {
    fun getStatus() = "extra-private-ready"
}

@Serializable
data class TestDto(val message: String)

@Serializable
data class StatusDto(val status: String)

class SimplePlugin(
    private val id: String = "simple"
) : KeelPlugin {
    override val descriptor = PluginDescriptor(id, "1.0", "Simple")
    var started = false
    var disposed = false

    override suspend fun onStart(context: PluginRuntimeContext) {
        started = true
    }

    override suspend fun onDispose(context: PluginRuntimeContext) {
        disposed = true
    }
}

class EndpointTestPlugin(
    private val id: String = "endpoint-test",
    private val requireExtraPrivateService: Boolean = false
) : KeelPlugin, EndpointPlugin {
    override val descriptor = PluginDescriptor(id, "1.0", "Endpoint Test")
    var started = false

    override suspend fun onStart(context: PluginRuntimeContext) {
        started = true
        context.kernelKoin.get<KernelService>()
        context.privateScope.get<PluginService>()
        if (requireExtraPrivateService) {
            context.privateScope.get<ExtraPrivateService>()
        }
    }

    override fun modules(): List<Module> = listOf(
        module {
            single { PluginService() }
        }
    )

    override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
        get<StatusDto>("/status") { PluginResult(body = StatusDto("ok")) }
        post<TestDto, TestDto>("/greet") { request -> PluginResult(body = TestDto("Hello ${request.message}")) }
        put<TestDto, TestDto>("/rename") { request -> PluginResult(body = TestDto("Renamed ${request.message}")) }
        delete<StatusDto>("/item") { PluginResult(body = StatusDto("deleted")) }
        sse("/events") {
            send(ServerSentEvent("ready"))
        }
    }
}

class DuplicateRoutePlugin : KeelPlugin, EndpointPlugin {
    override val descriptor = PluginDescriptor("dup-route", "1.0", "Duplicate")

    override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
        get<StatusDto>("/status") { PluginResult(body = StatusDto("a")) }
        get<StatusDto>("/status") { PluginResult(body = StatusDto("b")) }
    }
}

class FailingPlugin : KeelPlugin {
    override val descriptor = PluginDescriptor("failing", "1.0", "Failing Plugin")

    override suspend fun onStart(context: PluginRuntimeContext) {
        error("Plugin startup intentionally failed!")
    }
}

class TrackingDatabaseProvider(
    private val throwOnCleanup: Boolean = false
) : TestDatabaseProvider {
    var beforeStartCalled: Boolean = false
    var cleanupCalled: Boolean = false
    var afterDisposeCalled: Boolean = false
    private val delegate = H2InMemoryDatabaseProvider("tracking_db_${System.nanoTime()}")

    override fun beforeStart() {
        beforeStartCalled = true
    }

    override fun createModule(): Module = delegate.createModule()

    override fun cleanup() {
        cleanupCalled = true
        if (throwOnCleanup) {
            error("cleanup failed intentionally")
        }
    }

    override fun afterDispose() {
        afterDisposeCalled = true
    }
}

class TestKeelPluginDSLTest {

    @Test
    fun `single plugin starts`() {
        val plugin = SimplePlugin()
        testKeelPlugin {
            plugin(plugin)
            assertions {
                assertTrue(plugin.started)
                expectPluginState("simple", PluginLifecycleState.RUNNING)
            }
        }
    }

    @Test
    fun `multi plugin registration`() {
        val a = SimplePlugin("a")
        val b = SimplePlugin("b")
        testKeelPlugin {
            plugins(a, b)
            expectHealthy("a")
            expectHealthy("b")
        }
    }

    @Test
    fun `empty plugin list is valid`() {
        testKeelPlugin {
            assertions { assertTrue(true) }
        }
    }

    @Test
    fun `kernel module injection works`() {
        testKeelPlugin {
            plugin(SimplePlugin())
            kernelModule { single { KernelService() } }
            assertions {
                val svc = inject<KernelService>()
                assertEquals("kernel-ready", svc.getStatus())
            }
        }
    }

    @Test
    fun `plugin private scope injection works`() {
        val plugin = EndpointTestPlugin()
        testKeelPlugin {
            plugin(plugin)
            kernelModule { single { KernelService() } }
            assertions {
                assertTrue(plugin.started)
            }
        }
    }

    @Test
    fun `pluginModule injects extra private service`() {
        val plugin = EndpointTestPlugin(id = "private-extra", requireExtraPrivateService = true)
        testKeelPlugin {
            plugin(plugin)
            kernelModule { single { KernelService() } }
            pluginModule { single { ExtraPrivateService() } }
            expectHealthy("private-extra")
        }
    }

    @Test
    fun `http get works via top level http block`() {
        testKeelPlugin {
            plugin(EndpointTestPlugin())
            kernelModule { single { KernelService() } }
            http {
                get("/api/plugins/endpoint-test/status")
                    .expectStatus(HttpStatusCode.OK.value)
                    .expectBody<StatusDto> {
                        assertEquals("ok", it.status)
                    }
            }
        }
    }

    @Test
    fun `http post works`() {
        testKeelPlugin {
            plugin(EndpointTestPlugin())
            kernelModule { single { KernelService() } }
            http {
                postJson("/api/plugins/endpoint-test/greet", TestDto("Alice"))
                    .expectStatus(200)
                    .expectBody<TestDto> {
                        assertEquals("Hello Alice", it.message)
                    }
            }
        }
    }

    @Test
    fun `http put works`() {
        testKeelPlugin {
            plugin(EndpointTestPlugin())
            kernelModule { single { KernelService() } }
            http {
                putJson("/api/plugins/endpoint-test/rename", TestDto("Alice"))
                    .expectStatus(200)
                    .expectBody<TestDto> {
                        assertEquals("Renamed Alice", it.message)
                    }
            }
        }
    }

    @Test
    fun `http delete works`() {
        testKeelPlugin {
            plugin(EndpointTestPlugin())
            kernelModule { single { KernelService() } }
            http {
                delete("/api/plugins/endpoint-test/item")
                    .expectStatus(200)
                    .expectBody<StatusDto> {
                        assertEquals("deleted", it.status)
                    }
            }
        }
    }

    @Test
    fun `sse endpoint is reachable`() {
        testKeelPlugin {
            plugin(EndpointTestPlugin())
            kernelModule { single { KernelService() } }
            http {
                get("/api/plugins/endpoint-test/events")
                    .expectStatus(200)
                    .expectHeader("Content-Type", "text/event-stream")
            }
        }
    }

    @Test
    fun `http header assertion works`() {
        testKeelPlugin {
            plugin(EndpointTestPlugin())
            kernelModule { single { KernelService() } }
            http {
                get("/api/plugins/endpoint-test/status")
                    .expectStatus(200)
                    .expectHeader("Content-Type", "application/json; charset=UTF-8")
            }
        }
    }

    @Test
    fun `database provider hooks and setup are executed`() {
        val provider = TrackingDatabaseProvider()
        var setupExecuted = false
        testKeelPlugin {
            plugin(SimplePlugin())
            withDatabaseProvider(provider)
            databaseSetup {
                setupExecuted = true
                transaction { 1 + 1 }
            }
            assertions {
                val db = inject<KeelDatabase>(KeelDiQualifiers.keelDatabaseQualifier)
                assertNotNull(db)
            }
        }
        assertTrue(provider.beforeStartCalled)
        assertTrue(provider.cleanupCalled)
        assertTrue(provider.afterDisposeCalled)
        assertTrue(setupExecuted)
    }

    @Test
    fun `withInMemoryDatabase provides KeelDatabase`() {
        testKeelPlugin {
            plugin(SimplePlugin())
            withInMemoryDatabase()
            assertions {
                val db = inject<KeelDatabase>(KeelDiQualifiers.keelDatabaseQualifier)
                assertEquals(2, db.transaction { 1 + 1 })
            }
        }
    }

    @Test
    fun `plugin start failure bubbles up`() {
        val error = assertFailsWith<IllegalStateException> {
            testKeelPlugin {
                plugin(FailingPlugin())
            }
        }
        assertTrue(error.message?.contains("intentionally failed") == true)
    }

    @Test
    fun `route conflict is detected`() {
        val error = assertFails {
            testKeelPlugin {
                plugin(DuplicateRoutePlugin())
            }
        }
        assertTrue(error.message?.contains("Duplicate plugin endpoint registration") == true)
    }

    @Test
    fun `teardown failure does not swallow primary failure`() {
        val provider = TrackingDatabaseProvider(throwOnCleanup = true)
        val error = assertFailsWith<IllegalStateException> {
            testKeelPlugin {
                plugin(SimplePlugin())
                withDatabaseProvider(provider)
                assertions {
                    error("primary test failure")
                }
            }
        }
        assertTrue(provider.cleanupCalled)
        assertTrue(error.message?.contains("primary test failure") == true)
    }

    @Test
    fun `teardown failure fails test when no primary failure`() {
        val provider = TrackingDatabaseProvider(throwOnCleanup = true)
        val error = assertFails {
            testKeelPlugin {
                plugin(SimplePlugin())
                withDatabaseProvider(provider)
                assertions {
                    assertTrue(true)
                }
            }
        }
        assertTrue(provider.cleanupCalled)
        assertTrue(error.message?.contains("dispose encountered") == true)
    }

    @Test
    fun `missing required module fails fast`() {
        val error = assertFails {
            testKeelPlugin {
                plugin(EndpointTestPlugin("needs-kernel"))
            }
        }
        assertTrue(
            error.message?.contains("No definition found") == true ||
                error.message?.contains("NoBeanDefFoundException") == true
        )
    }

    @Test
    fun `diagnostic report contains route and http failure details`() {
        val originalErr = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer))
        try {
            assertFails {
                testKeelPlugin {
                    plugin(EndpointTestPlugin())
                    kernelModule { single { KernelService() } }
                    http {
                        get("/api/plugins/endpoint-test/status")
                            .expectStatus(201)
                    }
                }
            }
        } finally {
            System.setErr(originalErr)
        }
        val report = buffer.toString()
        assertTrue(report.contains("Mounted Routes:"))
        assertTrue(report.contains("HTTP Failure:"))
        assertTrue(report.contains("GET /status") || report.contains("GET /api/plugins/endpoint-test/status"))
    }

    @Test
    fun `expectHealthy works at top level`() {
        testKeelPlugin {
            plugin(SimplePlugin("healthy-check"))
            expectHealthy("healthy-check")
        }
    }

    @Test
    fun `expectPluginState works at top level`() {
        testKeelPlugin {
            plugin(SimplePlugin("state-check"))
            expectPluginState("state-check", PluginLifecycleState.RUNNING)
        }
    }

    @Test
    fun `contexts are isolated across sequential runs`() {
        repeat(5) { i ->
            val plugin = SimplePlugin("seq-$i")
            testKeelPlugin {
                plugin(plugin)
                kernelModule { single { KernelService() } }
                assertions {
                    assertEquals("kernel-ready", inject<KernelService>().getStatus())
                }
            }
            assertTrue(plugin.disposed)
        }
    }

    @Test
    fun `parallel isolation with 20 concurrent contexts repeated`() = runBlocking {
        repeat(3) { round ->
            coroutineScope {
                (1..20).map { idx ->
                    async {
                        val pluginId = "p-${round}-$idx"
                        testKeelPluginSuspend {
                            plugin(SimplePlugin(pluginId))
                            kernelModule { single { KernelService() } }
                            expectHealthy(pluginId)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    @Test
    fun `database names are isolated across contexts`() {
        testKeelPlugin {
            plugin(SimplePlugin("db1"))
            withInMemoryDatabase("db_1_${System.nanoTime()}")
            assertions {
                val db = inject<KeelDatabase>(KeelDiQualifiers.keelDatabaseQualifier)
                assertEquals(42, db.transaction { 42 })
            }
        }
        testKeelPlugin {
            plugin(SimplePlugin("db2"))
            withInMemoryDatabase("db_2_${System.nanoTime()}")
            assertions {
                val db = inject<KeelDatabase>(KeelDiQualifiers.keelDatabaseQualifier)
                assertEquals(99, db.transaction { 99 })
            }
        }
    }

    @Test
    fun `assertions block remains supported for complex checks`() {
        testKeelPlugin {
            plugin(SimplePlugin("assertions-block"))
            assertions {
                val id = pluginManager.getAllPlugins().keys.single()
                assertEquals("assertions-block", id)
            }
        }
    }
}
