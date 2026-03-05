package com.keel.test.config

import com.keel.kernel.config.buildKernel
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginRouteDefinition
import java.nio.file.Files
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KernelBuilderPluginRegistrationTest {
    @AfterTest
    fun teardown() {
        stopKoin()
    }

    @Test
    fun pluginFlagsControlEnabledAndHotReloadSourceRegistration() {
        val kernel = buildKernel {
            plugin(HotReloadOnPlugin(), enabled = true, hotReloadEnabled = true)
            plugin(HotReloadOffPlugin(), enabled = false, hotReloadEnabled = false)
            enablePluginHotReload(false)
        }

        val sourcePluginIds = kernel.pluginDevelopmentSourceIds()
        assertTrue("hotreload-on" in sourcePluginIds)
        assertFalse("hotreload-off" in sourcePluginIds)

        val pluginManager = kernel.pluginManager()
        val runtimeConfig = pluginManager.runtimeConfigObject("hotreload-off")
        assertEquals(false, runtimeConfig.enabled())
    }

    @Test
    fun watchDirectoriesCallOverridesDefaultWatchScope() {
        val dir1 = Files.createTempDirectory("keel-watch-1").toFile().absolutePath
        val dir2 = Files.createTempDirectory("keel-watch-2").toFile().absolutePath

        val kernel = buildKernel {
            watchDirectories(dir1, dir2)
            enablePluginHotReload(false)
        }

        val field = kernel.javaClass.getDeclaredField("moduleWatchDirectories")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val moduleWatchDirectories = field.get(kernel) as List<String>

        assertEquals(listOf(dir1, dir2), moduleWatchDirectories)
    }

    private fun Any.pluginDevelopmentSourceIds(): Set<String> {
        val field = javaClass.getDeclaredField("pluginDevelopmentSources")
        field.isAccessible = true
        val value = field.get(this) as List<*>
        return value.mapNotNull { source ->
            source?.javaClass?.getDeclaredMethod("getPluginId")?.invoke(source) as? String
        }.toSet()
    }

    private fun Any.runtimeConfigObject(pluginId: String): Any {
        val method = javaClass.getDeclaredMethod("getRuntimeConfig", String::class.java)
        return method.invoke(this, pluginId) ?: error("Runtime config not found for $pluginId")
    }

    private fun Any.enabled(): Boolean {
        val method = javaClass.getDeclaredMethod("getEnabled")
        return method.invoke(this) as Boolean
    }

    private fun Any.pluginManager(): Any {
        val field = javaClass.getDeclaredField("pluginManager")
        field.isAccessible = true
        return field.get(this)
    }

    class HotReloadOnPlugin : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "hotreload-on",
            version = "1.0.0",
            displayName = "HotReload On"
        )

        override fun endpoints(): List<PluginRouteDefinition> = emptyList()
    }

    class HotReloadOffPlugin : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "hotreload-off",
            version = "1.0.0",
            displayName = "HotReload Off"
        )

        override fun endpoints(): List<PluginRouteDefinition> = emptyList()
    }
}
