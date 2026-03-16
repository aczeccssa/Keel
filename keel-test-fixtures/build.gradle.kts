plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("java-library")
}

dependencies {
    // Public API surface — only what test authors directly need
    api(project(":keel-core"))
    api(project(":keel-contract"))
    api(libs.ktor.server.core)
    api(libs.koin.core)
    api(libs.ktor.server.test.host)
    api(libs.kotlin.test)
    api(libs.kotlin.test.junit5)
    api(libs.junit.jupiter)

    // Internal implementation — not exposed to consumers
    implementation(project(":keel-exposed-starter"))
    implementation(project(":keel-openapi-runtime"))
    implementation(libs.h2.database)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.mockk)
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
    useJUnitPlatform {
        excludeTags("stress")
    }
    failOnNoDiscoveredTests = false
}

val stressTest by tasks.registering(Test::class) {
    description = "Runs high-load parallel isolation tests for keel-test-fixtures."
    group = "verification"
    useJUnitPlatform {
        includeTags("stress")
    }
    systemProperty("keel.test.parallel.rounds", "60")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
}
