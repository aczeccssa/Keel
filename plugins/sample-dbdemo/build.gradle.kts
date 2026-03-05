plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":keel-core"))
    implementation(project(":keel-contract"))
    implementation(project(":keel-exposed-starter"))
    implementation(project(":keel-openapi-annotations"))
    implementation(project(":keel-openapi-runtime"))
    implementation(libs.ktor.server.core)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.h2.database)

    ksp(project(":keel-openapi-processor"))
}

kotlin {
    jvmToolchain(23)
}
