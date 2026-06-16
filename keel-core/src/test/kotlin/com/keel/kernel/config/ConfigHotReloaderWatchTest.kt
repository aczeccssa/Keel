package com.keel.kernel.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConfigHotReloaderWatchTest {

    @Test
    fun ignoresGeneratedLogFilesInsideModuleCacheDirectories() = runBlocking {
        val root = Paths.get(System.getProperty("user.dir"))
        val moduleDir = Files.createTempDirectory(root, "keel-module-")
        val sourceDir = Files.createDirectories(moduleDir.resolve("src/main/kotlin"))
        val logDir = Files.createDirectories(moduleDir.resolve("cache/log"))
        val sourceEvent = CompletableDeferred<ModuleChangeEvent>()
        val generatedLogEvent = CompletableDeferred<ModuleChangeEvent>()

        val reloader = ConfigHotReloader.Builder()
            .watchModuleDir(moduleDir.toString())
            .onModuleChange { event ->
                when (event.relativePath) {
                    "src/main/kotlin/Sample.kt" -> if (!sourceEvent.isCompleted) sourceEvent.complete(event)
                    "cache/log/application.log" -> if (!generatedLogEvent.isCompleted) generatedLogEvent.complete(event)
                }
            }
            .build()

        try {
            reloader.startWatching()
            delay(300)

            Files.writeString(logDir.resolve("application.log"), "runtime log noise")
            delay(500)
            assertFalse(
                generatedLogEvent.isCompleted,
                "Generated runtime logs should not emit module change events"
            )

            Files.writeString(sourceDir.resolve("Sample.kt"), "class Sample")

            val event = withTimeout(5_000) { sourceEvent.await() }
            assertEquals("src/main/kotlin/Sample.kt", event.relativePath)
        } finally {
            reloader.stopWatching()
            deleteIfExists(logDir.resolve("application.log"))
            deleteIfExists(sourceDir.resolve("Sample.kt"))
            deleteIfExists(logDir)
            deleteIfExists(sourceDir)
            deleteIfExists(sourceDir.parent)
            deleteIfExists(sourceDir.parent.parent)
            deleteIfExists(moduleDir.resolve("cache"))
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
