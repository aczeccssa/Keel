package com.keel.test.perf

import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginKtorConfig
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import io.ktor.client.request.get
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlin.system.measureTimeMillis
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private object KtorPluginScopeThresholds {
    const val REQUEST_500_WITH_SCOPE_MAX_MS = 4000L
    const val SCOPE_OVERHEAD_MULTIPLIER_MAX = 4.0
}

class KtorPluginScopePerformanceTest {
    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun serviceScopePluginOverheadStaysWithinBudget() = testApplication {
        runCatching { stopKoin() }
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(PingPlugin("plain"))
        manager.registerPlugin(PingPlugin("scoped", scoped = true))

        application {
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            manager.installConfiguredPluginApplicationKtorPlugins(this)
            routing {
                manager.mountRoutes(this)
            }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("plain")
                manager.startPlugin("scoped")
            }
        }

        val warmup = 100
        repeat(warmup) {
            client.get("/api/plugins/plain/ping")
            client.get("/api/plugins/scoped/ping")
        }

        val iterations = 500
        val plainMs = measureTimeMillis {
            repeat(iterations) {
                client.get("/api/plugins/plain/ping")
            }
        }
        val scopedMs = measureTimeMillis {
            repeat(iterations) {
                client.get("/api/plugins/scoped/ping")
            }
        }

        assertTrue(
            scopedMs < KtorPluginScopeThresholds.REQUEST_500_WITH_SCOPE_MAX_MS,
            "[CI GATE] Scoped route handling $iterations requests took ${scopedMs}ms, " +
                "max=${KtorPluginScopeThresholds.REQUEST_500_WITH_SCOPE_MAX_MS}ms"
        )
        val overheadMultiplier = scopedMs.toDouble() / plainMs.coerceAtLeast(1)
        assertTrue(
            overheadMultiplier < KtorPluginScopeThresholds.SCOPE_OVERHEAD_MULTIPLIER_MAX,
            "[CI GATE] Scoped route overhead ${"%.2f".format(overheadMultiplier)}x exceeds max " +
                "${KtorPluginScopeThresholds.SCOPE_OVERHEAD_MULTIPLIER_MAX}x"
        )
        println(
            "[PERF] scope overhead: plain=${plainMs}ms scoped=${scopedMs}ms " +
                "multiplier=${"%.2f".format(overheadMultiplier)}x"
        )
    }

    private class PingPlugin(
        pluginId: String,
        private val scoped: Boolean = false
    ) : StandardKeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId
        )

        override suspend fun onStop(context: PluginRuntimeContext) = Unit

        override fun ktorPlugins(): PluginKtorConfig = PluginKtorConfig().apply {
            if (scoped) {
                service {
                    install(scopedPerfMarkerPlugin)
                }
            }
        }

        override fun endpoints() = PluginEndpointBuilders.pluginEndpoints(descriptor.pluginId) {
            get<String>("/ping") {
                PluginResult(body = "pong")
            }
        }
    }

    companion object {
        private val scopedPerfMarkerPlugin = createRouteScopedPlugin("scoped-perf-marker") {
            onCall { call ->
                call.response.headers.append("X-Scoped-Perf", "1")
            }
        }
    }
}
