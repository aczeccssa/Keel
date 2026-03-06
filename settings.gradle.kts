pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
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
include("keel-uds-runtime")
include("keel-samples")
