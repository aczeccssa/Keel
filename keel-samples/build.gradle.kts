plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("application")
}

kotlin {
    jvmToolchain(23)
}

application {
    mainClass.set("com.keel.samples.KeelSampleKt")
}

dependencies {
    implementation(project(":keel-core"))
    implementation(project(":keel-contract"))
    implementation(project(":keel-exposed-starter"))
    implementation(project(":keel-openapi-annotations"))
    implementation(project(":keel-openapi-runtime"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.sse)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.koin.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.h2.database)

    ksp(project(":keel-openapi-processor"))

    testImplementation(project(":keel-test-fixtures"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
