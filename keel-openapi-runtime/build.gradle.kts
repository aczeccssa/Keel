plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(kotlin("reflect"))
    implementation(project(":keel-contract"))
    implementation(project(":keel-openapi-annotations"))

    testImplementation(project(":keel-core"))
    testImplementation(project(":keel-samples"))
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation("io.ktor:ktor-server-test-host:3.4.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
}
