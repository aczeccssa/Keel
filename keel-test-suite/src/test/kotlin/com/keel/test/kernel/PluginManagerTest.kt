package com.keel.test.kernel

import com.keel.kernel.plugin.KPlugin
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginManager
import com.keel.kernel.plugin.PluginState
import io.ktor.server.routing.routing
import io.ktor.server.routing.Routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginManagerTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun pluginLifecycleTransitions() = testApplication {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = PluginManager(koin)

        val plugin = RecordingPlugin()
        manager.registerPlugin(plugin)
        assertEquals(PluginState.INIT, manager.getPluginState(plugin.pluginId))

        runTest {
            manager.initPlugin(plugin.pluginId)
            assertTrue(plugin.inited)
            assertEquals(PluginState.INSTALLED, manager.getPluginState(plugin.pluginId))

            val scope = koin.getOrCreateScope(plugin.pluginId, named(plugin.pluginId))
            manager.installPlugin(plugin.pluginId, scope)
            assertTrue(plugin.installed)

            val routing = this@testApplication.application.routing { }
            manager.enablePlugin(plugin.pluginId, routing)
            assertTrue(plugin.enabled)
            assertEquals(PluginState.ENABLED, manager.getPluginState(plugin.pluginId))

            manager.disablePlugin(plugin.pluginId)
            assertTrue(plugin.disabled)
            assertEquals(PluginState.DISABLED, manager.getPluginState(plugin.pluginId))
        }

        stopKoin()
        koinStarted = false
    }

    private class RecordingPlugin : KPlugin {
        override val pluginId: String = "test-plugin"
        override val version: String = "1.0.0"

        var inited = false
        var installed = false
        var enabled = false
        var disabled = false

        override suspend fun onInit(context: PluginInitContext) {
            inited = true
        }

        override suspend fun onInstall(scope: Scope) {
            installed = true
        }

        override suspend fun onEnable(routing: Routing) {
            enabled = true
        }

        override suspend fun onDisable() {
            disabled = true
        }
    }
}
