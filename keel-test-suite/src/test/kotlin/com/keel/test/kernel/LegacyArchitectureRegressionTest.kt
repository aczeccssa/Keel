package com.keel.test.kernel

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertFalse

class LegacyArchitectureRegressionTest {

    private val projectRoot = resolveProjectRoot()

    @Test
    fun legacyPluginArchitectureProductionFilesDoNotExist() {
        val forbiddenFiles = listOf(
            "keel-core/src/main/kotlin/com/keel/kernel/plugin/KPlugin.kt",
            "keel-core/src/main/kotlin/com/keel/kernel/plugin/PluginManager.kt",
            "keel-core/src/main/kotlin/com/keel/kernel/plugin/KeelPluginV2.kt",
            "keel-core/src/main/kotlin/com/keel/kernel/plugin/HybridPluginManager.kt",
            "keel-core/src/main/kotlin/com/keel/kernel/routing/HybridSystemRoutes.kt",
            "keel-core/src/main/kotlin/com/keel/kernel/routing/SystemRoutes.kt",
            "keel-core/src/main/resources/META-INF/services/com.keel.kernel.plugin.KPlugin"
        )

        for (relativePath in forbiddenFiles) {
            assertFalse(projectRoot.resolve(relativePath).exists(), "Legacy production file still exists: $relativePath")
        }
    }

    @Test
    fun legacyPluginArchitectureSymbolsDoNotAppearInProductionSources() {
        val forbiddenPatterns = listOf(
            Regex("""\bKPlugin\b"""),
            Regex("""\bKeelPluginV2\b"""),
            Regex("""\bLegacyCompatibleKeelPlugin\b"""),
            Regex("""\bPluginExecutionMode\b"""),
            Regex("""\bhybridSystemRoutes\b"""),
            Regex("""\bPluginManager\b""")
        )

        Files.walk(projectRoot.resolve("keel-core/src/main")).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.extension in setOf("kt", "html") || it.fileName.toString().startsWith("com.keel.kernel.plugin.") }
                .forEach { path ->
                    val content = Files.readString(path)
                    for (pattern in forbiddenPatterns) {
                        assertFalse(
                            pattern.containsMatchIn(content),
                            "Forbidden legacy symbol ${pattern.pattern} found in ${path.invariantSeparatorsPathString}"
                        )
                    }
                }
        }
    }

    private fun resolveProjectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.exists(current.resolve("keel-core/src/main"))) {
            current = current.parent
        }
        check(Files.exists(current.resolve("keel-core/src/main"))) {
            "Could not resolve project root from ${Path.of("").toAbsolutePath().normalize()}"
        }
        return current
    }
}
