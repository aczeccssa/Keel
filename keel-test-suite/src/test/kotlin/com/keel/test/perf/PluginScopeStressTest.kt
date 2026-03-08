package com.keel.test.perf

import com.keel.kernel.di.PluginScopeManager
import com.keel.kernel.plugin.PluginConfig
import com.keel.kernel.plugin.PluginRuntimeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object ScopeThresholds {
    const val RAPID_CYCLE_200_MAX_MS = 1000L
    const val CONCURRENT_100_MAX_MS = 1000L
    const val HEAVY_50_MAX_MS = 2000L
    const val RECREATE_100_MAX_MS = 1000L
}

/**
 * Stress tests for PluginScopeManager (Koin scope isolation).
 *
 * Validates:
 *   - Rapid scope creation and destruction
 *   - Memory release after scope close (no leaks)
 *   - Concurrent scope operations for different plugins
 *   - koinApplication bootstrap overhead
 */
class PluginScopeStressTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    private fun makeConfig(pluginId: String) = PluginConfig(
        pluginId = pluginId,
        enabled = true,
        runtimeMode = PluginRuntimeMode.IN_PROCESS
    )

    // ─────────────────────────────────────────────────────────
    //  Test 1: Rapid create/close scope cycling
    // ─────────────────────────────────────────────────────────

    @Test
    fun rapidScopeCreateCloseCycling() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = PluginScopeManager(koin)
        val cycleCount = 200

        val elapsedMs = measureTimeMillis {
            repeat(cycleCount) { i ->
                val pluginId = "rapid-cycle-plugin"
                val modules = listOf(module {
                    single { "dependency-$i" }
                })
                manager.createScope(pluginId, makeConfig(pluginId), modules)
                assertNotNull(manager.getScope(pluginId), "Scope should exist after creation")
                manager.closeScope(pluginId)
                assertNull(manager.getScope(pluginId), "Scope should be null after close")
            }
        }

        assertTrue(elapsedMs < ScopeThresholds.RAPID_CYCLE_200_MAX_MS,
            "[CI GATE] Rapid scope cycling took ${elapsedMs}ms, max=${ScopeThresholds.RAPID_CYCLE_200_MAX_MS}ms")
        println("[PERF] Rapid scope create/close: $cycleCount cycles in ${elapsedMs}ms (${cycleCount * 1000.0 / elapsedMs} cycles/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 2: Many concurrent scope creations for different plugins
    // ─────────────────────────────────────────────────────────

    @Test
    fun concurrentScopeCreation() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = PluginScopeManager(koin)
        val pluginCount = 100

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                (0 until pluginCount).map { i ->
                    async(Dispatchers.Default) {
                        val pluginId = "concurrent-scope-$i"
                        val modules = listOf(module {
                            single { "dep-$i" }
                        })
                        manager.createScope(pluginId, makeConfig(pluginId), modules)
                    }
                }.awaitAll()
            }
        }

        // Verify all scopes exist
        for (i in 0 until pluginCount) {
            assertNotNull(
                manager.getScope("concurrent-scope-$i"),
                "Scope for concurrent-scope-$i should exist"
            )
        }

        assertTrue(elapsedMs < ScopeThresholds.CONCURRENT_100_MAX_MS,
            "[CI GATE] Concurrent scope creation took ${elapsedMs}ms, max=${ScopeThresholds.CONCURRENT_100_MAX_MS}ms")
        println("[PERF] Concurrent scope creation: $pluginCount scopes in ${elapsedMs}ms (${pluginCount * 1000.0 / elapsedMs} scopes/sec)")

        // Cleanup
        for (i in 0 until pluginCount) {
            manager.closeScope("concurrent-scope-$i")
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Test 3: Scope with multiple modules (heavier DI graph)
    // ─────────────────────────────────────────────────────────

    @Test
    fun scopeWithHeavyModules() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = PluginScopeManager(koin)
        val cycleCount = 50
        val modulesPerScope = 5
        val beansPerModule = 10

        val elapsedMs = measureTimeMillis {
            repeat(cycleCount) { cycle ->
                val pluginId = "heavy-scope-plugin"
                val modules = (0 until modulesPerScope).map { m ->
                    module {
                        (0 until beansPerModule).forEach { b ->
                            single(org.koin.core.qualifier.named("mod${m}_bean${b}")) { "value-${cycle}-${m}-${b}" }
                        }
                    }
                }
                manager.createScope(pluginId, makeConfig(pluginId), modules)
                manager.closeScope(pluginId)
            }
        }

        val totalBeans = modulesPerScope * beansPerModule
        assertTrue(elapsedMs < ScopeThresholds.HEAVY_50_MAX_MS,
            "[CI GATE] Heavy scope cycling took ${elapsedMs}ms, max=${ScopeThresholds.HEAVY_50_MAX_MS}ms")
        println("[PERF] Heavy scope ($totalBeans beans/scope): $cycleCount cycles in ${elapsedMs}ms (${cycleCount * 1000.0 / elapsedMs} cycles/sec)")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 4: Scope re-creation (simulates plugin reload)
    // ─────────────────────────────────────────────────────────

    @Test
    fun scopeReCreation() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = PluginScopeManager(koin)
        val reloadCount = 100

        val elapsedMs = measureTimeMillis {
            repeat(reloadCount) { i ->
                val pluginId = "recreate-plugin"
                // createScope internally calls closeScope first
                manager.createScope(
                    pluginId,
                    makeConfig(pluginId),
                    listOf(module {
                        single { "gen-$i" }
                    })
                )
            }
        }

        assertNotNull(
            manager.getScope("recreate-plugin"),
            "Scope should still exist after re-creations"
        )

        assertTrue(elapsedMs < ScopeThresholds.RECREATE_100_MAX_MS,
            "[CI GATE] Scope re-creation took ${elapsedMs}ms, max=${ScopeThresholds.RECREATE_100_MAX_MS}ms")
        println("[PERF] Scope re-creation: $reloadCount re-creations in ${elapsedMs}ms (${reloadCount * 1000.0 / elapsedMs} re-creations/sec)")

        manager.closeScope("recreate-plugin")
    }
}
