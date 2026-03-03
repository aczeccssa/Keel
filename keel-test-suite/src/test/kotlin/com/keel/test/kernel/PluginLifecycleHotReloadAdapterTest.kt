package com.keel.test.kernel

import com.keel.kernel.config.ConfigChangeEvent
import com.keel.kernel.config.PluginChangeEvent
import com.keel.kernel.config.PluginChangeType
import com.keel.kernel.config.PluginLifecycleHotReloadAdapter
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginDispatchDisposition
import com.keel.kernel.plugin.PluginEndpointDefinition
import com.keel.kernel.plugin.PluginGeneration
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.plugin.pluginEndpoints
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

        adapter.handleConfigChange(ConfigChangeEvent(type = "MODIFIED", fileName = "plugin-a.json"))

        assertEquals(firstGeneration.next(), manager.getGeneration("plugin-a"))
        assertEquals(2, plugin.startCount)
    }

    @Test
    fun pluginArtifactEventsUseReplaceAndDisposeLifecyclePaths() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val adapter = PluginLifecycleHotReloadAdapter(manager)
        val plugin = LifecycleProbePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.startPlugin("plugin-a")
        val firstGeneration = manager.getGeneration("plugin-a")

        adapter.handlePluginChange(
            PluginChangeEvent(
                type = PluginChangeType.MODIFIED,
                pluginId = "plugin-a",
                filePath = "/tmp/plugin-a.jar"
            )
        )

        assertEquals(firstGeneration.next(), manager.getGeneration("plugin-a"))
        assertEquals(2, plugin.startCount)

        adapter.handlePluginChange(
            PluginChangeEvent(
                type = PluginChangeType.DELETED,
                pluginId = "plugin-a",
                filePath = "/tmp/plugin-a.jar"
            )
        )

        assertEquals(PluginDispatchDisposition.NOT_FOUND, manager.resolveDispatchDisposition("plugin-a"))
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

        override fun endpoints(): List<PluginEndpointDefinition<*, *>> = pluginEndpoints(descriptor.pluginId) {
            get<String> {
                PluginResult(body = "ok")
            }
        }
    }
}
