package com.keel.test.perf

import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginDispatchDisposition
import com.keel.kernel.plugin.PluginLifecycleState
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.routing.GatewayInterceptor
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

private object GatewayThresholds {
    const val DISPATCH_500K_AVG_NS = 500L
    const val REGEX_1M_AVG_NS = 500L
    const val COMBINED_500K_AVG_NS = 1000L
    const val SHORT_CIRCUIT_1M_AVG_NS = 500L
}

/**
 * Performance benchmark for GatewayInterceptor.
 *
 * Measures:
 *   - Regex-based `extractPluginId()` throughput
 *   - `resolveDispatchDisposition()` ConcurrentHashMap lookup speed
 *   - Combined path parsing + disposition resolution overhead
 */
class GatewayInterceptorBenchmark {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Test 1: resolveDispatchDisposition raw throughput
    // ─────────────────────────────────────────────────────────

    @Test
    fun dispatchDispositionRawThroughput() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)

        // Register plugins to create realistic ConcurrentHashMap state
        val pluginIds = (0 until 20).map { "bench-plugin-$it" }
        pluginIds.forEach { id ->
            manager.registerPlugin(NoopPlugin(id))
            manager.startPlugin(id)
        }

        val iterations = 500_000
        val elapsedNs = measureNanoTime {
            repeat(iterations) { i ->
                manager.resolveDispatchDisposition(pluginIds[i % pluginIds.size])
            }
        }

        val avgNs = elapsedNs / iterations
        assertTrue(avgNs < GatewayThresholds.DISPATCH_500K_AVG_NS,
            "[CI GATE] resolveDispatchDisposition avg ${avgNs}ns/op exceeds max ${GatewayThresholds.DISPATCH_500K_AVG_NS}ns")
        println("[PERF] resolveDispatchDisposition: $iterations iterations in ${elapsedNs / 1_000_000}ms, avg ${avgNs}ns/op")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 2: Regex extractPluginId throughput
    //         (tests the compiled regex performance)
    // ─────────────────────────────────────────────────────────

    @Test
    fun regexPluginIdExtractionThroughput() {
        val regex = """^/api/plugins/([^/]+)""".toRegex()
        val paths = listOf(
            "/api/plugins/helloworld",
            "/api/plugins/dbdemo/notes",
            "/api/plugins/observability/topology",
            "/api/plugins/auth/login",
            "/api/plugins/very-long-plugin-id-name/deeply/nested/path",
            "/api/_system/health",       // non-plugin path
            "/api/plugins/a",            // short plugin ID
        )

        val iterations = 1_000_000
        val elapsedNs = measureNanoTime {
            repeat(iterations) { i ->
                val path = paths[i % paths.size]
                regex.find(path)?.groupValues?.get(1)
            }
        }

        val avgNs = elapsedNs / iterations
        assertTrue(avgNs < GatewayThresholds.REGEX_1M_AVG_NS,
            "[CI GATE] Regex extraction avg ${avgNs}ns/op exceeds max ${GatewayThresholds.REGEX_1M_AVG_NS}ns")
        println("[PERF] Regex extractPluginId: $iterations iterations in ${elapsedNs / 1_000_000}ms, avg ${avgNs}ns/op")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 3: Combined path parsing + dispatch resolution
    //         (simulates the full GatewayInterceptor.intercept path)
    // ─────────────────────────────────────────────────────────

    @Test
    fun combinedPathAndDispatchThroughput() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val regex = """^/api/plugins/([^/]+)""".toRegex()

        val pluginIds = (0 until 10).map { "combined-$it" }
        pluginIds.forEach { id ->
            manager.registerPlugin(NoopPlugin(id))
            manager.startPlugin(id)
        }

        val testPaths = pluginIds.map { "/api/plugins/$it/some/endpoint" }

        val iterations = 500_000
        val elapsedNs = measureNanoTime {
            repeat(iterations) { i ->
                val path = testPaths[i % testPaths.size]
                val pluginId = regex.find(path)?.groupValues?.get(1)
                if (pluginId != null) {
                    manager.resolveDispatchDisposition(pluginId)
                }
            }
        }

        val avgNs = elapsedNs / iterations
        assertTrue(avgNs < GatewayThresholds.COMBINED_500K_AVG_NS,
            "[CI GATE] Combined path+dispatch avg ${avgNs}ns/op exceeds max ${GatewayThresholds.COMBINED_500K_AVG_NS}ns")
        println("[PERF] Combined path+dispatch: $iterations iterations in ${elapsedNs / 1_000_000}ms, avg ${avgNs}ns/op")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 4: Non-plugin path short-circuit
    //         (ensure system paths have minimal overhead)
    // ─────────────────────────────────────────────────────────

    @Test
    fun nonPluginPathShortCircuit() {
        val regex = """^/api/plugins/([^/]+)""".toRegex()
        val systemPaths = listOf(
            "/api/_system/health",
            "/api/_system/plugins",
            "/api/_system/docs/openapi.json",
            "/",
            "/static/index.html",
        )

        val iterations = 1_000_000
        var nullCount = 0
        val elapsedNs = measureNanoTime {
            repeat(iterations) { i ->
                val result = regex.find(systemPaths[i % systemPaths.size])?.groupValues?.get(1)
                if (result == null) nullCount++
            }
        }

        assertEquals(iterations, nullCount, "All system paths should return null for pluginId")
        val avgNs = elapsedNs / iterations
        assertTrue(avgNs < GatewayThresholds.SHORT_CIRCUIT_1M_AVG_NS,
            "[CI GATE] Non-plugin short-circuit avg ${avgNs}ns/op exceeds max ${GatewayThresholds.SHORT_CIRCUIT_1M_AVG_NS}ns")
        println("[PERF] Non-plugin path regex: $iterations iterations in ${elapsedNs / 1_000_000}ms, avg ${avgNs}ns/op")
    }

    // ─── Helper ──────────────────────────────────────────────

    private class NoopPlugin(pluginId: String) : KeelPlugin {
        override val descriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId
        )
        override fun endpoints(): List<PluginRouteDefinition> = emptyList()
    }
}
