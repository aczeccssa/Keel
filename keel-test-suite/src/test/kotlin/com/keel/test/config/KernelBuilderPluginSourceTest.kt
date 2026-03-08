package com.keel.test.config

import com.keel.kernel.config.buildKeel
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginRouteDefinition
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class KernelBuilderPluginSourceTest {
    @AfterTest
    fun teardown() {
        stopKoin()
    }

    @Test
    fun pluginRegistrationInfersSourceMetadata() {
        val kernel = buildKeel {
            plugin(SourceOnlyPlugin(), hotReloadEnabled = true)
            enablePluginHotReload(true)
        }

        assertNotNull(kernel.pluginDevelopmentSourceIds().singleOrNull { it == "source-only-plugin" })

        val pluginManagerField = kernel.javaClass.getDeclaredField("pluginManager")
        pluginManagerField.isAccessible = true
        val pluginManager = pluginManagerField.get(kernel)
        val snapshotMethod = pluginManager.javaClass.getDeclaredMethod("getRuntimeSnapshot", String::class.java)
        val snapshot = snapshotMethod.invoke(pluginManager, "source-only-plugin")
        assertNotNull(snapshot)
    }

    class SourceOnlyPlugin : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "source-only-plugin",
            version = "1.0.0",
            displayName = "Source Only Plugin"
        )

        override fun endpoints(): List<PluginRouteDefinition> = emptyList()
    }
}
