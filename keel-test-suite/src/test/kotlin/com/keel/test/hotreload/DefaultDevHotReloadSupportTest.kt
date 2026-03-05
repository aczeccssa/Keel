package com.keel.test.hotreload

import com.keel.kernel.config.ModuleChangeEvent
import com.keel.kernel.config.ModuleChangeType
import com.keel.kernel.hotreload.ChangeScope
import com.keel.kernel.hotreload.DefaultModuleChangeClassifier
import com.keel.kernel.hotreload.DefaultPluginImpactAnalyzer
import com.keel.kernel.hotreload.PluginDevelopmentSource
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultDevHotReloadSupportTest {

    @Test
    fun classifierMarksKernelAndBuildLogicAsRestartRequired() {
        val classifier = DefaultModuleChangeClassifier()

        val buildEvent = ModuleChangeEvent(
            type = ModuleChangeType.MODIFIED,
            fileName = "build.gradle.kts",
            fullPath = "/tmp/sample/build.gradle.kts",
            moduleRoot = "/tmp/sample",
            relativePath = "build.gradle.kts"
        )
        val kernelEvent = ModuleChangeEvent(
            type = ModuleChangeType.MODIFIED,
            fileName = "Kernel.kt",
            fullPath = "/tmp/keel-core/src/main/kotlin/Kernel.kt",
            moduleRoot = "/tmp/keel-core",
            relativePath = "src/main/kotlin/Kernel.kt"
        )
        val pluginEvent = ModuleChangeEvent(
            type = ModuleChangeType.MODIFIED,
            fileName = "Hello.kt",
            fullPath = "/tmp/keel-samples/src/main/kotlin/Hello.kt",
            moduleRoot = "/tmp/keel-samples",
            relativePath = "src/main/kotlin/Hello.kt"
        )

        assertEquals(ChangeScope.BUILD_LOGIC, classifier.classify(buildEvent).scope)
        assertEquals(ChangeScope.KERNEL_SOURCE, classifier.classify(kernelEvent).scope)
        assertEquals(ChangeScope.PLUGIN_SOURCE, classifier.classify(pluginEvent).scope)
    }

    @Test
    fun impactAnalyzerResolvesRecursiveDependencies() {
        val repo = Files.createTempDirectory("keel-hotreload-repo-")
        try {
            Files.writeString(repo.resolve("settings.gradle.kts"), "rootProject.name = \"keel\"")
            val appDir = createModule(repo, "app", "dependencies { implementation(project(\":shared\")) }")
            createModule(repo, "shared", "dependencies { }")
            createModule(repo, "nested/core", "dependencies { }")

            val analyzer = DefaultPluginImpactAnalyzer(repo.toFile())
            analyzer.registerOwnership(
                PluginDevelopmentSource(
                    pluginId = "plug-a",
                    owningModulePath = appDir.toString(),
                    implementationClassName = "com.example.PluginA"
                )
            )

            val owningPath = appDir.toFile().absoluteFile.normalize().path
            val affected = analyzer.affectedPlugins(owningPath)
            val ownership = analyzer.ownershipOf("plug-a")
            assertTrue(ownership != null)
            assertTrue(ownership.dependentModulePaths.any { it.contains("/app") })
            assertTrue("plug-a" in affected)
        } finally {
            repo.toFile().deleteRecursively()
        }
    }

    private fun createModule(repo: java.nio.file.Path, relative: String, buildScript: String): java.nio.file.Path {
        val dir = repo.resolve(relative).createDirectories()
        Files.writeString(dir.resolve("build.gradle.kts"), buildScript)
        return dir
    }
}
