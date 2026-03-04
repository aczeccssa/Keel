plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization")
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
    implementation(project(":plugins:sample-hello"))
    implementation(project(":plugins:sample-dbdemo"))
    implementation(project(":plugins:sample-observability"))
}
