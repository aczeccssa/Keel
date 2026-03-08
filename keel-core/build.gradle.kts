plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

tasks.processResources {
    inputs.property("frameworkVersion", project.version.toString())
    inputs.property("exposedVersion", libs.versions.exposed.get())
    filesMatching("keel-framework.properties") {
        expand(
            "frameworkVersion" to project.version.toString(),
            "exposedVersion" to libs.versions.exposed.get()
        )
    }
}

dependencies {
    // Ktor
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    compileOnly(libs.ktor.server.cio)
    compileOnly(libs.ktor.server.tomcat)
    compileOnly(libs.ktor.server.jetty)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.tomcat)
    testImplementation(libs.ktor.server.jetty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.websockets)

    // Koin
    implementation(libs.koin.ktor)
    implementation(libs.koin.core)

    // Exposed
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)

    // kotlinx
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.soul.logger.core)

    // SSE
    implementation(libs.ktor.server.sse)

    // Netty
    implementation(libs.netty.all)

    // Micrometer
    implementation(libs.micrometer.core)

    // OpenTelemetry
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.context)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.sdk.trace)

    // Internal modules
    implementation(project(":keel-contract"))
    implementation(project(":keel-exposed-starter"))
    implementation(project(":keel-jvm-runtime"))
    implementation(project(":keel-openapi-annotations"))
    implementation(project(":keel-openapi-runtime"))
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))

    ksp(project(":keel-openapi-processor"))
}
