package com.keel.samples

import com.keel.contract.di.KeelDiQualifiers
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.EndpointPlugin
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.test.fixtures.testKeelPlugin
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.Serializable
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

@Serializable
data class DemoMessage(val message: String)

@Serializable
data class DemoStatus(val status: String)

private class DemoSimplePlugin(
    private val id: String
) : KeelPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(id, "1.0.0", "Demo Simple $id")
}

private class DemoEndpointPlugin(
    private val id: String
) : KeelPlugin, EndpointPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(id, "1.0.0", "Demo Endpoint $id")

    override suspend fun onStart(context: PluginRuntimeContext) {
        context.kernelKoin.get<DemoKernelService>()
    }

    override fun modules() = listOf(
        module {
            single { DemoPluginService() }
        }
    )

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        get<DemoStatus>("/status") { PluginResult(body = DemoStatus("ok")) }
        post<DemoMessage, DemoMessage>("/echo") { request -> PluginResult(body = DemoMessage("echo:${request.message}")) }
        put<DemoMessage, DemoMessage>("/rename") { request -> PluginResult(body = DemoMessage("renamed:${request.message}")) }
        delete<DemoStatus>("/item") { PluginResult(body = DemoStatus("deleted")) }
        sse("/events") {
            send(ServerSentEvent("ready"))
        }
    }
}

private class DemoKernelService
private class DemoPluginService

class KeelSampleFixturesDemoTest {

    @Test
    fun fixtures_smoke() {
        testKeelPlugin {
            plugin(DemoEndpointPlugin("smoke"))
            kernelModule { single { DemoKernelService() } }
            expectHealthy("smoke")
            http {
                get("/api/plugins/smoke/status")
                    .expectStatus(200)
                    .expectHeader("Content-Type", "application/json; charset=UTF-8")
                    .expectBody<DemoStatus> {
                        assertEquals("ok", it.status)
                    }
            }
        }
    }

    @Test
    fun fixtures_http_crud() {
        testKeelPlugin {
            plugin(DemoEndpointPlugin("crud"))
            kernelModule { single { DemoKernelService() } }
            http {
                postJson("/api/plugins/crud/echo", DemoMessage("alice"))
                    .expectStatus(200)
                    .expectBody<DemoMessage> { assertEquals("echo:alice", it.message) }

                putJson("/api/plugins/crud/rename", DemoMessage("alice"))
                    .expectStatus(200)
                    .expectBody<DemoMessage> { assertEquals("renamed:alice", it.message) }

                delete("/api/plugins/crud/item")
                    .expectStatus(200)
                    .expectBody<DemoStatus> { assertEquals("deleted", it.status) }
            }
        }
    }

    @Test
    fun fixtures_multi_plugin() {
        testKeelPlugin {
            plugins(DemoSimplePlugin("alpha"), DemoSimplePlugin("beta"))
            expectHealthy("alpha")
            expectHealthy("beta")
        }
    }

    @Test
    fun fixtures_database() {
        var setupExecuted = false
        testKeelPlugin {
            plugin(DemoSimplePlugin("db"))
            withInMemoryDatabase()
            databaseSetup {
                setupExecuted = true
                transaction { 1 + 1 }
            }
            assertions {
                val db = inject<KeelDatabase>(KeelDiQualifiers.keelDatabaseQualifier)
                assertEquals(7, db.transaction { 7 })
            }
        }
        assertTrue(setupExecuted)
    }

    @Test
    fun fixtures_sse() {
        testKeelPlugin {
            plugin(DemoEndpointPlugin("sse"))
            kernelModule { single { DemoKernelService() } }
            http {
                get("/api/plugins/sse/events")
                    .expectStatus(200)
                    .expectHeader("Content-Type", "text/event-stream")
            }
        }
    }

    @Test
    fun fixtures_diagnostic() {
        val originalErr = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer))
        try {
            assertFails {
                testKeelPlugin {
                    plugin(DemoEndpointPlugin("diag"))
                    kernelModule { single { DemoKernelService() } }
                    http {
                        get("/api/plugins/diag/status")
                            .expectStatus(201)
                    }
                }
            }
        } finally {
            System.setErr(originalErr)
        }

        val report = buffer.toString()
        assertTrue(report.contains("Stage:"))
        assertTrue(report.contains("Mounted Routes:"))
        assertTrue(report.contains("HTTP Failure:"))
    }
}
