package com.keel.test.perf

import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginLifecycleState
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.UnifiedPluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enterprise CI/CD Benchmark Thresholds.
 * Tests will FAIL the build if performance regresses beyond these limits.
 * Thresholds are set at ~10× the observed baseline to allow for CI runner variance.
 */
private object PluginManagerThresholds {
    const val REGISTER_200_MAX_MS = 500L
    const val CONCURRENT_START_100_MAX_MS = 500L
    const val LIFECYCLE_CYCLE_50_MAX_MS = 500L
    const val CONTENTION_50_MAX_MS = 2000L
    const val RELOAD_30_MAX_MS = 500L
    const val DISPATCH_LOOKUP_100K_MAX_MS = 1000L
}

/**
 * Stress tests for UnifiedPluginManager.
 *
 * Validates that the lifecycle state machine (register → start → stop → dispose → reload)
 * behaves correctly under high-concurrency coroutine pressure, measuring:
 *   - Mutex lock contention across many plugins
 *   - ConcurrentHashMap lookup under load
 *   - State consistency after rapid lifecycle transitions
 */
class PluginManagerStressTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Test 1: Register many plugins concurrently
    // ─────────────────────────────────────────────────────────

    @Test
    fun registerManyPluginsConcurrently() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val pluginCount = 200

        val elapsedMs = measureTimeMillis {
            for (i in 0 until pluginCount) {
                manager.registerPlugin(NoopPlugin("stress-plugin-$i"))
            }
        }

        val allPlugins = manager.getAllPlugins()
        assertEquals(pluginCount, allPlugins.size, "Expected $pluginCount plugins registered")
        assertTrue(elapsedMs < PluginManagerThresholds.REGISTER_200_MAX_MS,
            "[CI GATE] Register $pluginCount plugins took ${elapsedMs}ms, max=${PluginManagerThresholds.REGISTER_200_MAX_MS}ms")
        println("[PERF] Registered $pluginCount plugins in ${elapsedMs}ms (${pluginCount * 1000.0 / elapsedMs} ops/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 2: Concurrent start of many plugins (per-plugin locking)
    // ─────────────────────────────────────────────────────────

    @Test
    fun concurrentStartManyPlugins() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val pluginCount = 100

        for (i in 0 until pluginCount) {
            manager.registerPlugin(NoopPlugin("concurrent-$i"))
        }

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                (0 until pluginCount).map { i ->
                    async(Dispatchers.Default) {
                        manager.startPlugin("concurrent-$i")
                    }
                }.awaitAll()
            }
        }

        for (i in 0 until pluginCount) {
            assertEquals(
                PluginLifecycleState.RUNNING,
                manager.getLifecycleState("concurrent-$i"),
                "Plugin concurrent-$i should be RUNNING"
            )
        }

        assertTrue(elapsedMs < PluginManagerThresholds.CONCURRENT_START_100_MAX_MS,
            "[CI GATE] Concurrent start $pluginCount plugins took ${elapsedMs}ms, max=${PluginManagerThresholds.CONCURRENT_START_100_MAX_MS}ms")
        println("[PERF] Concurrently started $pluginCount plugins in ${elapsedMs}ms (${pluginCount * 1000.0 / elapsedMs} ops/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 3: Rapid lifecycle cycling (start → stop → start → stop)
    // ─────────────────────────────────────────────────────────

    @Test
    fun rapidLifecycleCycling() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val cycleCount = 50

        manager.registerPlugin(NoopPlugin("cycling-plugin"))

        val elapsedMs = measureTimeMillis {
            repeat(cycleCount) {
                manager.startPlugin("cycling-plugin")
                manager.stopPlugin("cycling-plugin")
            }
        }

        // Final state should be STOPPED
        assertEquals(
            PluginLifecycleState.STOPPED,
            manager.getLifecycleState("cycling-plugin"),
            "Plugin should be STOPPED after cycling"
        )

        assertTrue(elapsedMs < PluginManagerThresholds.LIFECYCLE_CYCLE_50_MAX_MS,
            "[CI GATE] $cycleCount lifecycle cycles took ${elapsedMs}ms, max=${PluginManagerThresholds.LIFECYCLE_CYCLE_50_MAX_MS}ms")
        println("[PERF] Completed $cycleCount start/stop cycles in ${elapsedMs}ms (${cycleCount * 1000.0 / elapsedMs} cycles/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 4: Concurrent lifecycle operations on the SAME plugin
    //         (contention stress on the per-plugin Mutex)
    // ─────────────────────────────────────────────────────────

    @Test
    fun concurrentLifecycleOnSamePlugin() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val plugin = CountingPlugin("contention-plugin")
        val concurrentOps = 50

        manager.registerPlugin(plugin)

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                (0 until concurrentOps).map {
                    async(Dispatchers.Default) {
                        manager.startPlugin("contention-plugin")
                        manager.stopPlugin("contention-plugin")
                    }
                }.awaitAll()
            }
        }

        // State machine should be in a consistent final state
        val finalState = manager.getLifecycleState("contention-plugin")
        assertTrue(
            finalState == PluginLifecycleState.STOPPED || finalState == PluginLifecycleState.RUNNING,
            "Plugin should be in a valid state, got: $finalState"
        )

        assertTrue(elapsedMs < PluginManagerThresholds.CONTENTION_50_MAX_MS,
            "[CI GATE] $concurrentOps concurrent ops took ${elapsedMs}ms, max=${PluginManagerThresholds.CONTENTION_50_MAX_MS}ms")
        println("[PERF] $concurrentOps concurrent lifecycle ops on same plugin in ${elapsedMs}ms")
        println("[PERF] Plugin init count: ${plugin.initCount.get()}, start count: ${plugin.startCount.get()}, stop count: ${plugin.stopCount.get()}")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 5: Reload cycling stress
    // ─────────────────────────────────────────────────────────

    @Test
    fun reloadCyclingStress() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val reloadCount = 30

        manager.registerPlugin(NoopPlugin("reload-plugin"))
        manager.startPlugin("reload-plugin")

        val elapsedMs = measureTimeMillis {
            repeat(reloadCount) {
                manager.reloadPlugin("reload-plugin")
            }
        }

        assertEquals(
            PluginLifecycleState.RUNNING,
            manager.getLifecycleState("reload-plugin"),
            "Plugin should be RUNNING after reloads"
        )

        val gen = manager.getGeneration("reload-plugin")
        assertEquals(
            reloadCount + 1L,
            gen.value,
            "Generation should be ${reloadCount + 1} after $reloadCount reloads"
        )

        assertTrue(elapsedMs < PluginManagerThresholds.RELOAD_30_MAX_MS,
            "[CI GATE] $reloadCount reloads took ${elapsedMs}ms, max=${PluginManagerThresholds.RELOAD_30_MAX_MS}ms")
        println("[PERF] Completed $reloadCount reloads in ${elapsedMs}ms (${reloadCount * 1000.0 / elapsedMs} reloads/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 6: Dispatch disposition lookup throughput
    // ─────────────────────────────────────────────────────────

    @Test
    fun dispatchDispositionLookupThroughput() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val pluginCount = 50
        val lookupIterations = 100_000

        for (i in 0 until pluginCount) {
            manager.registerPlugin(NoopPlugin("lookup-$i"))
            manager.startPlugin("lookup-$i")
        }

        val elapsedMs = measureTimeMillis {
            repeat(lookupIterations) { i ->
                manager.resolveDispatchDisposition("lookup-${i % pluginCount}")
            }
        }

        assertTrue(elapsedMs < PluginManagerThresholds.DISPATCH_LOOKUP_100K_MAX_MS,
            "[CI GATE] $lookupIterations dispatch lookups took ${elapsedMs}ms, max=${PluginManagerThresholds.DISPATCH_LOOKUP_100K_MAX_MS}ms")
        println("[PERF] $lookupIterations dispatch lookups across $pluginCount plugins in ${elapsedMs}ms (${lookupIterations * 1000.0 / elapsedMs} ops/sec)")
    }

    // ─── Helper Plugins ──────────────────────────────────────

    private class NoopPlugin(pluginId: String) : KeelPlugin {
        override val descriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId
        )

        override fun endpoints(): List<PluginRouteDefinition> = emptyList()
    }

    private class CountingPlugin(pluginId: String) : KeelPlugin {
        override val descriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId
        )

        val initCount = AtomicInteger(0)
        val startCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)

        override suspend fun onInit(context: PluginInitContext) {
            initCount.incrementAndGet()
        }

        override suspend fun onStart(context: com.keel.kernel.plugin.PluginRuntimeContext) {
            startCount.incrementAndGet()
        }

        override suspend fun onStop(context: com.keel.kernel.plugin.PluginRuntimeContext) {
            stopCount.incrementAndGet()
        }

        override fun endpoints(): List<PluginRouteDefinition> = emptyList()
    }
}
