plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.kotlin.datetime)

    api(libs.kotlinx.datetime)
    api(libs.kotlinx.serialization.json)

    implementation(libs.koin.core)

    implementation(libs.hikari.cp)

    implementation(libs.soul.logger.core)

    implementation(project(":keel-contract"))

    // H2 database for testing
    testImplementation("com.h2database:h2:2.2.224")
}
