package com.keel.test.loader

import com.keel.kernel.config.KeelConstants
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointDefinition
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
            assertTrue(discovered.first().artifactChecksum.isNotBlank())
            assertTrue(discovered.first().artifactLastModifiedMs > 0)

            val plugin = loader.loadPlugin(discovered.first())
            assertEquals("test-plugin", plugin.descriptor.pluginId)

            loader.unloadPlugin("test-plugin")
            val reloaded = loader.reloadPlugin("test-plugin")
            assertNotNull(reloaded)
            assertEquals("test-plugin", reloaded.descriptor.pluginId)
        }
    }

    @Test
    fun artifactFingerprintChangesWhenJarChanges() {
        val pluginsDir = Files.createTempDirectory("keel-plugins-").toFile()
        val jarFile = pluginsDir.resolve("test-plugin.jar")
        writeManifestJar(jarFile, "1.0.0")

        val loader = DefaultPluginLoader()
        runBlocking {
            val first = loader.discoverPlugins(pluginsDir.absolutePath).single()
            loader.loadPlugin(first)
            Thread.sleep(10)
            writeManifestJar(jarFile, "1.0.1")
            assertTrue(loader.hasArtifactChanged("test-plugin"))
            val second = loader.discoverPlugins(pluginsDir.absolutePath).single()

            assertNotEquals(first.artifactChecksum, second.artifactChecksum)
        }
    }

    @Test
    fun discoverIgnoresLegacyPluginArtifacts() {
        val pluginsDir = Files.createTempDirectory("keel-plugins-").toFile()
        val jarFile = pluginsDir.resolve("legacy-plugin.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(KeelConstants.MANIFEST_PLUGIN_ID, "legacy-plugin")
            mainAttributes.putValue(KeelConstants.MANIFEST_PLUGIN_VERSION, "1.0.0")
            mainAttributes.putValue(
                KeelConstants.MANIFEST_PLUGIN_MAIN_CLASS,
                LegacyTestPlugin::class.java.name
            )
        }
        JarOutputStream(FileOutputStream(jarFile), manifest).use { }

        val loader = DefaultPluginLoader()
        runBlocking {
            val discovered = loader.discoverPlugins(pluginsDir.absolutePath)
            assertTrue(discovered.isEmpty())
        }
    }

    private fun writeManifestJar(jarFile: java.io.File, version: String) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(KeelConstants.MANIFEST_PLUGIN_ID, "test-plugin")
            mainAttributes.putValue(KeelConstants.MANIFEST_PLUGIN_VERSION, version)
            mainAttributes.putValue(
                KeelConstants.MANIFEST_PLUGIN_MAIN_CLASS,
                TestPlugin::class.java.name
            )
        }
        JarOutputStream(FileOutputStream(jarFile), manifest).use { }
    }
}

class TestPlugin : KeelPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "test-plugin",
        version = "1.0.0",
        displayName = "test-plugin"
    )

    override fun endpoints(): List<PluginEndpointDefinition<*, *>> = emptyList()
}

class LegacyTestPlugin
