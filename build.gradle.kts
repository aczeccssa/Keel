import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.dokka.gradle.DokkaExtension

plugins {
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

val repoUrl = "https://github.com/aczeccssa/Keel"
val excludedFromDocs = setOf("keel-samples")
val excludedFromPublishing = setOf("keel-samples", "keel-test-suite")

val docsProjects = subprojects.filter { it.name !in excludedFromDocs }

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven("https://jitpack.io")
}

dependencies {
    docsProjects.forEach { dokka(it) }
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

subprojects {
    if (name in excludedFromDocs) return@subprojects

    apply(plugin = "org.jetbrains.dokka")

    extensions.configure<DokkaExtension>("dokka") {
        dokkaSourceSets.configureEach {
            val repoPath = project.projectDir.relativeTo(rootProject.projectDir).invariantSeparatorsPath
            val kotlinDir = project.projectDir.resolve("src/main/kotlin")
            val javaDir = project.projectDir.resolve("src/main/java")

            if (kotlinDir.exists()) {
                sourceLink {
                    localDirectory.set(kotlinDir)
                    remoteUrl.set(uri("$repoUrl/blob/main/$repoPath/src/main/kotlin"))
                    remoteLineSuffix.set("#L")
                }
            }
            if (javaDir.exists()) {
                sourceLink {
                    localDirectory.set(javaDir)
                    remoteUrl.set(uri("$repoUrl/blob/main/$repoPath/src/main/java"))
                    remoteLineSuffix.set("#L")
                }
            }
        }
    }
}

subprojects {
    if (name in excludedFromPublishing) return@subprojects

    apply(plugin = "maven-publish")

    pluginManager.withPlugin("java") {
        the<JavaPluginExtension>().withSourcesJar()

        val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
            val dokkaJavadocTask = tasks.named("dokkaGeneratePublicationHtml")
            dependsOn(dokkaJavadocTask)
            archiveClassifier.set("javadoc")
            from(dokkaJavadocTask)
        }

        extensions.configure<PublishingExtension>("publishing") {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    artifact(dokkaJavadocJar)
                    pom {
                        name.set(project.name)
                        description.set("Keel: A Ktor-based modular monolith kernel for pluginized backend systems.")
                        url.set(repoUrl)
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("$repoUrl/blob/main/LICENSE")
                            }
                        }
                        developers {
                            developer {
                                id.set("aczeccssa")
                                name.set("aczeccssa")
                            }
                        }
                        scm {
                            url.set(repoUrl)
                            connection.set("scm:git:$repoUrl.git")
                            developerConnection.set("scm:git:$repoUrl.git")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/aczeccssa/Keel")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: System.getenv("GPR_USER")
                        password = System.getenv("GITHUB_TOKEN") ?: System.getenv("GPR_TOKEN")
                    }
                }
            }
        }
    }
}
