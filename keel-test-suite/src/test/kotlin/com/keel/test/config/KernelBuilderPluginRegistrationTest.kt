package com.keel.test.config

import com.keel.kernel.config.buildKeel
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
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
        val kernel = buildKeel {
            plugin(HotReloadOnPlugin(), enabled = true, hotReloadEnabled = true)
            plugin(HotReloadOffPlugin(), enabled = false, hotReloadEnabled = false)
            enablePluginHotReload(false)
        }

        val sourcePluginIds = kernel.pluginDevelopmentSourceIds()
        assertTrue("hotreload-on" in sourcePluginIds)
        assertFalse("hotreload-off" in sourcePluginIds)

        val pluginManager = kernel.pluginManager()
        val runtimeConfig = pluginManager.getRuntimeConfig("hotreload-off")
        assertEquals(false, runtimeConfig?.enabled)
    }

    @Test
    fun watchDirectoriesCallOverridesDefaultWatchScope() {
        val dir1 = Files.createTempDirectory("keel-watch-1").toFile().absolutePath
        val dir2 = Files.createTempDirectory("keel-watch-2").toFile().absolutePath

        val kernel = buildKeel {
            watchDirectories(dir1, dir2)
            enablePluginHotReload(false)
        }

        val field = kernel.javaClass.getDeclaredField("moduleWatchDirectories")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val moduleWatchDirectories = field.get(kernel) as List<String>

        assertEquals(listOf(dir1, dir2), moduleWatchDirectories)
    }

    class HotReloadOnPlugin : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "hotreload-on",
            version = "1.0.0",
            displayName = "HotReload On"
        )
    }

    class HotReloadOffPlugin : KeelPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = "hotreload-off",
            version = "1.0.0",
            displayName = "HotReload Off"
        )
    }
}
