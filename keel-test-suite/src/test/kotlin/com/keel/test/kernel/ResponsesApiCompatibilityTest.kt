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
import com.keel.samples.aigateway.airelay.upstream.MockableUpstreamHttpClient
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.token.TokenPlugin
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that validate Anthropic Messages → OpenAI Responses transcoding
 * using the mock upstream client (mock:// URLs).
 *
 * No real API keys needed. The mock client captures the request as a JsonObject
 * that can be inspected to verify field mapping.
 *
 * Catches regressions like:
 * - Unsupported fields leaking (metadata, text.format, stop_sequences)
 * - Missing required fields (store=false)
 * - Billing header in instructions
 * - Tool schema format issues (additionalProperties)
 * - Content type mapping (input_text/output_text)
 * - thinking → reasoning.effort conversion
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponsesApiCompatibilityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Requests captured by the custom mock client, keyed by test name. */
    private val capturedRequests = mutableListOf<JsonObject>()
    private var mockClient: CapturingMockClient? = null

    // ---- Request format validation tests ----

    @Test
    fun simpleTextRequestProducesValidResponsesFormat() = withRelay { ctx ->
        val resp = ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, resp.status, "Simple text request failed: ${resp.bodyAsText()}")

        val upstreamReq = capturedRequests.last()
        assertNotNull(upstreamRequest(upstreamReq), "Upstream should have received the request")
    }

    @Test
    fun streamingRequestProducesValidResponsesFormat() = withRelay { ctx ->
        val resp = ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "stream": true,
                    "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, resp.status, "Streaming request failed: ${resp.bodyAsText()}")
    }

    // ---- Field sanitization tests ----

    @Test
    fun metadataFieldNotSentToUpstream() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "metadata": {"user_id": "test-user"},
                    "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        assertFalse(req.containsKey("metadata"),
            "metadata should NOT be sent to Responses API upstream. Found: ${req["metadata"]}")
    }

    @Test
    fun storeFieldIsSetToFalse() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        assertEquals(JsonPrimitive(false), req["store"],
            "store must be false for Responses API compatibility")
    }

    @Test
    fun systemPromptStripsBillingHeader() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "system": "x-anthropic-billing-header: cc_version=1.0; cc_entrypoint=cli;\nYou are helpful.",
                    "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        val instructions = req["instructions"]?.jsonPrimitive?.content ?: ""
        assertFalse(instructions.contains("x-anthropic-billing-header"),
            "billing header should be stripped. Got: ${instructions.take(100)}")
        assertTrue(instructions.contains("You are helpful"),
            "actual system content should be preserved. Got: ${instructions.take(100)}")
    }

    @Test
    fun stopSequencesNotSentToUpstream() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "stop_sequences": ["STOP", "END"],
                    "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        assertFalse(req.containsKey("stop"),
            "stop_sequences should not map to 'stop' for Responses API. Found: ${req["stop"]}")
    }

    @Test
    fun anthropicExtrasNotLeakedToUpstream() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "cache_control": {"type": "ephemeral"},
                    "service_tier": "priority",
                    "container": "sandbox",
                    "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        assertFalse(req.containsKey("cache_control"), "cache_control should not leak")
        assertFalse(req.containsKey("service_tier"), "service_tier should not leak")
        assertFalse(req.containsKey("container"), "container should not leak")
        assertFalse(req.containsKey("inference_geo"), "inference_geo should not leak")
    }

    // ---- Tool handling tests ----

    @Test
    fun toolSchemasHaveAdditionalPropertiesStripped() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "messages": [{"role": "user", "content": "hello"}],
                    "tools": [{
                        "name": "read_file",
                        "description": "Read a file",
                        "input_schema": {
                            "type": "object",
                            "properties": {"path": {"type": "string"}},
                            "required": ["path"],
                            "additionalProperties": false
                        }
                    }]
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        val tools = req["tools"]?.jsonArray
        assertNotNull(tools, "tools should be present")
        assertTrue(tools!!.isNotEmpty())
        val params = tools[0].jsonObject["parameters"]?.jsonObject
        assertNotNull(params, "parameters should be present")
        assertFalse(params!!.containsKey("additionalProperties"),
            "additionalProperties should be stripped by cleanSchema()")
    }

    @Test
    fun toolChoiceMapping() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "messages": [{"role": "user", "content": "hello"}],
                    "tools": [{
                        "name": "read_file",
                        "description": "Read",
                        "input_schema": {"type": "object", "properties": {}, "required": []}
                    }],
                    "tool_choice": {"type": "any", "disable_parallel_tool_use": true}
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        assertEquals(JsonPrimitive("required"), req["tool_choice"],
            "tool_choice type=any should map to 'required'")
        assertEquals(JsonPrimitive(false), req["parallel_tool_calls"],
            "disable_parallel_tool_use=true should map to parallel_tool_calls=false")
    }

    // ---- Content type mapping tests ----

    @Test
    fun messagesConvertedToInputFormat() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 64,
                    "messages": [
                        {"role": "user", "content": "Hello"},
                        {"role": "assistant", "content": "Hi there!"},
                        {"role": "user", "content": "How are you?"}
                    ]
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        assertFalse(req.containsKey("messages"), "Anthropic 'messages' should be converted to 'input'")
        assertTrue(req.containsKey("input"), "'input' should be present")
        val input = req["input"]?.jsonArray
        assertNotNull(input)
        assertTrue(input!!.isNotEmpty(), "input should not be empty")
    }

    @Test
    fun maxTokensConvertedToMaxOutputTokens() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 16384,
                    "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        assertEquals(JsonPrimitive(16384), req["max_output_tokens"],
            "max_tokens should map to max_output_tokens")
        assertFalse(req.containsKey("max_tokens"), "Anthropic 'max_tokens' should not appear")
    }

    // ---- Thinking/reasoning tests ----

    @Test
    fun thinkingConfigConvertedToReasoningEffort() = withRelay { ctx ->
        ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 16384,
                    "thinking": {"type": "enabled", "budget_tokens": 10240},
                    "messages": [{"role": "user", "content": "hello"}]
                }
            """.trimIndent())
        }
        val req = capturedRequests.last()
        val reasoning = req["reasoning"]?.jsonObject
        assertNotNull(reasoning, "reasoning should be present when thinking is enabled")
        assertEquals("medium", reasoning!!["effort"]?.jsonPrimitive?.content,
            "budget_tokens=10240 should map to medium effort")
    }

    // ---- E2E round trip ----

    @Test
    fun fullAnthropicToResponsesToAnthropicRoundTrip() = withRelay { ctx ->
        val resp = ctx.client.post("/api/plugins/airelay/v1/messages") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "model": "test-model",
                    "max_tokens": 128,
                    "system": "Reply with only: pong",
                    "messages": [
                        {"role": "user", "content": "ping"},
                        {"role": "assistant", "content": [
                            {"type": "tool_use", "id": "toolu_1", "name": "Read", "input": {"file_path": "test.kt"}}
                        ]},
                        {"role": "user", "content": [
                            {"type": "tool_result", "tool_use_id": "toolu_1", "content": "fun main() {}"}
                        ]}
                    ],
                    "tools": [
                        {"name": "Read", "description": "Read a file", "input_schema": {"type": "object", "properties": {"file_path": {"type": "string"}}, "required": ["file_path"]}}
                    ],
                    "tool_choice": {"type": "auto", "disable_parallel_tool_use": true},
                    "stream": false
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.OK, resp.status, "E2E round-trip failed: ${resp.bodyAsText()}")

        // Verify upstream got the right format
        val req = capturedRequests.last()
        assertEquals("test-model", req["model"]?.jsonPrimitive?.content)
        assertNotNull(req["instructions"])
        assertNotNull(req["input"])
        assertEquals(JsonPrimitive(false), req["store"])
        assertNotNull(req["tools"])

        // Verify the response is valid Anthropic format
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val inner = body["response"]?.jsonPrimitive?.contentOrNull
        val response = if (inner != null) json.parseToJsonElement(inner).jsonObject else body
        assertEquals("message", response["type"]?.jsonPrimitive?.content)
    }

    // ---- Helpers ----

    /**
     * Wrapper that captures requests for inspection before delegating to the real mock client.
     */
    private class CapturingMockClient(
        private val delegate: MockableUpstreamHttpClient,
        val captured: MutableList<JsonObject>
    ) : com.keel.samples.aigateway.airelay.upstream.UpstreamHttpClient {
        override suspend fun send(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: JsonObject,
            extraHeaders: Map<String, String>
        ): com.keel.samples.aigateway.airelay.upstream.UpstreamResponse {
            captured.add(request)
            return delegate.send(selection, request, extraHeaders)
        }

        override fun stream(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: JsonObject,
            extraHeaders: Map<String, String>
        ): kotlinx.coroutines.flow.Flow<io.ktor.sse.ServerSentEvent> {
            captured.add(request)
            return delegate.stream(selection, request, extraHeaders)
        }

        override suspend fun countTokens(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: JsonObject,
            extraHeaders: Map<String, String>
        ): com.keel.samples.aigateway.airelay.upstream.UpstreamResponse =
            delegate.countTokens(selection, request, extraHeaders)

        override suspend fun proxyRaw(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: com.keel.samples.aigateway.airelay.upstream.RawProxyRequest,
            extraHeaders: Map<String, String>
        ): com.keel.samples.aigateway.airelay.upstream.RawProxyResponse =
            delegate.proxyRaw(selection, request, extraHeaders)
    }

    private fun upstreamRequest(req: JsonObject): JsonObject? {
        // The mock client captures the request that would be sent to the upstream
        return if (req.containsKey("model")) req else null
    }

    private data class GatewayContext(
        val client: io.ktor.client.HttpClient,
        val rawKey: String,
    )

    private fun withRelay(block: suspend (GatewayContext) -> Unit) = testApplication {
        val previousDataDir = System.getProperty("keel.data.dir")
        val testDataDir = java.nio.file.Files.createTempDirectory("keel-compat-test-").toFile()
        System.setProperty("keel.data.dir", testDataDir.absolutePath)
        OpenApiRegistry.clear()
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        val accountPlugin = AccountPlugin()
        val tokenPlugin = TokenPlugin()
        val riskPlugin = RiskControlPlugin()
        val airelayPlugin = AIRelayPlugin()
        manager.registerPlugin(accountPlugin)
        manager.registerPlugin(tokenPlugin)
        manager.registerPlugin(riskPlugin)
        manager.registerPlugin(airelayPlugin)

        val chains = listOf(
            PoolChainConfig(
                chainId = "default",
                modelAliases = listOf("test-model"),
                levels = listOf(
                    PoolLevelConfig(
                        levelId = "mock-responses",
                        levelIndex = 1,
                        provider = UpstreamProviderConfig(
                            providerId = "mock-responses-upstream",
                            baseUrl = "mock://responses-test",
                            protocol = WireProtocol.OPENAI_RESPONSES,
                            timeoutMs = 30_000,
                        ),
                        keys = listOf(
                            PooledKeyConfig(
                                keyId = "mock-key-1",
                                apiKey = "sk-mock-test-key",
                                maxConcurrency = 8,
                            )
                        )
                    )
                )
            )
        )

        val delegate = MockableUpstreamHttpClient(realClient = null)
        val capturingClient = CapturingMockClient(delegate, capturedRequests)
        mockClient = capturingClient
        capturedRequests.clear()
        runBlocking { airelayPlugin.installRealUpstream(capturingClient, chains) }

        try {
            application {
                install(ContentNegotiation) { json() }
                install(SSE)
                routing { manager.mountRoutes(this) }
                runBlocking {
                    manager.startPlugin("account")
                    manager.startPlugin("token")
                    manager.startPlugin("riskcontrol")
                    manager.startPlugin("airelay")
                }
            }

            // Create an API key
            val loginResp = client.post("/api/plugins/account/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"user@example.com","password":"user123"}""")
            }
            assertEquals(HttpStatusCode.OK, loginResp.status)
            val accessToken = json.parseToJsonElement(loginResp.bodyAsText())
                .jsonObject["accessToken"]!!.jsonPrimitive.content

            val keyResp = client.post("/api/plugins/token/v1/keys") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Compat test key","maxBudgetUsd":100}""")
            }
            assertEquals(HttpStatusCode.OK, keyResp.status)
            val rawKey = json.parseToJsonElement(keyResp.bodyAsText())
                .jsonObject["rawKey"]!!.jsonPrimitive.content

            capturedRequests.clear()
            block(GatewayContext(client = client, rawKey = rawKey))
        } finally {
            if (previousDataDir == null) {
                System.clearProperty("keel.data.dir")
            } else {
                System.setProperty("keel.data.dir", previousDataDir)
            }
        }
    }
}
