plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("application")
}

// Custom source set for tools + generated frontend resources.
val generatedFrontendResources = layout.buildDirectory.dir("generated-resources/frontend")

sourceSets {
    named("main") {
        resources {
            exclude("ui/customer-portal-ui/**")
            exclude("ui/ai-gateway-ui/**")
            srcDir(generatedFrontendResources)
        }
    }

    create("tools") {
        kotlin.srcDir("src/tools/kotlin")
        compileClasspath += sourceSets["main"].compileClasspath
        runtimeClasspath += sourceSets["main"].runtimeClasspath
    }
}

// Task to export H2 database data to CSV via TCP
tasks.register<JavaExec>("exportH2Data") {
    description = "Export airelay config tables to CSV (requires H2 TCP server on port 9092)"
    group = "keel-sample"

    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("ExportH2DataKt")

    findProperty("dbFolder")?.let { systemProperty("dbFolder", it.toString()) }
}

kotlin {
    jvmToolchain(23)
}

val frontendDir = layout.projectDirectory.dir("frontend")
val customerPortalAppDir = frontendDir.dir("apps/customer-portal")
val aiGatewayAppDir = frontendDir.dir("apps/ai-gateway")

val installFrontendDependencies by tasks.registering(Exec::class) {
    description = "Install npm dependencies for the sample React frontends"
    group = "frontend"
    workingDir = frontendDir.asFile
    commandLine("npm", "ci")
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("package-lock.json")).optional()
    inputs.dir(frontendDir.dir("packages"))
    inputs.dir(frontendDir.dir("apps"))
    outputs.dir(frontendDir.dir("node_modules"))
}

val buildCustomerPortalFrontend by tasks.registering(Exec::class) {
    description = "Build the Customer Portal React frontend"
    group = "frontend"
    dependsOn(installFrontendDependencies)
    workingDir = frontendDir.asFile
    commandLine("npm", "run", "build:customer")
    inputs.dir(customerPortalAppDir.dir("src"))
    inputs.file(customerPortalAppDir.file("index.html"))
    inputs.file(customerPortalAppDir.file("package.json"))
    inputs.file(customerPortalAppDir.file("tsconfig.json"))
    inputs.file(customerPortalAppDir.file("vite.config.ts"))
    inputs.dir(frontendDir.dir("packages/ui/src"))
    outputs.dir(customerPortalAppDir.dir("dist"))
}

val buildAiGatewayFrontend by tasks.registering(Exec::class) {
    description = "Build the AI Gateway React frontend"
    group = "frontend"
    dependsOn(installFrontendDependencies)
    workingDir = frontendDir.asFile
    commandLine("npm", "run", "build:ai-gateway")
    inputs.dir(aiGatewayAppDir.dir("src"))
    inputs.file(aiGatewayAppDir.file("index.html"))
    inputs.file(aiGatewayAppDir.file("package.json"))
    inputs.file(aiGatewayAppDir.file("tsconfig.json"))
    inputs.file(aiGatewayAppDir.file("vite.config.ts"))
    inputs.dir(frontendDir.dir("packages/ui/src"))
    outputs.dir(aiGatewayAppDir.dir("dist"))
}

val syncCustomerPortalFrontend by tasks.registering(Sync::class) {
    description = "Sync Customer Portal dist into generated classpath resources"
    group = "frontend"
    dependsOn(buildCustomerPortalFrontend)
    from(customerPortalAppDir.dir("dist"))
    into(generatedFrontendResources.map { it.dir("ui/customer-portal-ui") })
}

val syncAiGatewayFrontend by tasks.registering(Sync::class) {
    description = "Sync AI Gateway dist into generated classpath resources"
    group = "frontend"
    dependsOn(buildAiGatewayFrontend)
    from(aiGatewayAppDir.dir("dist"))
    into(generatedFrontendResources.map { it.dir("ui/ai-gateway-ui") })
}

tasks.register("buildSampleFrontends") {
    description = "Build both React/Vite sample frontends"
    group = "frontend"
    dependsOn(syncCustomerPortalFrontend, syncAiGatewayFrontend)
}

tasks.processResources {
    dependsOn(syncCustomerPortalFrontend, syncAiGatewayFrontend)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

application {
    mainClass.set("com.keel.samples.KeelSampleKt")
}

dependencies {
    implementation(project(":keel-core"))
    implementation(project(":keel-contract"))
    implementation(project(":keel-exposed-starter"))
    implementation(project(":keel-openapi-annotations"))
    implementation(project(":keel-openapi-runtime"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.koin.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.h2.database)

    ksp(project(":keel-openapi-processor"))
}
