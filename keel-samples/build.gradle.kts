plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("application")
}

// Generated React/Vite bundles are staged here and added to the main resources
// classpath ahead of checked-in fallback assets.
val generatedFrontendResources = layout.buildDirectory.dir("generated-resources/frontend")

sourceSets {
    named("main") {
        resources.setSrcDirs(listOf(generatedFrontendResources, "src/main/resources"))
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
    mainClass.set("ExportH2DataTaskKt")

    findProperty("dbFolder")?.let { systemProperty("dbFolder", it.toString()) }
}

tasks.register<JavaExec>("aiRelayBenchmark") {
    description = "Run the AI relay production-like benchmark harness"
    group = "keel-sample"

    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("AiRelayBenchmarkTaskKt")

    findProperty("benchmarkConfig")?.let { args("--config=${it}") }
    findProperty("benchmarkPhase")?.let { args("--phase=${it}") }
    findProperty("benchmarkOutputDir")?.let { args("--output=${it}") }
}

tasks.register<JavaExec>("aiRelayBenchmarkSmoke") {
    description = "Run a short AI relay benchmark smoke validation"
    group = "keel-sample"

    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("AiRelayBenchmarkTaskKt")
    args("--phase=smoke")
    findProperty("benchmarkOutputDir")?.let { args("--output=${it}") }
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
    // Keep Gradle self-contained for developers: install dependencies even when
    // package-lock.json is missing or stale after ad-hoc local npm usage.
    commandLine("npm", "install", "--no-package-lock")
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file(".npmrc")).optional()
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
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.routing)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)
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
