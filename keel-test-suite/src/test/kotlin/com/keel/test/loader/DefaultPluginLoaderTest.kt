package com.keel.test.loader

import com.keel.kernel.config.KeelConstants
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.plugin.KPlugin
import com.keel.kernel.plugin.PluginInitContext
import io.ktor.server.routing.Routing
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.koin.core.scope.Scope

class DefaultPluginLoaderTest {

    @Test
    fun loadUnloadReloadPluginFromManifestJar() {
        val pluginsDir = Files.createTempDirectory("keel-plugins-").toFile()
        val jarFile = pluginsDir.resolve("test-plugin.jar")

        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(KeelConstants.MANIFEST_PLUGIN_ID, "test-plugin")
            mainAttributes.putValue(KeelConstants.MANIFEST_PLUGIN_VERSION, "1.0.0")
            mainAttributes.putValue(
                KeelConstants.MANIFEST_PLUGIN_MAIN_CLASS,
                TestPlugin::class.java.name
            )
        }

        JarOutputStream(FileOutputStream(jarFile), manifest).use { }

        val loader = DefaultPluginLoader()
        runBlocking {
            val discovered = loader.discoverPlugins(pluginsDir.absolutePath)
            assertEquals(1, discovered.size)

            val plugin = loader.loadPlugin(discovered.first())
            assertEquals("test-plugin", plugin.pluginId)

            loader.unloadPlugin("test-plugin")
            val reloaded = loader.reloadPlugin("test-plugin")
            assertNotNull(reloaded)
        }
    }
}

class TestPlugin : KPlugin {
    override val pluginId: String = "test-plugin"
    override val version: String = "1.0.0"

    override suspend fun onInit(context: PluginInitContext) {}

    override suspend fun onInstall(scope: Scope) {}

    override suspend fun onEnable(routing: Routing) {}

    override suspend fun onDisable() {}
}
