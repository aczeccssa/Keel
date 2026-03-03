plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":keel-contract"))
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
}
