plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.ktor)
    implementation(libs.koin.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)

    implementation(project(":keel-contract"))
    implementation(project(":keel-core"))
    implementation(project(":keel-exposed-starter"))
    testImplementation(project(":keel-openapi-runtime"))

    // Test dependencies
    testImplementation(libs.koin.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.ktor.server.sse)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("io.ktor:ktor-server-test-host:3.4.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation(libs.h2.database)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
}
