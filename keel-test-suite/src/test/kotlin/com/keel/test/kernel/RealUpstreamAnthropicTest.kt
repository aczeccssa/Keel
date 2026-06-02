package com.keel.test.kernel

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.upstream.RealUpstreamHttpClient
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.token.TokenPlugin
import com.keel.samples.observability.ObservabilityPlugin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Real-credential validation against a local Anthropic-compatible upstream.
 *
 * The test account pool is configured as:
 *   - baseUrl: http://127.0.0.1:15721
 *   - apiKey:  ANTHROPIC_AUTH_TOKEN  (read from env)
 *   - model:   any Anthropic model exposed by the local server
 *
 * Two paths are covered:
 *   1. Direct Anthropic call (no transcoding) at `/v1/messages`.
 *   2. OpenAI Responses input -> Anthropic upstream + Anthropic output (full IR transcode).
 *
 * If the local upstream is not reachable (env not set, port closed) the test is skipped
 * rather than failing, so CI without the local account pool still goes green.
 */
class RealUpstreamAnthropicTest {

    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun teardown() {
        OpenApiRegistry.clear()
        runCatching { stopKoin() }
    }

    @Test
    fun directAnthropicMessagesCall() = withRealGateway(baseUrl = "http://127.0.0.1:15721") { ctx ->
        assumeTrue(
            ctx.localUpstreamReachable,
            "Skipping: local Anthropic-compatible upstream at http://127.0.0.1:15721 is not reachable. " +
                "Start your local account-pool server and re-run."
        )

        val resp = ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "model": "${ctx.model}",
                  "max_tokens": 64,
                  "messages": [{"role":"user","content":"Reply with the single word: pong"}]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status, "Direct Anthropic call failed: ${resp.bodyAsText()}")
        val body = unwrapRelayResponse(resp.bodyAsText()).jsonObject
        assertEquals("message", body["type"]!!.jsonPrimitive.content)
        val content = body["content"]!!.jsonArray
        assertTrue(content.isNotEmpty(), "Anthropic response must contain at least one content block")
    }

    @Test
    fun openAiResponsesToAnthropicConversion() = withRealGateway(baseUrl = "http://127.0.0.1:15721") { ctx ->
        assumeTrue(
            ctx.localUpstreamReachable,
            "Skipping: local Anthropic-compatible upstream at http://127.0.0.1:15721 is not reachable."
        )

        // Send an OpenAI Responses request; the gateway must transcode it to Anthropic
        // Messages on the wire and return an OpenAI Responses envelope to the client.
        val resp = ctx.client.post("/api/plugins/airelay/v1/responses") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "model": "${ctx.model}",
                  "input": "Reply with the single word: pong",
                  "store": false,
                  "max_output_tokens": 64
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status, "Responses->Anthropic failed: ${resp.bodyAsText()}")
        val body = unwrapRelayResponse(resp.bodyAsText()).jsonObject
        assertEquals("response", body["object"]!!.jsonPrimitive.content, "Client must receive Responses envelope")
        assertTrue((body["output"]!!.jsonArray).isNotEmpty(), "Responses output must be non-empty")
        // The X-Upstream-Protocol header should be Anthropic (proving the conversion happened).
        val upstreamProto = resp.headers["X-Upstream-Protocol"]
        assertNotNull(upstreamProto, "Server must announce the upstream protocol it dispatched to")
        assertTrue(
            upstreamProto.contains("ANTHROPIC", ignoreCase = true),
            "Expected Anthropic upstream for model=${ctx.model}, got '$upstreamProto'"
        )
    }

    private fun unwrapRelayResponse(body: String): kotlinx.serialization.json.JsonElement {
        val outer = json.parseToJsonElement(body).jsonObject
        val inner = outer["response"]!!.jsonPrimitive.content
        return json.parseToJsonElement(inner)
    }

    private class GatewayContext(
        val client: io.ktor.client.HttpClient,
        val rawKey: String,
        val model: String,
        val localUpstreamReachable: Boolean
    )

    private fun withRealGateway(baseUrl: String, block: suspend (GatewayContext) -> Unit) = testApplication {
        OpenApiRegistry.clear()
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        val accountPlugin = AccountPlugin()
        val tokenPlugin = TokenPlugin()
        val riskPlugin = RiskControlPlugin()
        val airelayPlugin = AIRelayPlugin()
        val observability = ObservabilityPlugin()
        manager.registerPlugin(accountPlugin)
        manager.registerPlugin(tokenPlugin)
        manager.registerPlugin(riskPlugin)
        manager.registerPlugin(airelayPlugin)
        manager.registerPlugin(observability)

        // Override the default chain to point at the local account pool.
        val apiKey = System.getenv("ANTHROPIC_AUTH_TOKEN")
        val localUpstreamReachable = apiKey != null && probeUpstream(baseUrl)
        val chains = listOf(
            PoolChainConfig(
                chainId = "anthropic-local-chain",
                modelAliases = listOf("claude-local", "claude-sonnet-4-20250514"),
                levels = listOf(
                    PoolLevelConfig(
                        levelId = "l1-anthropic-local",
                        levelIndex = 1,
                        provider = UpstreamProviderConfig(
                            providerId = "anthropic-local",
                            baseUrl = baseUrl,
                            protocol = WireProtocol.ANTHROPIC_MESSAGES,
                            timeoutMs = 60_000
                        ),
                        keys = listOf(
                            PooledKeyConfig(
                                keyId = "local-anthropic-1",
                                apiKey = apiKey ?: "sk-placeholder",
                                maxConcurrency = 8
                            )
                        )
                    )
                )
            )
        )
        // Replace the upstream client in the airelay plugin with a real one.
        runBlocking {
            airelayPlugin.installRealUpstream(RealUpstreamHttpClient.create(), chains)
        }

        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing { manager.mountRoutes(this) }
            runBlocking {
                manager.startPlugin("account")
                manager.startPlugin("token")
                manager.startPlugin("riskcontrol")
                manager.startPlugin("airelay")
                manager.startPlugin("observability")
            }
        }

        val rawKey = "sk-keel-demo-user"
        block(
            GatewayContext(
                client = client,
                rawKey = rawKey,
                model = "claude-sonnet-4-20250514",
                localUpstreamReachable = localUpstreamReachable
            )
        )
    }

    private suspend fun probeUpstream(baseUrl: String): Boolean = try {
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 3_000
                connectTimeoutMillis = 2_000
            }
            expectSuccess = false
        }
        client.use {
            val resp = it.get("$baseUrl/v1/models")
            resp.status.value in 200..599
        }
    } catch (_: Exception) {
        false
    }
}

private suspend fun io.ktor.client.HttpClient.get(url: String): io.ktor.client.statement.HttpResponse =
    io.ktor.client.request.get(url)

private fun AIRelayPlugin.installRealUpstream(
    client: RealUpstreamHttpClient,
    @Suppress("UNUSED_PARAMETER") chains: List<PoolChainConfig>
) {
    this.upstreamClient = client
}
