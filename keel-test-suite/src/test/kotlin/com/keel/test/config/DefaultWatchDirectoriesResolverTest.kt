package com.keel.test.config

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DefaultWatchDirectoriesResolverTest {

    @Test
    fun resolvesCallerModuleAndRecursiveProjectDependencies() {
        val repoRoot = Files.createTempDirectory("keel-watch-root-")
        try {
            val appModule = createModule(repoRoot, "app", """dependencies { implementation(project(":shared")) }""")
            createModule(repoRoot, "shared", """dependencies { implementation(project(":nested:core")) }""")
            createModule(repoRoot, "nested/core", """dependencies { }""")

            val resolved = resolve(repoRoot.toFile(), appModule.toFile())

            assertNotNull(resolved)
            assertEquals(
                listOf(":app", ":shared", ":nested:core"),
                moduleProjectPaths(resolved)
            )
        } finally {
            repoRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun deduplicatesTransitiveProjectDependencies() {
        val repoRoot = Files.createTempDirectory("keel-watch-root-")
        try {
            val appModule = createModule(
                repoRoot,
                "app",
                """
                dependencies {
                    implementation(project(":shared-a"))
                    implementation(project(":shared-b"))
                }
                """.trimIndent()
            )
            createModule(repoRoot, "shared-a", """dependencies { implementation(project(":nested:core")) }""")
            createModule(repoRoot, "shared-b", """dependencies { implementation(project(":nested:core")) }""")
            createModule(repoRoot, "nested/core", """dependencies { }""")

            val resolved = resolve(repoRoot.toFile(), appModule.toFile())

            assertNotNull(resolved)
            assertEquals(
                listOf(":app", ":shared-a", ":nested:core", ":shared-b"),
                moduleProjectPaths(resolved)
            )
        } finally {
            repoRoot.toFile().deleteRecursively()
        }
    }

    private fun createModule(repoRoot: Path, relativePath: String, buildScript: String): Path {
        val moduleDir = repoRoot.resolve(relativePath).createDirectories()
        Files.writeString(moduleDir.resolve("build.gradle.kts"), buildScript)
        return moduleDir
    }

    private fun resolve(repoRoot: File, callerModuleDir: File): Any? {
        val resolverClass = Class.forName("com.keel.kernel.config.DefaultWatchDirectoriesResolver")
        val instance = resolverClass.getField("INSTANCE").get(null)
        val method = resolverClass.getDeclaredMethod("resolve\$keel_core", File::class.java, File::class.java)
        return method.invoke(instance, repoRoot, callerModuleDir)
    }

    private fun moduleProjectPaths(resolved: Any): List<String> {
        val modules = resolved.javaClass.getDeclaredMethod("getModules").invoke(resolved) as List<*>
        return modules.map { module ->
            module!!.javaClass.getDeclaredMethod("getProjectPath").invoke(module) as String
        }
    }
}
