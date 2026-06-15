# AI Relay Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-like benchmark harness for `keel-samples` that measures Anthropic Messages and OpenAI Responses API-key relay capacity from authentication through upstream transform/send/receive/transform/response.

**Architecture:** Add an opt-in benchmark package under `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark` plus a small tools entrypoint under `keel-samples/src/tools/kotlin`. The harness starts a real local simulated provider HTTP/SSE server, starts a real local Keel SUT with account/token/riskcontrol/airelay plugins, creates a real API key, sends heavy HTTP traffic through the public relay endpoints, and writes summary, time-series, and per-request reports.

**Tech Stack:** Kotlin/JVM 23, Ktor server/client CIO, kotlinx.coroutines, kotlinx.serialization JSON, existing Keel plugin manager and AI gateway sample plugins, Gradle `tools` sourceSet.

---

## Implementation Notes

- Treat the existing dirty worktree as user-owned. Before editing a tracked file that already has modifications, inspect it and preserve unrelated changes.
- Do not use `MockableUpstreamHttpClient` for load behavior. The benchmark must exercise real HTTP and SSE transport between Keel and the simulated provider.
- Do not change production relay behavior except adding benchmark-only code and optional request correlation headers.
- The first pass prioritizes correctness and reproducibility over pretty charts.
- Heavy benchmark runs must not execute during normal `test` or `check`.

## File Structure

- Modify `keel-samples/build.gradle.kts`: add server dependencies used directly by benchmark code and register `aiRelayBenchmark` / `aiRelayBenchmarkSmoke` JavaExec tasks.
- Create `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkModels.kt`: serializable config, enums, case identifiers, default smoke/full matrices, duration parsing.
- Create `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkTopologyFactory.kt`: generate `AIRelaySettings`, pool chains, model names, and API-key group ids for direct, alias-specific, and alias-any-provider cases.
- Create `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/SimulatedProviderServer.kt`: Ktor provider server with Anthropic Messages and OpenAI Responses blocking/SSE endpoints, provider concurrency counters, request log records, and protocol-valid responses.
- Create `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkKeelServer.kt`: embedded Keel SUT launcher, temporary data dir, generated `keel.airelay.config`, plugin startup, login, API-key creation.
- Create `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkHttpClient.kt`: relay request builder and blocking/SSE client with latency, TTFB, chunk counting, status, and error capture.
- Create `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkStatistics.kt`: percentile calculation, per-second windows, stability decision, P99 drift detection, and `503` attribution.
- Create `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkReports.kt`: `summary.json`, `summary.csv`, `timeseries.jsonl`, `requests.jsonl`, and run metadata writers.
- Create `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkRunner.kt`: smoke, calibration, step-concurrency, and near-limit soak orchestration.
- Create `keel-samples/src/tools/kotlin/AiRelayBenchmarkTask.kt`: CLI entrypoint for Gradle JavaExec tasks.
- Create tests under `keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/`.

## Task 1: Gradle Benchmark Entry Points

**Files:**
- Modify: `keel-samples/build.gradle.kts`

- [ ] **Step 1: Inspect current Gradle file before editing**

Run:

```bash
git diff -- keel-samples/build.gradle.kts
```

Expected: review existing user changes and keep them intact.

- [ ] **Step 2: Add direct Ktor server dependencies**

In the `dependencies` block, add these lines if they are not already present:

```kotlin
implementation(libs.ktor.server.netty)
implementation(libs.ktor.server.cio)
implementation(libs.ktor.server.routing)
implementation(libs.ktor.server.content.negotiation)
implementation(libs.ktor.serialization.kotlinx.json)
```

Expected: benchmark code can directly start Ktor servers and install JSON content negotiation without relying on transitive dependencies.

- [ ] **Step 3: Register benchmark JavaExec tasks**

After the existing `exportH2Data` task, add:

```kotlin
tasks.register<JavaExec>("aiRelayBenchmark") {
    description = "Run the AI relay production-like benchmark harness"
    group = "keel-sample"

    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("AiRelayBenchmarkTaskKt")

    findProperty("benchmarkConfig")?.let { arg("--config=${it}") }
    findProperty("benchmarkPhase")?.let { arg("--phase=${it}") }
    findProperty("benchmarkOutputDir")?.let { arg("--output=${it}") }
}

tasks.register<JavaExec>("aiRelayBenchmarkSmoke") {
    description = "Run a short AI relay benchmark smoke validation"
    group = "keel-sample"

    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("AiRelayBenchmarkTaskKt")
    args("--phase=smoke")
    findProperty("benchmarkOutputDir")?.let { arg("--output=${it}") }
}
```

Expected: developers can run `./gradlew :keel-samples:aiRelayBenchmarkSmoke`.

- [ ] **Step 4: Verify Gradle Kotlin DSL compiles**

Run:

```bash
./gradlew :keel-samples:tasks --all
```

Expected: output includes `aiRelayBenchmark` and `aiRelayBenchmarkSmoke`.

- [ ] **Step 5: Commit**

Run:

```bash
git add keel-samples/build.gradle.kts
git commit -m "build: add ai relay benchmark tasks"
```

Expected: commit succeeds.

## Task 2: Benchmark Config and Matrix Models

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkModels.kt`
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkModelsTest.kt`

- [ ] **Step 1: Write failing model tests**

Create `BenchmarkModelsTest.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkModelsTest {
    @Test
    fun smokeMatrixIsSmallButCoversBothProtocolsAndModes() {
        val cases = BenchmarkConfig.smoke().cases()

        assertTrue(cases.any { it.protocol == BenchmarkProtocol.ANTHROPIC_MESSAGES })
        assertTrue(cases.any { it.protocol == BenchmarkProtocol.OPENAI_RESPONSES })
        assertTrue(cases.any { it.requestMode == BenchmarkRequestMode.BLOCKING })
        assertTrue(cases.any { it.requestMode == BenchmarkRequestMode.STREAMING })
        assertTrue(cases.size <= 8, "smoke matrix should stay cheap")
    }

    @Test
    fun fullMatrixIncludesGenerationDurationLadderAndAliasProviderCounts() {
        val config = BenchmarkConfig.full()

        assertEquals(listOf(1_000L, 3_000L, 5_000L, 10_000L, 20_000L, 40_000L), config.generationDurationsMs)
        assertEquals(listOf(2, 3, 5), config.attachedProviderCounts)
        assertTrue(config.topologies.contains(BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER))
    }

    @Test
    fun caseIdIsStableAndFilesystemSafe() {
        val case = BenchmarkCase(
            protocol = BenchmarkProtocol.OPENAI_RESPONSES,
            requestMode = BenchmarkRequestMode.STREAMING,
            topology = BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER,
            attachedProviderCount = 5,
            generationDurationMs = 40_000L
        )

        assertEquals("openai_responses-streaming-alias_any_attached_provider-p5-40000ms", case.id)
        assertFalse(case.id.contains(" "))
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkModelsTest'
```

Expected: FAIL because benchmark model classes do not exist.

- [ ] **Step 3: Add benchmark model implementation**

Create `BenchmarkModels.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkProtocol {
    ANTHROPIC_MESSAGES,
    OPENAI_RESPONSES;

    val path: String get() = when (this) {
        ANTHROPIC_MESSAGES -> "/api/plugins/airelay/v1/messages"
        OPENAI_RESPONSES -> "/api/plugins/airelay/v1/responses"
    }

    val id: String get() = name.lowercase()
}

@Serializable
enum class BenchmarkRequestMode {
    BLOCKING,
    STREAMING;

    val isStreaming: Boolean get() = this == STREAMING
    val id: String get() = name.lowercase()
}

@Serializable
enum class BenchmarkTopology {
    DIRECT_PROVIDER,
    ALIAS_SPECIFIC_PROVIDER,
    ALIAS_ANY_ATTACHED_PROVIDER;

    val id: String get() = name.lowercase()
}

@Serializable
data class BenchmarkConfig(
    val protocols: List<BenchmarkProtocol>,
    val requestModes: List<BenchmarkRequestMode>,
    val topologies: List<BenchmarkTopology>,
    val attachedProviderCounts: List<Int>,
    val generationDurationsMs: List<Long>,
    val providerMaxConcurrency: Int = 1_000_000,
    val concurrencyLadder: List<Int>,
    val warmupSeconds: Int = 10,
    val measuredSecondsShort: Int = 90,
    val measuredSecondsLong: Int = 180,
    val soakMinutes: Int = 10,
    val stability: BenchmarkStability = BenchmarkStability(),
) {
    init {
        require(providerMaxConcurrency > 0) { "providerMaxConcurrency must be positive" }
        require(concurrencyLadder.isNotEmpty()) { "concurrencyLadder must not be empty" }
    }

    fun cases(): List<BenchmarkCase> = protocols.flatMap { protocol ->
        requestModes.flatMap { mode ->
            topologies.flatMap { topology ->
                val providerCounts = if (topology == BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER) {
                    attachedProviderCounts
                } else {
                    listOf(1)
                }
                providerCounts.flatMap { count ->
                    generationDurationsMs.map { duration ->
                        BenchmarkCase(protocol, mode, topology, count, duration)
                    }
                }
            }
        }
    }

    companion object {
        fun smoke(): BenchmarkConfig = BenchmarkConfig(
            protocols = listOf(BenchmarkProtocol.ANTHROPIC_MESSAGES, BenchmarkProtocol.OPENAI_RESPONSES),
            requestModes = listOf(BenchmarkRequestMode.BLOCKING, BenchmarkRequestMode.STREAMING),
            topologies = listOf(BenchmarkTopology.DIRECT_PROVIDER),
            attachedProviderCounts = listOf(1),
            generationDurationsMs = listOf(100L),
            concurrencyLadder = listOf(1, 2),
            warmupSeconds = 0,
            measuredSecondsShort = 2,
            measuredSecondsLong = 2,
            soakMinutes = 1,
        )

        fun full(): BenchmarkConfig = BenchmarkConfig(
            protocols = listOf(BenchmarkProtocol.ANTHROPIC_MESSAGES, BenchmarkProtocol.OPENAI_RESPONSES),
            requestModes = listOf(BenchmarkRequestMode.BLOCKING, BenchmarkRequestMode.STREAMING),
            topologies = listOf(
                BenchmarkTopology.DIRECT_PROVIDER,
                BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER,
                BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER
            ),
            attachedProviderCounts = listOf(2, 3, 5),
            generationDurationsMs = listOf(1_000L, 3_000L, 5_000L, 10_000L, 20_000L, 40_000L),
            concurrencyLadder = listOf(10, 25, 50, 100, 200, 400, 800, 1_200, 1_600, 2_400)
        )
    }
}

@Serializable
data class BenchmarkStability(
    val max503Rate: Double = 0.001,
    val maxErrorRate: Double = 0.005,
    val p99DriftWindowCount: Int = 3,
    val p99DriftRatio: Double = 1.25,
)

@Serializable
data class BenchmarkCase(
    val protocol: BenchmarkProtocol,
    val requestMode: BenchmarkRequestMode,
    val topology: BenchmarkTopology,
    val attachedProviderCount: Int,
    val generationDurationMs: Long,
) {
    val id: String
        get() = "${protocol.id}-${requestMode.id}-${topology.id}-p$attachedProviderCount-${generationDurationMs}ms"
}

enum class BenchmarkPhase {
    SMOKE,
    CALIBRATION,
    STEP,
    SOAK,
    FULL;

    companion object {
        fun parse(value: String?): BenchmarkPhase = when (value?.lowercase()) {
            null, "", "smoke" -> SMOKE
            "calibration" -> CALIBRATION
            "step" -> STEP
            "soak" -> SOAK
            "full" -> FULL
            else -> error("Unknown benchmark phase: $value")
        }
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkModelsTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkModels.kt keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkModelsTest.kt
git commit -m "feat: add ai relay benchmark matrix models"
```

Expected: commit succeeds.

## Task 3: Routing Topology Factory

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkTopologyFactory.kt`
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkTopologyFactoryTest.kt`

- [ ] **Step 1: Write failing topology tests**

Create `BenchmarkTopologyFactoryTest.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import com.keel.samples.aigateway.airelay.GroupExposureMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BenchmarkTopologyFactoryTest {
    @Test
    fun directProviderExposesRequestedModelWithoutAliasRoute() {
        val topology = BenchmarkTopologyFactory.create(
            case = BenchmarkCase(
                protocol = BenchmarkProtocol.ANTHROPIC_MESSAGES,
                requestMode = BenchmarkRequestMode.BLOCKING,
                topology = BenchmarkTopology.DIRECT_PROVIDER,
                attachedProviderCount = 1,
                generationDurationMs = 1_000
            ),
            providerBaseUrl = "http://127.0.0.1:19001",
            providerMaxConcurrency = 25
        )

        assertEquals("bench-direct-anthropic_messages", topology.groupId)
        assertEquals("bench-direct-anthropic_messages-model", topology.requestModel)
        assertEquals("bench-direct-anthropic_messages-model", topology.settings.chains.single().modelAliases.single())
        assertTrue(topology.settings.chains.single().aliasRoutes.isEmpty())
    }

    @Test
    fun aliasSpecificPinsAliasToOneChannel() {
        val topology = BenchmarkTopologyFactory.create(
            case = BenchmarkCase(
                protocol = BenchmarkProtocol.OPENAI_RESPONSES,
                requestMode = BenchmarkRequestMode.STREAMING,
                topology = BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER,
                attachedProviderCount = 1,
                generationDurationMs = 3_000
            ),
            providerBaseUrl = "http://127.0.0.1:19001",
            providerMaxConcurrency = 50
        )

        val alias = topology.settings.chains.single().aliasRoutes.single()
        assertEquals(topology.requestModel, alias.aliasName)
        assertEquals("bench-alias-specific-openai_responses-provider-1", alias.targets.single().channelId)
    }

    @Test
    fun aliasAnyCreatesRequestedProviderCountTargets() {
        val topology = BenchmarkTopologyFactory.create(
            case = BenchmarkCase(
                protocol = BenchmarkProtocol.ANTHROPIC_MESSAGES,
                requestMode = BenchmarkRequestMode.BLOCKING,
                topology = BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER,
                attachedProviderCount = 5,
                generationDurationMs = 5_000
            ),
            providerBaseUrl = "http://127.0.0.1:19001",
            providerMaxConcurrency = 100
        )

        val chain = topology.settings.chains.single()
        assertEquals(GroupExposureMode.ALIASES_AND_MODELS, chain.exposureMode)
        assertEquals(5, chain.levels.single().keys.size)
        assertEquals(5, chain.aliasRoutes.single().targets.size)
        assertNotNull(chain.levels.single().keys.first().provider)
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkTopologyFactoryTest'
```

Expected: FAIL because topology factory does not exist.

- [ ] **Step 3: Implement topology factory**

Create `BenchmarkTopologyFactory.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import com.keel.samples.aigateway.airelay.AIRelaySettings
import com.keel.samples.aigateway.airelay.AliasRouteConfig
import com.keel.samples.aigateway.airelay.AliasTargetConfig
import com.keel.samples.aigateway.airelay.GroupExposureMode
import com.keel.samples.aigateway.airelay.ModelPricing
import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.protocol.WireProtocol

data class BenchmarkTopologySetup(
    val settings: AIRelaySettings,
    val groupId: String,
    val requestModel: String,
)

object BenchmarkTopologyFactory {
    fun create(
        case: BenchmarkCase,
        providerBaseUrl: String,
        providerMaxConcurrency: Int,
    ): BenchmarkTopologySetup {
        val protocolId = case.protocol.id
        val prefix = when (case.topology) {
            BenchmarkTopology.DIRECT_PROVIDER -> "bench-direct-$protocolId"
            BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER -> "bench-alias-specific-$protocolId"
            BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER -> "bench-alias-any-$protocolId-p${case.attachedProviderCount}"
        }
        val groupId = prefix
        val targetModels = List(case.attachedProviderCount.coerceAtLeast(1)) { index ->
            "$prefix-upstream-model-${index + 1}"
        }
        val requestModel = when (case.topology) {
            BenchmarkTopology.DIRECT_PROVIDER -> "$prefix-model"
            BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER,
            BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER -> "$prefix-alias"
        }
        val keys = targetModels.mapIndexed { index, targetModel ->
            val channelId = "$prefix-provider-${index + 1}"
            PooledKeyConfig(
                keyId = channelId,
                apiKey = "bench-provider-key-${index + 1}",
                maxConcurrency = providerMaxConcurrency,
                supportedModels = listOf(if (case.topology == BenchmarkTopology.DIRECT_PROVIDER) requestModel else targetModel),
                provider = UpstreamProviderConfig(
                    providerId = channelId,
                    baseUrl = providerBaseUrl,
                    protocol = case.protocol.toWireProtocol(),
                    timeoutMs = maxOf(120_000L, case.generationDurationMs * 3),
                    defaultHeaders = mapOf(
                        "X-Benchmark-Provider-Id" to channelId,
                        "X-Benchmark-Generation-Ms" to case.generationDurationMs.toString()
                    )
                )
            )
        }
        val modelAliases = when (case.topology) {
            BenchmarkTopology.DIRECT_PROVIDER -> listOf(requestModel)
            BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER,
            BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER -> (listOf(requestModel) + targetModels).distinct()
        }
        val aliasRoutes = when (case.topology) {
            BenchmarkTopology.DIRECT_PROVIDER -> emptyList()
            BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER -> listOf(
                AliasRouteConfig(
                    aliasName = requestModel,
                    targets = listOf(AliasTargetConfig(targetModels.first(), keys.first().keyId))
                )
            )
            BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER -> listOf(
                AliasRouteConfig(
                    aliasName = requestModel,
                    targets = targetModels.zip(keys).map { (model, key) -> AliasTargetConfig(model, key.keyId) }
                )
            )
        }
        val chain = PoolChainConfig(
            chainId = groupId,
            modelAliases = modelAliases,
            exposureMode = GroupExposureMode.ALIASES_AND_MODELS,
            aliasRoutes = aliasRoutes,
            levels = listOf(
                PoolLevelConfig(
                    levelId = "$prefix-l1",
                    levelIndex = 1,
                    provider = UpstreamProviderConfig(
                        providerId = "$prefix-provider-level",
                        baseUrl = providerBaseUrl,
                        protocol = case.protocol.toWireProtocol(),
                        timeoutMs = maxOf(120_000L, case.generationDurationMs * 3)
                    ),
                    keys = keys,
                    cooldownMs = 5_000
                )
            )
        )
        val pricing = modelAliases.map {
            ModelPricing(model = it, inputCostPerMTok = 0.0, outputCostPerMTok = 0.0)
        }
        return BenchmarkTopologySetup(
            settings = AIRelaySettings(chains = listOf(chain), pricings = pricing),
            groupId = groupId,
            requestModel = requestModel
        )
    }
}

fun BenchmarkProtocol.toWireProtocol(): WireProtocol = when (this) {
    BenchmarkProtocol.ANTHROPIC_MESSAGES -> WireProtocol.ANTHROPIC_MESSAGES
    BenchmarkProtocol.OPENAI_RESPONSES -> WireProtocol.OPENAI_RESPONSES
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkTopologyFactoryTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkTopologyFactory.kt keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkTopologyFactoryTest.kt
git commit -m "feat: generate ai relay benchmark topologies"
```

Expected: commit succeeds.

## Task 4: Simulated Provider HTTP/SSE Server

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/SimulatedProviderServer.kt`
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/SimulatedProviderServerTest.kt`

- [ ] **Step 1: Write failing provider tests**

Create `SimulatedProviderServerTest.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedProviderServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun anthropicBlockingResponseIsProtocolValidAndDelayed() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { server ->
            val client = HttpClient(CIO)
            val started = System.nanoTime()
            val response = client.post("${server.baseUrl}/v1/messages") {
                header("x-api-key", "provider-key")
                header("X-Benchmark-Provider-Id", "provider-a")
                header("X-Benchmark-Generation-Ms", "75")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-bench","max_tokens":32,"messages":[{"role":"user","content":"hello"}]}""")
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

            assertTrue(elapsedMs >= 60, "provider should honor generation delay")
            assertEquals("message", body["type"]!!.jsonPrimitive.content)
            assertEquals("claude-bench", body["model"]!!.jsonPrimitive.content)
            assertEquals(1, server.snapshot().totalRequests)
            client.close()
        }
    }

    @Test
    fun responsesStreamingEmitsSseChunksOverConfiguredDuration() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { server ->
            val client = HttpClient(CIO)
            val response = client.post("${server.baseUrl}/v1/responses?stream=true") {
                header(HttpHeaders.Authorization, "Bearer provider-key")
                header("X-Benchmark-Provider-Id", "provider-r")
                header("X-Benchmark-Generation-Ms", "75")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"resp-bench","input":"hello","stream":true}""")
            }
            val body = response.bodyAsText()

            assertTrue(body.contains("event: response.output_text.delta"))
            assertTrue(body.contains("event: response.completed"))
            assertEquals(1, server.snapshot().totalRequests)
            client.close()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.SimulatedProviderServerTest'
```

Expected: FAIL because simulated provider does not exist.

- [ ] **Step 3: Implement simulated provider**

Create `SimulatedProviderServer.kt` with:

```kotlin
package com.keel.samples.aigateway.benchmark

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SimulatedProviderServer private constructor(
    private val engine: ApplicationEngine,
    val port: Int,
    private val state: State,
) : Closeable {
    val baseUrl: String = "http://127.0.0.1:$port"

    data class ProviderRequestLog(
        val requestId: String?,
        val providerId: String,
        val protocol: BenchmarkProtocol,
        val streaming: Boolean,
        val generationMs: Long,
        val startedEpochMs: Long,
        val completedEpochMs: Long,
    )

    data class Snapshot(
        val totalRequests: Long,
        val activeByProvider: Map<String, Int>,
        val maxActiveByProvider: Map<String, Int>,
        val logs: List<ProviderRequestLog>,
    )

    private class State {
        val totalRequests = AtomicLong()
        val activeByProvider = ConcurrentHashMap<String, AtomicInteger>()
        val maxActiveByProvider = ConcurrentHashMap<String, AtomicInteger>()
        val logs = CopyOnWriteArrayList<ProviderRequestLog>()
    }

    fun snapshot(): Snapshot = Snapshot(
        totalRequests = state.totalRequests.get(),
        activeByProvider = state.activeByProvider.mapValues { it.value.get() },
        maxActiveByProvider = state.maxActiveByProvider.mapValues { it.value.get() },
        logs = state.logs.toList(),
    )

    override fun close() {
        engine.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun start(port: Int = 0): SimulatedProviderServer {
            val resolvedPort = if (port == 0) freePort() else port
            val state = State()
            val engine = embeddedServer(CIO, port = resolvedPort, host = "127.0.0.1") {
                routing {
                    post("/v1/messages") {
                        val body = call.receiveText()
                        val request = json.parseToJsonElement(body).jsonObject
                        val streaming = call.request.queryParameters["stream"] == "true" ||
                            request["stream"]?.jsonPrimitive?.contentOrNull == "true"
                        if (streaming) {
                            val context = ProviderContext.from(call.request.header("X-Benchmark-Request-Id"), call.request.header("X-Benchmark-Provider-Id"), call.request.header("X-Benchmark-Generation-Ms"))
                            state.record(context, BenchmarkProtocol.ANTHROPIC_MESSAGES, streaming = true) {
                                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                    writeAnthropicStream(context)
                                }
                            }
                            return@post
                        }
                        val context = ProviderContext.from(call.request.header("X-Benchmark-Request-Id"), call.request.header("X-Benchmark-Provider-Id"), call.request.header("X-Benchmark-Generation-Ms"))
                        state.record(context, BenchmarkProtocol.ANTHROPIC_MESSAGES, streaming = false) {
                            delay(context.generationMs)
                            call.response.header("X-Benchmark-Provider-Id", context.providerId)
                            call.respondText(anthropicBody(request, context.providerId), ContentType.Application.Json)
                        }
                    }
                    post("/v1/responses") {
                        val body = call.receiveText()
                        val request = json.parseToJsonElement(body).jsonObject
                        val streaming = call.request.queryParameters["stream"] == "true" ||
                            request["stream"]?.jsonPrimitive?.contentOrNull == "true"
                        val context = ProviderContext.from(call.request.header("X-Benchmark-Request-Id"), call.request.header("X-Benchmark-Provider-Id"), call.request.header("X-Benchmark-Generation-Ms"))
                        if (streaming) {
                            state.record(context, BenchmarkProtocol.OPENAI_RESPONSES, streaming = true) {
                                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                    writeResponsesStream(context)
                                }
                            }
                        } else {
                            state.record(context, BenchmarkProtocol.OPENAI_RESPONSES, streaming = false) {
                                delay(context.generationMs)
                                call.response.header("X-Benchmark-Provider-Id", context.providerId)
                                call.respondText(responsesBody(request, context.providerId), ContentType.Application.Json)
                            }
                        }
                    }
                }
            }.start(wait = false)
            return SimulatedProviderServer(engine, resolvedPort, state)
        }

        private data class ProviderContext(
            val requestId: String?,
            val providerId: String,
            val generationMs: Long,
        ) {
            companion object {
                fun from(requestId: String?, providerId: String?, generationMs: String?): ProviderContext =
                    ProviderContext(
                        requestId = requestId,
                        providerId = providerId?.takeIf { it.isNotBlank() } ?: "provider-unknown",
                        generationMs = generationMs?.toLongOrNull()?.coerceAtLeast(0L) ?: 1_000L,
                    )
            }
        }

        private suspend fun State.record(
            context: ProviderContext,
            protocol: BenchmarkProtocol,
            streaming: Boolean,
            block: suspend () -> Unit,
        ) {
            val started = System.currentTimeMillis()
            totalRequests.incrementAndGet()
            val active = activeByProvider.computeIfAbsent(context.providerId) { AtomicInteger() }.incrementAndGet()
            maxActiveByProvider.computeIfAbsent(context.providerId) { AtomicInteger() }.updateAndGet { old -> maxOf(old, active) }
            try {
                block()
            } finally {
                activeByProvider.getValue(context.providerId).decrementAndGet()
                logs += ProviderRequestLog(
                    requestId = context.requestId,
                    providerId = context.providerId,
                    protocol = protocol,
                    streaming = streaming,
                    generationMs = context.generationMs,
                    startedEpochMs = started,
                    completedEpochMs = System.currentTimeMillis(),
                )
            }
        }

        private fun anthropicBody(request: JsonObject, providerId: String): String {
            val model = request["model"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            return buildJsonObject {
                put("id", JsonPrimitive("msg_$providerId"))
                put("type", JsonPrimitive("message"))
                put("role", JsonPrimitive("assistant"))
                put("model", JsonPrimitive(model))
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive("simulated response from $providerId"))
                    })
                })
                put("stop_reason", JsonPrimitive("end_turn"))
                put("usage", buildJsonObject {
                    put("input_tokens", JsonPrimitive(16))
                    put("output_tokens", JsonPrimitive(8))
                })
            }.toString()
        }

        private suspend fun java.io.Writer.writeAnthropicStream(context: ProviderContext) {
            val gap = (context.generationMs / 3).coerceAtLeast(1)
            write("event: message_start\n")
            write("""data: {"type":"message_start","message":{"id":"msg_${context.providerId}","type":"message","role":"assistant","model":"simulated","content":[],"usage":{"input_tokens":16,"output_tokens":0}}}""" + "\n\n")
            flush()
            delay(gap)
            write("event: content_block_delta\n")
            write("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"simulated "}}""" + "\n\n")
            flush()
            delay(gap)
            write("event: content_block_delta\n")
            write("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"response"}}""" + "\n\n")
            flush()
            delay((context.generationMs - gap - gap).coerceAtLeast(1))
            write("event: message_delta\n")
            write("""data: {"type":"message_delta","usage":{"output_tokens":8},"delta":{"stop_reason":"end_turn"}}""" + "\n\n")
            write("event: message_stop\n")
            write("""data: {"type":"message_stop"}""" + "\n\n")
            flush()
        }

        private fun responsesBody(request: JsonObject, providerId: String): String {
            val model = request["model"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            return buildJsonObject {
                put("id", JsonPrimitive("resp_$providerId"))
                put("object", JsonPrimitive("response"))
                put("status", JsonPrimitive("completed"))
                put("model", JsonPrimitive(model))
                put("output", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive("msg_0"))
                        put("type", JsonPrimitive("message"))
                        put("role", JsonPrimitive("assistant"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("output_text"))
                                put("text", JsonPrimitive("simulated response from $providerId"))
                            })
                        })
                    })
                })
                put("output_text", JsonPrimitive("simulated response from $providerId"))
                put("usage", buildJsonObject {
                    put("input_tokens", JsonPrimitive(16))
                    put("output_tokens", JsonPrimitive(8))
                    put("total_tokens", JsonPrimitive(24))
                })
            }.toString()
        }

        private suspend fun java.io.Writer.writeResponsesStream(context: ProviderContext) {
            val gap = (context.generationMs / 3).coerceAtLeast(1)
            delay(gap)
            write("""event: response.output_text.delta""" + "\n")
            write("""data: {"type":"response.output_text.delta","delta":"simulated "}""" + "\n\n")
            flush()
            delay(gap)
            write("""event: response.output_text.delta""" + "\n")
            write("""data: {"type":"response.output_text.delta","delta":"response"}""" + "\n\n")
            flush()
            delay((context.generationMs - gap - gap).coerceAtLeast(1))
            write("""event: response.completed""" + "\n")
            write("""data: {"type":"response.completed","response":{"id":"resp_${context.providerId}","object":"response","status":"completed","model":"simulated","usage":{"input_tokens":16,"output_tokens":8,"total_tokens":24}}}""" + "\n\n")
            flush()
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.SimulatedProviderServerTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/SimulatedProviderServer.kt keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/SimulatedProviderServerTest.kt
git commit -m "feat: add simulated ai provider server"
```

Expected: commit succeeds.

## Task 5: Embedded Keel SUT Launcher

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkKeelServer.kt`
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkKeelServerTest.kt`

- [ ] **Step 1: Write failing SUT smoke test**

Create `BenchmarkKeelServerTest.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkKeelServerTest {
    @Test
    fun startsKeelAndCreatesApiKeyForBenchmarkGroup() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { provider ->
            val case = BenchmarkConfig.smoke().cases().first { it.protocol == BenchmarkProtocol.ANTHROPIC_MESSAGES }
            val topology = BenchmarkTopologyFactory.create(case, provider.baseUrl, providerMaxConcurrency = 10)
            BenchmarkKeelServer.start(topology).use { keel ->
                val client = HttpClient(CIO)
                val response = client.post("${keel.baseUrl}${BenchmarkProtocol.ANTHROPIC_MESSAGES.path}") {
                    header("x-api-key", keel.apiKey)
                    header("anthropic-version", "2023-06-01")
                    header("X-Benchmark-Request-Id", "test-request")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"${topology.requestModel}","max_tokens":32,"messages":[{"role":"user","content":"hello"}]}""")
                }

                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertTrue(provider.snapshot().totalRequests >= 1)
                client.close()
            }
        }
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkKeelServerTest'
```

Expected: FAIL because `BenchmarkKeelServer` does not exist.

- [ ] **Step 3: Implement embedded Keel server**

Create `BenchmarkKeelServer.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.token.TokenPlugin
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.Closeable
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.io.path.writeText

class BenchmarkKeelServer private constructor(
    private val engine: ApplicationEngine,
    private val previousDataDir: String?,
    private val previousConfigPath: String?,
    private val client: HttpClient,
    val baseUrl: String,
    val apiKey: String,
) : Closeable {
    override fun close() {
        client.close()
        engine.stop(gracePeriodMillis = 500, timeoutMillis = 3_000)
        runCatching { stopKoin() }
        if (previousDataDir == null) System.clearProperty("keel.data.dir") else System.setProperty("keel.data.dir", previousDataDir)
        if (previousConfigPath == null) System.clearProperty("keel.airelay.config") else System.setProperty("keel.airelay.config", previousConfigPath)
        OpenApiRegistry.clear()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun start(topology: BenchmarkTopologySetup, port: Int = 0): BenchmarkKeelServer {
            val resolvedPort = if (port == 0) freePort() else port
            val previousDataDir = System.getProperty("keel.data.dir")
            val previousConfigPath = System.getProperty("keel.airelay.config")
            val dataDir = Files.createTempDirectory("keel-ai-relay-benchmark-")
            val configPath = dataDir.resolve("airelay-settings.json")
            configPath.writeText(json.encodeToString(topology.settings))
            System.setProperty("keel.data.dir", dataDir.toAbsolutePath().toString())
            System.setProperty("keel.airelay.config", configPath.toAbsolutePath().toString())
            OpenApiRegistry.clear()
            runCatching { stopKoin() }
            val koin: Koin = startKoin {}.koin
            val manager = UnifiedPluginManager(koin)
            manager.registerPlugin(AccountPlugin())
            manager.registerPlugin(TokenPlugin())
            manager.registerPlugin(RiskControlPlugin())
            manager.registerPlugin(AIRelayPlugin())
            val engine = embeddedServer(ServerCIO, port = resolvedPort, host = "127.0.0.1") {
                install(ServerContentNegotiation) { json() }
                install(SSE)
                routing { manager.mountRoutes(this) }
                runBlocking {
                    manager.startPlugin("account")
                    manager.startPlugin("token")
                    manager.startPlugin("riskcontrol")
                    manager.startPlugin("airelay")
                }
            }.start(wait = false)
            val client = HttpClient(ClientCIO) {
                install(ClientContentNegotiation) { json(json) }
            }
            val apiKey = runBlocking {
                createApiKey(client, "http://127.0.0.1:$resolvedPort", topology.groupId)
            }
            return BenchmarkKeelServer(
                engine = engine,
                previousDataDir = previousDataDir,
                previousConfigPath = previousConfigPath,
                client = client,
                baseUrl = "http://127.0.0.1:$resolvedPort",
                apiKey = apiKey,
            )
        }

        private suspend fun createApiKey(client: HttpClient, baseUrl: String, groupId: String): String {
            val login: LoginResponse = client.post("$baseUrl/api/plugins/account/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"user@example.com","password":"user123"}""")
            }.body()
            val created: KeyResponse = client.post("$baseUrl/api/plugins/token/v1/keys") {
                header("Authorization", "Bearer ${login.accessToken}")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"AI relay benchmark key","groupId":"$groupId","maxBudgetUsd":1000000000}""")
            }.body()
            return created.rawKey
        }

        @Serializable
        private data class LoginResponse(val accessToken: String)

        @Serializable
        private data class KeyResponse(val rawKey: String)

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkKeelServerTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkKeelServer.kt keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkKeelServerTest.kt
git commit -m "feat: start benchmark keel sut"
```

Expected: commit succeeds.

## Task 6: Relay HTTP Client and Request Metrics

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkHttpClient.kt`
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkHttpClientTest.kt`

- [ ] **Step 1: Write failing HTTP client tests**

Create `BenchmarkHttpClientTest.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BenchmarkHttpClientTest {
    @Test
    fun blockingClientRecordsSuccessfulAnthropicMetrics() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { provider ->
            val case = BenchmarkCase(BenchmarkProtocol.ANTHROPIC_MESSAGES, BenchmarkRequestMode.BLOCKING, BenchmarkTopology.DIRECT_PROVIDER, 1, 50)
            val topology = BenchmarkTopologyFactory.create(case, provider.baseUrl, 10)
            BenchmarkKeelServer.start(topology).use { keel ->
                BenchmarkHttpClient().use { client ->
                    val result = client.execute(keel.baseUrl, keel.apiKey, topology.requestModel, case, "req-1")

                    assertEquals(200, result.status)
                    assertEquals(case.id, result.caseId)
                    assertTrue(result.latencyMs >= 1)
                    assertNotNull(result.providerId)
                }
            }
        }
    }

    @Test
    fun streamingClientRecordsTtfbAndChunkCount() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { provider ->
            val case = BenchmarkCase(BenchmarkProtocol.OPENAI_RESPONSES, BenchmarkRequestMode.STREAMING, BenchmarkTopology.DIRECT_PROVIDER, 1, 50)
            val topology = BenchmarkTopologyFactory.create(case, provider.baseUrl, 10)
            BenchmarkKeelServer.start(topology).use { keel ->
                BenchmarkHttpClient().use { client ->
                    val result = client.execute(keel.baseUrl, keel.apiKey, topology.requestModel, case, "req-2")

                    assertEquals(200, result.status)
                    assertTrue(result.timeToFirstByteMs != null && result.timeToFirstByteMs >= 0)
                    assertTrue(result.chunkCount > 0)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkHttpClientTest'
```

Expected: FAIL because benchmark HTTP client does not exist.

- [ ] **Step 3: Implement benchmark HTTP client**

Create `BenchmarkHttpClient.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.Serializable
import java.io.Closeable

@Serializable
data class BenchmarkRequestResult(
    val requestId: String,
    val caseId: String,
    val protocol: BenchmarkProtocol,
    val requestMode: BenchmarkRequestMode,
    val topology: BenchmarkTopology,
    val attachedProviderCount: Int,
    val generationDurationMs: Long,
    val status: Int,
    val latencyMs: Long,
    val timeToFirstByteMs: Long? = null,
    val streamDurationMs: Long? = null,
    val chunkCount: Int = 0,
    val providerId: String? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
)

class BenchmarkHttpClient : Closeable {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 180_000
        }
        expectSuccess = false
    }

    suspend fun execute(
        keelBaseUrl: String,
        apiKey: String,
        model: String,
        case: BenchmarkCase,
        requestId: String,
    ): BenchmarkRequestResult {
        val started = System.nanoTime()
        return try {
            val response = client.post("$keelBaseUrl${case.protocol.path}") {
                header("X-Benchmark-Request-Id", requestId)
                when (case.protocol) {
                    BenchmarkProtocol.ANTHROPIC_MESSAGES -> {
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    BenchmarkProtocol.OPENAI_RESPONSES -> header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                if (case.requestMode.isStreaming) header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(requestBody(model, case))
            }
            val providerId = response.headers["X-Benchmark-Provider-Id"]
            if (!case.requestMode.isStreaming) {
                response.bodyAsText()
                return result(case, requestId, response.status.value, started, providerId = providerId)
            }
            val channel = response.bodyAsChannel()
            var firstByteMs: Long? = null
            var chunks = 0
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (firstByteMs == null) firstByteMs = elapsedMs(started)
                if (line.startsWith("data:") || line.startsWith("event:")) chunks += 1
            }
            result(
                case = case,
                requestId = requestId,
                status = response.status.value,
                started = started,
                ttfb = firstByteMs,
                streamDuration = elapsedMs(started),
                chunkCount = chunks,
                providerId = providerId,
            )
        } catch (error: Throwable) {
            result(
                case = case,
                requestId = requestId,
                status = 0,
                started = started,
                errorType = error::class.simpleName ?: "Throwable",
                errorMessage = error.message,
            )
        }
    }

    override fun close() {
        client.close()
    }

    private fun requestBody(model: String, case: BenchmarkCase): String = when (case.protocol) {
        BenchmarkProtocol.ANTHROPIC_MESSAGES ->
            """{"model":"$model","max_tokens":512,"stream":${case.requestMode.isStreaming},"messages":[{"role":"user","content":"benchmark payload"}]}"""
        BenchmarkProtocol.OPENAI_RESPONSES ->
            """{"model":"$model","max_output_tokens":512,"stream":${case.requestMode.isStreaming},"store":false,"input":"benchmark payload"}"""
    }

    private fun result(
        case: BenchmarkCase,
        requestId: String,
        status: Int,
        started: Long,
        ttfb: Long? = null,
        streamDuration: Long? = null,
        chunkCount: Int = 0,
        providerId: String? = null,
        errorType: String? = null,
        errorMessage: String? = null,
    ) = BenchmarkRequestResult(
        requestId = requestId,
        caseId = case.id,
        protocol = case.protocol,
        requestMode = case.requestMode,
        topology = case.topology,
        attachedProviderCount = case.attachedProviderCount,
        generationDurationMs = case.generationDurationMs,
        status = status,
        latencyMs = elapsedMs(started),
        timeToFirstByteMs = ttfb,
        streamDurationMs = streamDuration,
        chunkCount = chunkCount,
        providerId = providerId,
        errorType = errorType,
        errorMessage = errorMessage,
    )

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkHttpClientTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkHttpClient.kt keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkHttpClientTest.kt
git commit -m "feat: add ai relay benchmark http client"
```

Expected: commit succeeds.

## Task 7: Statistics, Stability, and 503 Attribution

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkStatistics.kt`
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkStatisticsTest.kt`

- [ ] **Step 1: Write failing statistics tests**

Create `BenchmarkStatisticsTest.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkStatisticsTest {
    @Test
    fun percentilesAreComputedFromSortedLatency() {
        val stats = BenchmarkStatistics.summarize(
            case = sampleCase(),
            concurrency = 10,
            results = (1..100).map { sampleResult(status = 200, latencyMs = it.toLong()) },
            stability = BenchmarkStability(),
        )

        assertEquals(50, stats.p50Ms)
        assertEquals(90, stats.p90Ms)
        assertEquals(95, stats.p95Ms)
        assertEquals(99, stats.p99Ms)
    }

    @Test
    fun status503AboveThresholdIsUnstableAndAttributedPoolExhausted() {
        val results = (1..1000).map { index ->
            if (index <= 2) sampleResult(status = 503, latencyMs = 10, errorMessage = "pool exhausted")
            else sampleResult(status = 200, latencyMs = 10)
        }
        val stats = BenchmarkStatistics.summarize(sampleCase(), 100, results, BenchmarkStability())

        assertFalse(stats.stable)
        assertEquals(0.002, stats.status503Rate)
        assertEquals(2, stats.attribution["pool_exhausted"])
    }

    @Test
    fun p99DriftAcrossThreeWindowsIsUnstable() {
        val windows = listOf(
            BenchmarkTimeWindow(epochSecond = 1, p99Ms = 100, requestCount = 10),
            BenchmarkTimeWindow(epochSecond = 2, p99Ms = 130, requestCount = 10),
            BenchmarkTimeWindow(epochSecond = 3, p99Ms = 170, requestCount = 10),
            BenchmarkTimeWindow(epochSecond = 4, p99Ms = 220, requestCount = 10),
        )

        assertTrue(BenchmarkStatistics.hasSustainedP99Drift(windows, BenchmarkStability()))
    }

    private fun sampleCase() = BenchmarkCase(
        BenchmarkProtocol.OPENAI_RESPONSES,
        BenchmarkRequestMode.BLOCKING,
        BenchmarkTopology.DIRECT_PROVIDER,
        1,
        1_000
    )

    private fun sampleResult(status: Int, latencyMs: Long, errorMessage: String? = null) = BenchmarkRequestResult(
        requestId = "r-$latencyMs-$status",
        caseId = sampleCase().id,
        protocol = BenchmarkProtocol.OPENAI_RESPONSES,
        requestMode = BenchmarkRequestMode.BLOCKING,
        topology = BenchmarkTopology.DIRECT_PROVIDER,
        attachedProviderCount = 1,
        generationDurationMs = 1_000,
        status = status,
        latencyMs = latencyMs,
        errorMessage = errorMessage,
    )
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkStatisticsTest'
```

Expected: FAIL because statistics implementation does not exist.

- [ ] **Step 3: Implement statistics**

Create `BenchmarkStatistics.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
data class BenchmarkStepSummary(
    val caseId: String,
    val concurrency: Int,
    val requests: Int,
    val successes: Int,
    val errors: Int,
    val status503Count: Int,
    val status503Rate: Double,
    val errorRate: Double,
    val p50Ms: Long,
    val p90Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val maxMs: Long,
    val stable: Boolean,
    val instabilityReason: String?,
    val attribution: Map<String, Int>,
)

@Serializable
data class BenchmarkTimeWindow(
    val epochSecond: Long,
    val p99Ms: Long,
    val requestCount: Int,
)

object BenchmarkStatistics {
    fun summarize(
        case: BenchmarkCase,
        concurrency: Int,
        results: List<BenchmarkRequestResult>,
        stability: BenchmarkStability,
        windows: List<BenchmarkTimeWindow> = emptyList(),
    ): BenchmarkStepSummary {
        val requests = results.size
        val errors = results.count { it.status == 0 || it.status >= 400 }
        val status503 = results.count { it.status == 503 }
        val status503Rate = if (requests == 0) 0.0 else status503.toDouble() / requests
        val errorRate = if (requests == 0) 0.0 else errors.toDouble() / requests
        val latencies = results.map { it.latencyMs }.sorted()
        val drift = hasSustainedP99Drift(windows, stability)
        val reason = when {
            status503Rate >= stability.max503Rate -> "503_rate"
            errorRate >= stability.maxErrorRate -> "error_rate"
            drift -> "p99_drift"
            else -> null
        }
        return BenchmarkStepSummary(
            caseId = case.id,
            concurrency = concurrency,
            requests = requests,
            successes = requests - errors,
            errors = errors,
            status503Count = status503,
            status503Rate = status503Rate,
            errorRate = errorRate,
            p50Ms = percentile(latencies, 50.0),
            p90Ms = percentile(latencies, 90.0),
            p95Ms = percentile(latencies, 95.0),
            p99Ms = percentile(latencies, 99.0),
            maxMs = latencies.lastOrNull() ?: 0,
            stable = reason == null,
            instabilityReason = reason,
            attribution = results.filter { it.status == 503 }.groupingBy { attribute503(it) }.eachCount(),
        )
    }

    fun hasSustainedP99Drift(windows: List<BenchmarkTimeWindow>, stability: BenchmarkStability): Boolean {
        if (windows.size < stability.p99DriftWindowCount + 1) return false
        val p99s = windows.map { it.p99Ms }
        val rising = p99s.windowed(stability.p99DriftWindowCount + 1).any { values ->
            values.zipWithNext().all { (left, right) -> right > left }
        }
        val baseline = p99s.first().coerceAtLeast(1)
        val sustainedAboveBaseline = p99s.drop(1)
            .windowed(stability.p99DriftWindowCount)
            .any { values -> values.all { it > baseline * stability.p99DriftRatio } }
        return rising || sustainedAboveBaseline
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        if (sorted.isEmpty()) return 0
        val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun attribute503(result: BenchmarkRequestResult): String {
        val message = result.errorMessage?.lowercase().orEmpty()
        return when {
            message.contains("pool") || message.contains("no upstream") -> "pool_exhausted"
            message.contains("timeout") -> "gateway_timeout"
            message.contains("provider") -> "provider_503"
            message.contains("accounting") || message.contains("usage") -> "local_accounting_or_usage_error"
            message.contains("connection") || message.contains("reset") || message.contains("closed") -> "transport_error"
            else -> "unknown_503"
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkStatisticsTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkStatistics.kt keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkStatisticsTest.kt
git commit -m "feat: summarize ai relay benchmark stability"
```

Expected: commit succeeds.

## Task 8: Reports

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkReports.kt`
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkReportsTest.kt`

- [ ] **Step 1: Write failing report tests**

Create `BenchmarkReportsTest.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class BenchmarkReportsTest {
    @Test
    fun writesSummaryCsvJsonAndRequestJsonl() {
        val dir = Files.createTempDirectory("bench-report-test-")
        val reports = BenchmarkReports(dir)
        val case = BenchmarkCase(BenchmarkProtocol.OPENAI_RESPONSES, BenchmarkRequestMode.BLOCKING, BenchmarkTopology.DIRECT_PROVIDER, 1, 100)
        val result = BenchmarkRequestResult("req-1", case.id, case.protocol, case.requestMode, case.topology, 1, 100, 200, 12)
        val summary = BenchmarkStatistics.summarize(case, concurrency = 1, results = listOf(result), stability = BenchmarkStability())

        reports.writeRequest(result)
        reports.writeStepSummary(summary)
        reports.close()

        assertTrue(dir.resolve("requests.jsonl").readText().contains("req-1"))
        assertTrue(dir.resolve("summary.csv").readText().contains("caseId,concurrency"))
        assertTrue(dir.resolve("summary.json").readText().contains(case.id))
        assertTrue(dir.resolve("timeseries.jsonl").readLines().isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkReportsTest'
```

Expected: FAIL because reports writer does not exist.

- [ ] **Step 3: Implement reports writer**

Create `BenchmarkReports.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedWriter

class BenchmarkReports(private val outputDir: Path) : Closeable {
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val requestsWriter = outputDir.resolve("requests.jsonl").bufferedWriter()
    private val timeseriesWriter = outputDir.resolve("timeseries.jsonl").bufferedWriter()
    private val summaries = mutableListOf<BenchmarkStepSummary>()

    init {
        Files.createDirectories(outputDir)
    }

    fun writeRequest(result: BenchmarkRequestResult) {
        requestsWriter.appendLine(json.encodeToString(result))
        requestsWriter.flush()
    }

    fun writeTimeWindow(window: BenchmarkTimeWindow) {
        timeseriesWriter.appendLine(json.encodeToString(window))
        timeseriesWriter.flush()
    }

    fun writeStepSummary(summary: BenchmarkStepSummary) {
        summaries += summary
        flushSummaries()
    }

    private fun flushSummaries() {
        outputDir.resolve("summary.json").bufferedWriter().use { writer ->
            writer.append(json.encodeToString(summaries))
        }
        outputDir.resolve("summary.csv").bufferedWriter().use { writer ->
            writer.appendLine("caseId,concurrency,requests,successes,errors,status503Count,status503Rate,errorRate,p50Ms,p90Ms,p95Ms,p99Ms,maxMs,stable,instabilityReason")
            summaries.forEach { s ->
                writer.appendLine(
                    listOf(
                        s.caseId,
                        s.concurrency,
                        s.requests,
                        s.successes,
                        s.errors,
                        s.status503Count,
                        s.status503Rate,
                        s.errorRate,
                        s.p50Ms,
                        s.p90Ms,
                        s.p95Ms,
                        s.p99Ms,
                        s.maxMs,
                        s.stable,
                        s.instabilityReason.orEmpty()
                    ).joinToString(",")
                )
            }
        }
    }

    override fun close() {
        requestsWriter.close()
        timeseriesWriter.close()
        flushSummaries()
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkReportsTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkReports.kt keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkReportsTest.kt
git commit -m "feat: write ai relay benchmark reports"
```

Expected: commit succeeds.

## Task 9: Runner Orchestration

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkRunner.kt`
- Test: `keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkRunnerTest.kt`

- [ ] **Step 1: Write failing runner smoke test**

Create `BenchmarkRunnerTest.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class BenchmarkRunnerTest {
    @Test
    fun smokeRunProducesReports() {
        val outputDir = Files.createTempDirectory("ai-relay-runner-smoke-")

        BenchmarkRunner(BenchmarkConfig.smoke(), outputDir).run(BenchmarkPhase.SMOKE)

        assertTrue(outputDir.resolve("summary.csv").exists())
        assertTrue(outputDir.resolve("requests.jsonl").readText().contains("anthropic_messages"))
        assertTrue(outputDir.resolve("requests.jsonl").readText().contains("openai_responses"))
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkRunnerTest'
```

Expected: FAIL because runner does not exist.

- [ ] **Step 3: Implement runner**

Create `BenchmarkRunner.kt`:

```kotlin
package com.keel.samples.aigateway.benchmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class BenchmarkRunner(
    private val config: BenchmarkConfig,
    private val outputDir: Path,
) {
    fun run(phase: BenchmarkPhase) = runBlocking {
        BenchmarkReports(outputDir).use { reports ->
            val cases = when (phase) {
                BenchmarkPhase.SMOKE -> BenchmarkConfig.smoke().cases()
                else -> config.cases()
            }
            for (case in cases) {
                SimulatedProviderServer.start(port = 0).use { provider ->
                    val topology = BenchmarkTopologyFactory.create(case, provider.baseUrl, config.providerMaxConcurrency)
                    BenchmarkKeelServer.start(topology).use { keel ->
                        when (phase) {
                            BenchmarkPhase.SMOKE -> runStep(case, topology, keel, reports, concurrency = 1, measuredSeconds = 1)
                            BenchmarkPhase.CALIBRATION -> runStep(case, topology, keel, reports, concurrency = 1, measuredSeconds = measuredSeconds(case))
                            BenchmarkPhase.STEP -> runStepSearch(case, topology, keel, reports)
                            BenchmarkPhase.SOAK -> runStep(case, topology, keel, reports, concurrency = config.concurrencyLadder.first(), measuredSeconds = config.soakMinutes * 60)
                            BenchmarkPhase.FULL -> {
                                runStepSearch(case, topology, keel, reports)
                                runStep(case, topology, keel, reports, concurrency = config.concurrencyLadder.first(), measuredSeconds = config.soakMinutes * 60)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun runStepSearch(
        case: BenchmarkCase,
        topology: BenchmarkTopologySetup,
        keel: BenchmarkKeelServer,
        reports: BenchmarkReports,
    ) {
        var sawUnstable = false
        for (concurrency in config.concurrencyLadder) {
            val summary = runStep(case, topology, keel, reports, concurrency, measuredSeconds(case))
            if (!summary.stable) {
                if (sawUnstable) break
                sawUnstable = true
            }
        }
    }

    private suspend fun runStep(
        case: BenchmarkCase,
        topology: BenchmarkTopologySetup,
        keel: BenchmarkKeelServer,
        reports: BenchmarkReports,
        concurrency: Int,
        measuredSeconds: Int,
    ): BenchmarkStepSummary {
        val client = BenchmarkHttpClient()
        val deadline = System.nanoTime() + measuredSeconds.seconds.inWholeNanoseconds
        val results = coroutineScope {
            (1..concurrency).map { worker ->
                async(Dispatchers.IO) {
                    val workerResults = mutableListOf<BenchmarkRequestResult>()
                    do {
                        workerResults += client.execute(
                            keelBaseUrl = keel.baseUrl,
                            apiKey = keel.apiKey,
                            model = topology.requestModel,
                            case = case,
                            requestId = "${case.id}-w$worker-${UUID.randomUUID()}",
                        )
                    } while (System.nanoTime() < deadline)
                    workerResults
                }
            }.awaitAll().flatten()
        }
        results.forEach(reports::writeRequest)
        client.close()
        val summary = BenchmarkStatistics.summarize(case, concurrency, results, config.stability)
        reports.writeStepSummary(summary)
        return summary
    }

    private fun measuredSeconds(case: BenchmarkCase): Int =
        if (case.generationDurationMs <= 5_000L) config.measuredSecondsShort else config.measuredSecondsLong
}
```

- [ ] **Step 4: Run smoke runner test**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.BenchmarkRunnerTest'
```

Expected: PASS within a short time because smoke config uses 100 ms provider latency and 1 second measured windows.

- [ ] **Step 5: Commit**

Run:

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkRunner.kt keel-samples/src/test/kotlin/com/keel/samples/aigateway/benchmark/BenchmarkRunnerTest.kt
git commit -m "feat: orchestrate ai relay benchmark runs"
```

Expected: commit succeeds.

## Task 10: CLI Entrypoint and Config Loading

**Files:**
- Create: `keel-samples/src/tools/kotlin/AiRelayBenchmarkTask.kt`
- Test manually via Gradle tasks.

- [ ] **Step 1: Add CLI entrypoint**

Create `AiRelayBenchmarkTask.kt`:

```kotlin
import com.keel.samples.aigateway.benchmark.BenchmarkConfig
import com.keel.samples.aigateway.benchmark.BenchmarkPhase
import com.keel.samples.aigateway.benchmark.BenchmarkRunner
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

fun main(args: Array<String>) {
    val parsed = args.mapNotNull { arg ->
        val index = arg.indexOf('=')
        if (arg.startsWith("--") && index > 2) arg.substring(2, index) to arg.substring(index + 1) else null
    }.toMap()
    val phase = BenchmarkPhase.parse(parsed["phase"])
    val outputDir = parsed["output"]?.let(Path::of)
        ?: Path.of("build", "reports", "ai-relay-benchmark", System.currentTimeMillis().toString())
    val config = parsed["config"]?.let { path ->
        Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkConfig>(Path.of(path).readText())
    } ?: if (phase == BenchmarkPhase.SMOKE) BenchmarkConfig.smoke() else BenchmarkConfig.full()

    Files.createDirectories(outputDir)
    println("AI relay benchmark phase=$phase output=$outputDir cases=${config.cases().size}")
    BenchmarkRunner(config, outputDir).run(phase)
    println("AI relay benchmark complete output=$outputDir")
}
```

- [ ] **Step 2: Run smoke task**

Run:

```bash
./gradlew :keel-samples:aiRelayBenchmarkSmoke -PbenchmarkOutputDir=build/reports/ai-relay-benchmark-smoke
```

Expected: task exits 0 and writes:

```text
keel-samples/build/reports/ai-relay-benchmark-smoke/summary.csv
keel-samples/build/reports/ai-relay-benchmark-smoke/summary.json
keel-samples/build/reports/ai-relay-benchmark-smoke/requests.jsonl
keel-samples/build/reports/ai-relay-benchmark-smoke/timeseries.jsonl
```

- [ ] **Step 3: Commit**

Run:

```bash
git add keel-samples/src/tools/kotlin/AiRelayBenchmarkTask.kt
git commit -m "feat: add ai relay benchmark cli"
```

Expected: commit succeeds.

## Task 11: Documentation

**Files:**
- Modify: `keel-samples/README.md`

- [ ] **Step 1: Add benchmark usage section**

Append a section to `keel-samples/README.md`:

```markdown
## AI Relay Benchmark

The AI relay benchmark runs real HTTP requests through the API-key relay path for Anthropic Messages and OpenAI Responses compatible APIs. It starts a local simulated provider, starts a local Keel sample SUT, creates a real API key, and records end-to-end latency/error/capacity reports.

Smoke run:

```bash
./gradlew :keel-samples:aiRelayBenchmarkSmoke -PbenchmarkOutputDir=build/reports/ai-relay-benchmark-smoke
```

Full heavy run:

```bash
./gradlew :keel-samples:aiRelayBenchmark -PbenchmarkPhase=full -PbenchmarkOutputDir=build/reports/ai-relay-benchmark-full
```

Reports:

- `summary.csv`: one row per measured step.
- `summary.json`: machine-readable step summaries.
- `requests.jsonl`: one line per request with latency, status, streaming metrics, and attribution data.
- `timeseries.jsonl`: per-window P99/time-series data when enabled.

The full matrix covers Anthropic Messages and OpenAI Responses, blocking and streaming requests, direct provider routing, alias pinned to a specific provider, alias to any attached providers with 2/3/5 providers, and provider generation durations of 1s, 3s, 5s, 10s, 20s, and 40s.
```

- [ ] **Step 2: Verify README formatting**

Run:

```bash
rg -n "AI Relay Benchmark|aiRelayBenchmarkSmoke|aiRelayBenchmark" keel-samples/README.md
```

Expected: all three terms appear.

- [ ] **Step 3: Commit**

Run:

```bash
git add keel-samples/README.md
git commit -m "docs: document ai relay benchmark"
```

Expected: commit succeeds.

## Task 12: Final Verification

**Files:**
- No new files unless fixes are required.

- [ ] **Step 1: Run focused benchmark test suite**

Run:

```bash
./gradlew :keel-samples:test --tests 'com.keel.samples.aigateway.benchmark.*'
```

Expected: PASS.

- [ ] **Step 2: Run smoke benchmark task**

Run:

```bash
./gradlew :keel-samples:aiRelayBenchmarkSmoke -PbenchmarkOutputDir=build/reports/ai-relay-benchmark-smoke-final
```

Expected: PASS and report files are created.

- [ ] **Step 3: Inspect smoke report**

Run:

```bash
ls -la keel-samples/build/reports/ai-relay-benchmark-smoke-final
```

Expected: directory contains `summary.csv`, `summary.json`, `requests.jsonl`, and `timeseries.jsonl`.

- [ ] **Step 4: Check git status**

Run:

```bash
git status --short
```

Expected: only unrelated pre-existing user changes remain, or clean if all benchmark changes are committed.

- [ ] **Step 5: Use finishing skill**

Announce:

```text
I'm using the finishing-a-development-branch skill to complete this work.
```

Then use `superpowers:finishing-a-development-branch` to decide integration next steps.
