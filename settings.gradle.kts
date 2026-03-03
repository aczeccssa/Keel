pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Keel"

include("keel-core")
include("keel-contract")
include("keel-exposed-starter")
include("keel-test-suite")
include("keel-openapi-annotations")
include("keel-openapi-processor")
include("keel-openapi-runtime")
include("keel-ipc-runtime")
include("keel-samples")
include("plugins:sample-hello")
include("plugins:sample-dbdemo")
