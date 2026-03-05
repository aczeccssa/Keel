package com.keel.test.config

import com.keel.kernel.config.ConfigChangeEvent
import com.keel.kernel.config.ConfigHotReloader
import com.keel.kernel.config.PluginChangeEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ConfigHotReloaderWatchTest {

    @Test
    fun emitsEventsFromConfigAndPluginDirs() = runBlocking {
        val root = Paths.get(System.getProperty("user.dir"))
        val configDir = Files.createTempDirectory(root, "keel-config-")
        val pluginDir = Files.createTempDirectory(root, "keel-plugins-")

        val configEvent = CompletableDeferred<ConfigChangeEvent>()
        val pluginEvent = CompletableDeferred<PluginChangeEvent>()

        val reloader = ConfigHotReloader.Builder()
            .watchConfigDir(configDir.toString())
            .watchPluginDir(pluginDir.toString())
            .onConfigChange { event ->
                if (!configEvent.isCompleted) configEvent.complete(event)
            }
            .onPluginChange { event ->
                if (!pluginEvent.isCompleted) pluginEvent.complete(event)
            }
            .build()

        try {
            reloader.startWatching()
            delay(300)

            Files.writeString(configDir.resolve("app.yaml"), "x: 1")
            Files.write(pluginDir.resolve("plug-a.jar"), byteArrayOf(1))

            withTimeout(5_000) {
                configEvent.await()
                pluginEvent.await()
            }
        } finally {
            reloader.stopWatching()
            deleteIfExists(configDir.resolve("app.yaml"))
            deleteIfExists(pluginDir.resolve("plug-a.jar"))
            deleteIfExists(configDir)
            deleteIfExists(pluginDir)
        }
    }

    @Test
    fun acceptsRelativeConfigDirPaths() = runBlocking {
        val root = Paths.get(System.getProperty("user.dir"))
        val configDir = Files.createTempDirectory(root, "keel-config-rel-")
        val relativeConfigDir = root.relativize(configDir).toString()

        val configEvent = CompletableDeferred<ConfigChangeEvent>()

        val reloader = ConfigHotReloader.Builder()
            .watchConfigDir(relativeConfigDir)
            .onConfigChange { event ->
                if (!configEvent.isCompleted) configEvent.complete(event)
            }
            .build()

        try {
            reloader.startWatching()
            delay(300)

            Files.writeString(configDir.resolve("app.yaml"), "x: 2")

            withTimeout(5_000) {
                configEvent.await()
            }
        } finally {
            reloader.stopWatching()
            deleteIfExists(configDir.resolve("app.yaml"))
            deleteIfExists(configDir)
        }
    }

    private fun deleteIfExists(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
            // Best-effort cleanup for temp files.
        }
    }
}
