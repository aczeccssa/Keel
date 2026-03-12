@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven(url = rootDir.resolve("third_party/maven"))
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Keel"

include("keel-core")
include("keel-contract")
include("keel-exposed-starter")
include("keel-test-suite")
include("keel-openapi-annotations")
include("keel-openapi-processor")
include("keel-openapi-runtime")
include("keel-jvm-runtime")
include("keel-samples")
