package com.keel.test.kernel

import com.keel.kernel.config.PluginLifecycleHotReloadAdapter
import com.keel.kernel.hotreload.DevHotReloadEngine
import com.keel.kernel.hotreload.DevHotReloadStatus
import com.keel.kernel.hotreload.DevReloadOutcome
import com.keel.kernel.hotreload.DevReloadEvent
import com.keel.kernel.hotreload.PluginDevelopmentSource
import com.keel.kernel.hotreload.ReloadAttemptResult
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLifecycleHotReloadAdapterTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun configChangeTriggersReloadThroughManagerLifecyclePath() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val adapter = PluginLifecycleHotReloadAdapter(manager)
        val plugin = LifecycleProbePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.startPlugin("plugin-a")
        val firstGeneration = manager.getGeneration("plugin-a")

        adapter.handleConfigChange("plugin-a")

        assertEquals(firstGeneration.next(), manager.getGeneration("plugin-a"))
        assertEquals(2, plugin.startCount)
    }

    @Test
    fun configChangeFallsBackToLegacyManagerReloadWhenPluginSourceNotRegistered() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val fakeEngine = FakeHotReloadEngine()
        val adapter = PluginLifecycleHotReloadAdapter(manager, fakeEngine)
        val plugin = LifecycleProbePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.startPlugin("plugin-a")
        val firstGeneration = manager.getGeneration("plugin-a")

        adapter.handleConfigChange("plugin-a")

        assertEquals(firstGeneration.next(), manager.getGeneration("plugin-a"))
        assertFalse(fakeEngine.called)
    }

    @Test
    fun configChangeUsesHotReloadEngineWhenPluginSourceRegistered() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val fakeEngine = FakeHotReloadEngine()
        val adapter = PluginLifecycleHotReloadAdapter(manager, fakeEngine)
        val plugin = LifecycleProbePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.registerPluginSource(
            PluginDevelopmentSource(
                pluginId = "plugin-a",
                owningModulePath = ":keel-samples",
                implementationClassName = "com.example.PluginA"
            )
        )
        manager.startPlugin("plugin-a")
        val firstGeneration = manager.getGeneration("plugin-a")

        adapter.handleConfigChange("plugin-a")

        assertEquals(firstGeneration, manager.getGeneration("plugin-a"))
        assertTrue(fakeEngine.called)
    }

    private class FakeHotReloadEngine : DevHotReloadEngine {
        var called: Boolean = false
        override val events: SharedFlow<DevReloadEvent> = MutableSharedFlow()
        override fun registerSource(source: PluginDevelopmentSource) = Unit
        override fun status(): DevHotReloadStatus = DevHotReloadStatus()
        override suspend fun handleModuleChange(event: com.keel.kernel.config.ModuleChangeEvent) = Unit
        override suspend fun reloadPlugin(pluginId: String, reason: String): ReloadAttemptResult {
            called = true
            return ReloadAttemptResult(pluginId = pluginId, outcome = DevReloadOutcome.RELOADED, message = "ok")
        }
    }

    private class LifecycleProbePlugin(
        pluginId: String
    ) : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId
        )

        var startCount: Int = 0

        override suspend fun onStart(context: PluginRuntimeContext) {
            startCount += 1
        }

        override suspend fun onStop(context: PluginRuntimeContext) = Unit

        override fun endpoints(): List<PluginRouteDefinition> = PluginEndpointBuilders.pluginEndpoints(descriptor.pluginId) {
            get<String> {
                PluginResult(body = "ok")
            }
        }
    }
}
