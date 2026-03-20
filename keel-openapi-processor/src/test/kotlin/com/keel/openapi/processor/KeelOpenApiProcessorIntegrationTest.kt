package com.keel.openapi.processor

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

class KeelOpenApiProcessorIntegrationTest {
    private val repoRoot: File = findRepoRoot()

    @Test
    fun `build fails with migration guidance when legacy KeelApi is used`() {
        val fixtureDir = createFixtureProject(useLegacyKeelApi = true)

        val result = GradleRunner.create()
            .withProjectDir(fixtureDir)
            .withArguments("compileKotlin", "--stacktrace")
            .buildAndFail()

        val hasProcessorMessage = result.output.contains(
            "@KeelApi is no longer supported. Migrate endpoint docs to doc = OpenApiDoc(...) on DSL calls."
        )
        val hasDeprecationGuidance = result.output.contains(
            "Use DSL doc = OpenApiDoc(...) instead of @KeelApi."
        )
        assertTrue(
            hasProcessorMessage || hasDeprecationGuidance,
            "Expected migration guidance in output, but got:\n${result.output}"
        )
    }

    @Test
    fun `build passes when only OpenApiDoc-based annotations are used`() {
        val fixtureDir = createFixtureProject(useLegacyKeelApi = false)

        val result = GradleRunner.create()
            .withProjectDir(fixtureDir)
            .withArguments("compileKotlin", "--stacktrace")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected successful build, but got:\n${result.output}")
        assertTrue(
            fixtureDir.resolve("build/generated/ksp/main/kotlin/com/keel/generated/FixturePluginInterceptorMetadata.kt").isFile,
            "Expected generated interceptor metadata source to exist"
        )
        assertTrue(
            fixtureDir.resolve("build/generated/ksp/main/resources/META-INF/services/com.keel.kernel.plugin.KeelGeneratedInterceptorMetadataProvider").isFile,
            "Expected generated interceptor metadata service file to exist"
        )
    }

    private fun createFixtureProject(useLegacyKeelApi: Boolean): File {
        val projectDir = createTempDirectory("keel-openapi-ksp-fixture").toFile()
        val includedBuildPath = repoRoot.invariantSeparatorsPath()

        projectDir.writeFile(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                    google()
                }
            }

            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                    google()
                }
            }

            rootProject.name = "ksp-fixture"
            includeBuild("$includedBuildPath")
            """
        )

        projectDir.writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.0"
                id("com.google.devtools.ksp") version "2.3.0"
            }

            repositories {
                mavenCentral()
                google()
            }

            dependencies {
                implementation("com.keel:keel-core")
                implementation("com.keel:keel-openapi-annotations")
                implementation("com.keel:keel-openapi-runtime")
                ksp("com.keel:keel-openapi-processor")
            }
            """
        )

        val source = if (useLegacyKeelApi) {
            """
            package fixture

            import com.keel.kernel.api.KeelApi
            fun legacyMarker() {
                @KeelApi(summary = "legacy")
                val marker = Unit
                check(marker == Unit)
            }
            """
        } else {
            """
            package fixture

            import com.keel.kernel.plugin.KeelInterceptorResult
            import com.keel.kernel.plugin.KeelRequestContext
            import com.keel.kernel.plugin.KeelRequestInterceptor
            import com.keel.openapi.annotations.KeelApiPlugin
            import com.keel.openapi.annotations.KeelInterceptors
            import com.keel.openapi.annotations.KeelRouteInterceptors

            @KeelApiPlugin(pluginId = "fixture", title = "Fixture")
            @KeelInterceptors(FixtureInterceptor::class)
            @KeelRouteInterceptors(method = "GET", path = "/admin", value = [FixtureInterceptor::class])
            class FixturePlugin

            class FixtureInterceptor : KeelRequestInterceptor {
                override suspend fun intercept(
                    context: KeelRequestContext,
                    next: suspend () -> KeelInterceptorResult
                ): KeelInterceptorResult = next()
            }
            """
        }
        projectDir.writeFile("src/main/kotlin/fixture/Fixture.kt", source)
        return projectDir
    }

    private fun findRepoRoot(): File {
        val start = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(start) { it.parentFile }
            .firstOrNull { candidate ->
                candidate.resolve("settings.gradle.kts").isFile &&
                    candidate.resolve("keel-openapi-processor").isDirectory
            }
            ?: error("Unable to locate repository root from ${start.absolutePath}")
    }

    private fun File.writeFile(path: String, content: String) {
        val output = resolve(path)
        output.parentFile.mkdirs()
        output.writeText(content.trimIndent())
    }

    private fun File.invariantSeparatorsPath(): String = absolutePath.replace('\\', '/')
}
