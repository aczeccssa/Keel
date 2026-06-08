plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("application")
}

// Custom source set for tools
sourceSets {
    create("tools") {
        kotlin.srcDir("src/tools/kotlin")
        compileClasspath += sourceSets["main"].compileClasspath
        runtimeClasspath += sourceSets["main"].runtimeClasspath
    }
}

// Task to export H2 database data to CSV via TCP
tasks.register<JavaExec>("exportH2Data") {
    description = "Export airelay config tables to CSV (requires H2 TCP server on port 9092)"
    group = "keel-sample"
    
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("ExportH2DataKt")
    
    findProperty("dbFolder")?.let { systemProperty("dbFolder", it.toString()) }
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
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.koin.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.h2.database)

    ksp(project(":keel-openapi-processor"))
}
