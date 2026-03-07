package com.keel.test.config

import com.keel.kernel.config.ConfigHotReloader
import com.keel.kernel.config.ModuleChangeEvent
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

    @Test
    fun skipsWatchingNonExistentDirectories() = runBlocking {
        val root = Paths.get(System.getProperty("user.dir"))
        val nonExistentDir = root.resolve("keel-nonexistent-${System.nanoTime()}")

        // Should not throw and should not create the directory
        val reloader = ConfigHotReloader.Builder()
            .watchModuleDir(nonExistentDir.toString())
            .build()

        try {
            reloader.startWatching()
            delay(200)
            kotlin.test.assertFalse(Files.exists(nonExistentDir),
                "Non-existent watch directory should NOT be auto-created")
        } finally {
            reloader.stopWatching()
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
