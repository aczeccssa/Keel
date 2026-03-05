plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.ksp) apply false
}

subprojects {
    group = "com.keel"

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_23)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(23)
    }

    tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version.toString()
            )
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnit()
    }
}

subprojects {
    if (!path.startsWith(":plugins:")) return@subprojects

    configurations.configureEach {
        withDependencies {
            forEach { dependency ->
                val projectDependency = dependency as? org.gradle.api.artifacts.ProjectDependency ?: return@forEach
                val dependencyPath = projectDependency.path
                require(!dependencyPath.startsWith(":plugins:")) {
                    "Plugin project $path must not depend on another plugin project: $dependencyPath"
                }
            }
        }
    }
}
