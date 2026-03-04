package com.keel.test.config

import com.keel.kernel.config.ConfigChangeEvent
import com.keel.kernel.config.ConfigHotReloader
import com.keel.kernel.config.ModuleChangeEvent
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

    @Test
    fun emitsConfigEventsForFilesCreatedInsideNewSubdirectories() = runBlocking {
        val root = Paths.get(System.getProperty("user.dir"))
        val configDir = Files.createTempDirectory(root, "keel-config-nested-")
        val configEvent = CompletableDeferred<ConfigChangeEvent>()

        val reloader = ConfigHotReloader.Builder()
            .watchConfigDir(configDir.toString())
            .onConfigChange { event ->
                if (!configEvent.isCompleted && event.fileName == "plugin-a.json") {
                    configEvent.complete(event)
                }
            }
            .build()

        try {
            reloader.startWatching()
            delay(300)

            val nestedDir = Files.createDirectories(configDir.resolve("plugins"))
            Files.writeString(nestedDir.resolve("plugin-a.json"), """{"pluginId":"plugin-a"}""")

            withTimeout(5_000) {
                configEvent.await()
            }
        } finally {
            reloader.stopWatching()
            deleteIfExists(configDir.resolve("plugins/plugin-a.json"))
            deleteIfExists(configDir.resolve("plugins"))
            deleteIfExists(configDir)
        }
    }

    @Test
    fun createsMissingConfigRootAndWatchesIt() = runBlocking {
        val root = Paths.get(System.getProperty("user.dir"))
        val baseDir = Files.createTempDirectory(root, "keel-config-missing-")
        val configDir = baseDir.resolve("config/plugins")
        val configEvent = CompletableDeferred<ConfigChangeEvent>()

        val reloader = ConfigHotReloader.Builder()
            .watchConfigDir(configDir.toString())
            .onConfigChange { event ->
                if (!configEvent.isCompleted && event.fileName == "plugin-b.json") {
                    configEvent.complete(event)
                }
            }
            .build()

        try {
            reloader.startWatching()
            delay(300)

            Files.writeString(configDir.resolve("plugin-b.json"), """{"pluginId":"plugin-b"}""")

            withTimeout(5_000) {
                configEvent.await()
            }
        } finally {
            reloader.stopWatching()
            deleteIfExists(configDir.resolve("plugin-b.json"))
            deleteIfExists(configDir)
            deleteIfExists(configDir.parent)
            deleteIfExists(baseDir)
        }
    }

    @Test
    fun emitsModuleEventsFromWatchedModuleDirs() = runBlocking {
        val root = Paths.get(System.getProperty("user.dir"))
        val moduleDir = Files.createTempDirectory(root, "keel-module-")
        val sourceDir = Files.createDirectories(moduleDir.resolve("src/main/kotlin"))
        val moduleEvent = CompletableDeferred<ModuleChangeEvent>()

        val reloader = ConfigHotReloader.Builder()
            .watchModuleDir(moduleDir.toString())
            .onModuleChange { event ->
                if (!moduleEvent.isCompleted && event.relativePath == "src/main/kotlin/Sample.kt") {
                    moduleEvent.complete(event)
                }
            }
            .build()

        try {
            reloader.startWatching()
            delay(300)

            Files.writeString(sourceDir.resolve("Sample.kt"), "class Sample")

            val event = withTimeout(5_000) { moduleEvent.await() }
            kotlin.test.assertEquals("src/main/kotlin/Sample.kt", event.relativePath)
        } finally {
            reloader.stopWatching()
            deleteIfExists(sourceDir.resolve("Sample.kt"))
            deleteIfExists(sourceDir)
            deleteIfExists(sourceDir.parent)
            deleteIfExists(sourceDir.parent.parent)
            deleteIfExists(moduleDir)
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
