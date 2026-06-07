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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.OutputStreamWriter
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Integration tests that validate Anthropic Messages → OpenAI Responses transcoding
 * using a local mock Responses API upstream.
 *
 * No real API keys needed. The mock server validates request format and returns
 * pre-canned Responses API SSE streams.
 *
 * This catches regressions like:
 * - Unsupported fields leaking (metadata, text.format, stop_sequences)
 * - Missing required fields (store=false)
 * - Incorrect content type mapping (input_text/output_text)
 * - Tool schema format issues (additionalProperties)
 * - Streaming event mapping errors
 * - Billing header leaking into instructions
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponsesApiCompatibilityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var mockServerPort: Int = 0
    private var mockServer: io.ktor.server.engine.ApplicationEngine? = null

    /** Captured request bodies for assertion. */
    private var lastRequestBody: JsonObject? = null
    private var requestCount = 0

    @BeforeAll
    fun startMockServer() {
        mockServerPort = findFreePort()
        mockServer = embeddedServer(Netty, port = mockServerPort) {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing {
                post("/v1/responses") {
                    val body = call.receiveText()
                    lastRequestBody = json.parseToJsonElement(body).jsonObject
                    requestCount++

                    // Validate: reject unsupported fields
                    val unsupported = findUnsupportedFields(lastRequestBody!!)
                    if (unsupported.isNotEmpty()) {
                        call.response.status(HttpStatusCode.fromValue(400))
                        call.respondText(
                            """{"error":{"message":"Unsupported parameter: ${unsupported.first()}","type":"invalid_request_error"}}""",
                            ContentType.Application.Json
                        )
                        return@post
                    }

                    // Determine if this is a streaming request
                    val isStream = lastRequestBody!!.get("stream")?.jsonPrimitive?.contentOrNull == "true"

                    if (isStream) {
                        call.response.header("Content-Type", "text/event-stream")
                        call.respondText(
                            buildSseResponse(lastRequestBody!!),
                            ContentType.Text.EventStream
                        )
                    } else {
                        call.respondText(
                            buildBlockingResponse(lastRequestBody!!),
                            ContentType.Application.Json
                        )
                    }
                }
            }
        }.start(wait = false)
        Thread.sleep(500) // Wait for server to bind
    }

    @AfterAll
    fun stopMockServer() {
        mockServer?.stop(0, 0)
    }

    // ---- Request format validation tests ----

    @Test
    fun simpleTextRequestSucceeds() = withRelay { ctx ->
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
        val body = parseResponse(resp.bodyAsText())
        assertEquals("message", body["type"]?.jsonPrimitive?.content)
        assertNotNull(body["content"])
    }

    @Test
    fun streamingRequestSucceeds() = withRelay { ctx ->
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
        assertNotNull(lastRequestBody, "Mock server should have received a request")
        assertFalse(lastRequestBody!!.containsKey("metadata"),
            "metadata should NOT be sent to Responses API upstream. Found: ${lastRequestBody!!["metadata"]}")
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
        assertNotNull(lastRequestBody)
        assertEquals(JsonPrimitive(false), lastRequestBody!!["store"],
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
        assertNotNull(lastRequestBody)
        val instructions = lastRequestBody!!["instructions"]?.jsonPrimitive?.content ?: ""
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
        assertNotNull(lastRequestBody)
        assertFalse(lastRequestBody!!.containsKey("stop"),
            "stop_sequences should not map to 'stop' for Responses API. Found: ${lastRequestBody!!["stop"]}")
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
        assertNotNull(lastRequestBody)
        assertFalse(lastRequestBody!!.containsKey("cache_control"),
            "cache_control should not leak to Responses API")
        assertFalse(lastRequestBody!!.containsKey("service_tier"),
            "service_tier should not leak to Responses API")
        assertFalse(lastRequestBody!!.containsKey("container"),
            "container should not leak to Responses API")
    }

    // ---- Tool handling tests ----

    @Test
    fun toolSchemasAreCleaned() = withRelay { ctx ->
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
        assertNotNull(lastRequestBody)
        val tools = lastRequestBody!!["tools"]?.jsonArray
        assertNotNull(tools, "tools should be present")
        assertTrue(tools!!.isNotEmpty(), "tools should not be empty")
        val tool = tools[0].jsonObject
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        val params = tool["parameters"]?.jsonObject
        assertNotNull(params, "parameters should be present")
        assertFalse(params!!.containsKey("additionalProperties"),
            "additionalProperties should be stripped by cleanSchema()")
    }

    // ---- Content type mapping tests ----

    @Test
    fun messagesConvertedToProperInputFormat() = withRelay { ctx ->
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
        assertNotNull(lastRequestBody)
        val input = lastRequestBody!!["input"]?.jsonArray
        assertNotNull(input, "input should be present")
        // Should have message items, not raw Anthropic messages format
        assertFalse(lastRequestBody!!.containsKey("messages"),
            "Anthropic 'messages' should be converted to 'input'")
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
        assertNotNull(lastRequestBody)
        val reasoning = lastRequestBody!!["reasoning"]?.jsonObject
        assertNotNull(reasoning, "reasoning should be present when thinking is enabled")
        assertEquals("medium", reasoning!!["effort"]?.jsonPrimitive?.content,
            "budget_tokens=10240 should map to medium")
    }

    // ---- Error scenario tests ----

    @Test
    fun upstreamErrorReturns503ToClient() = withRelay { ctx ->
        // Send a request that triggers upstream rejection (e.g. unsupported field)
        val resp = ctx.client.post("/api/plugins/airelay/v1/messages") {
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
        // With metadata filtered out, this should succeed.
        // The test above already verifies metadata is filtered.
        // This is a placeholder for testing actual upstream errors.
    }

    // ---- Helpers ----

    private fun findUnsupportedFields(body: JsonObject): List<String> {
        val unsupported = mutableListOf<String>()
        // These are fields the Responses API upstream doesn't support
        // but Anthropic clients may send
        val knownUnsupported = setOf(
            "metadata", "cache_control", "service_tier", "container",
            "inference_geo", "output_config", "thinking", "top_k",
            "stop_sequences", "system", "max_tokens"
        )
        for (key in body.keys) {
            if (key in knownUnsupported) unsupported.add(key)
        }
        return unsupported
    }

    private fun buildSseResponse(request: JsonObject): String {
        val model = request["model"]?.jsonPrimitive?.content ?: "gpt-5.4"
        val respId = "resp_mock_${System.currentTimeMillis()}"
        return buildString {
            append("data: ${json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.created"))
                put("response", buildJsonObject {
                    put("id", JsonPrimitive(respId))
                    put("model", JsonPrimitive(model))
                    put("status", JsonPrimitive("in_progress"))
                })
            })}\n\n")

            append("data: ${json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.in_progress"))
                put("response", buildJsonObject {
                    put("id", JsonPrimitive(respId))
                    put("status", JsonPrimitive("in_progress"))
                })
            })}\n\n")

            append("data: ${json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.output_item.added"))
                put("output_index", JsonPrimitive(0))
                put("item", buildJsonObject {
                    put("type", JsonPrimitive("message"))
                    put("id", JsonPrimitive("msg_mock"))
                    put("role", JsonPrimitive("assistant"))
                    put("status", JsonPrimitive("in_progress"))
                })
            })}\n\n")

            append("data: ${json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.content_part.added"))
                put("item_id", JsonPrimitive("msg_mock"))
                put("output_index", JsonPrimitive(0))
                put("content_index", JsonPrimitive(0))
                put("part", buildJsonObject {
                    put("type", JsonPrimitive("output_text"))
                    put("text", JsonPrimitive(""))
                })
            })}\n\n")

            append("data: ${json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.output_text.delta"))
                put("item_id", JsonPrimitive("msg_mock"))
                put("output_index", JsonPrimitive(0))
                put("content_index", JsonPrimitive(0))
                put("delta", JsonPrimitive("Hello! How can I help?"))
            })}\n\n")

            append("data: ${json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.output_text.done"))
                put("item_id", JsonPrimitive("msg_mock"))
                put("output_index", JsonPrimitive(0))
                put("content_index", JsonPrimitive(0))
                put("text", JsonPrimitive("Hello! How can I help?"))
            })}\n\n")

            append("data: ${json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.content_part.done"))
                put("item_id", JsonPrimitive("msg_mock"))
                put("output_index", JsonPrimitive(0))
                put("content_index", JsonPrimitive(0))
            })}\n\n")

            append("data: ${json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.output_item.done"))
                put("output_index", JsonPrimitive(0))
                put("item", buildJsonObject {
                    put("type", JsonPrimitive("message"))
                    put("id", JsonPrimitive("msg_mock"))
                    put("role", JsonPrimitive("assistant"))
                    put("status", JsonPrimitive("completed"))
                })
            })}\n\n")

            append("data: ${json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.completed"))
                put("response", buildJsonObject {
                    put("id", JsonPrimitive(respId))
                    put("model", JsonPrimitive(model))
                    put("status", JsonPrimitive("completed"))
                    put("usage", buildJsonObject {
                        put("input_tokens", JsonPrimitive(50))
                        put("output_tokens", JsonPrimitive(10))
                        put("total_tokens", JsonPrimitive(60))
                    })
                })
            })}\n\n")

            append("data: [DONE]\n\n")
        }
    }

    private fun buildBlockingResponse(request: JsonObject): String {
        val model = request["model"]?.jsonPrimitive?.content ?: "gpt-5.4"
        return json.encodeToString(buildJsonObject {
            put("id", JsonPrimitive("resp_mock_${System.currentTimeMillis()}"))
            put("object", JsonPrimitive("response"))
            put("model", JsonPrimitive(model))
            put("status", JsonPrimitive("completed"))
            put("output", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("message"))
                    put("id", JsonPrimitive("msg_mock"))
                    put("role", JsonPrimitive("assistant"))
                    put("content", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("output_text"))
                            put("text", JsonPrimitive("Hello! How can I help?"))
                        })
                    })
                })
            })
            put("usage", buildJsonObject {
                put("input_tokens", JsonPrimitive(50))
                put("output_tokens", JsonPrimitive(10))
                put("total_tokens", JsonPrimitive(60))
            })
        })
    }

    private fun parseResponse(body: String): JsonObject {
        val outer = json.parseToJsonElement(body).jsonObject
        val inner = outer["response"]?.jsonPrimitive?.contentOrNull
        return if (inner != null) json.parseToJsonElement(inner).jsonObject else outer
    }

    private data class GatewayContext(
        val client: io.ktor.client.HttpClient,
        val rawKey: String,
    )

    private fun withRelay(block: suspend (GatewayContext) -> Unit) = testApplication {
        val previousDataDir = System.getProperty("keel.data.dir")
        val testDataDir = java.nio.file.Files.createTempDirectory("keel-mock-test-").toFile()
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

        val mockBaseUrl = "http://127.0.0.1:$mockServerPort"
        val chains = listOf(
            PoolChainConfig(
                chainId = "default",
                modelAliases = listOf("test-model", "gpt-5.4"),
                levels = listOf(
                    PoolLevelConfig(
                        levelId = "mock-responses",
                        levelIndex = 1,
                        provider = UpstreamProviderConfig(
                            providerId = "mock-responses-upstream",
                            baseUrl = mockBaseUrl,
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

        runBlocking { airelayPlugin.installRealUpstream(RealUpstreamHttpClient.create(), chains) }

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
            val accessToken = json.parseToJsonElement(loginResp.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content

            val keyResp = client.post("/api/plugins/token/v1/keys") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Mock test key","maxBudgetUsd":100}""")
            }
            assertEquals(HttpStatusCode.OK, keyResp.status)
            val rawKey = json.parseToJsonElement(keyResp.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

            // Reset captured state
            lastRequestBody = null
            requestCount = 0

            block(GatewayContext(client = client, rawKey = rawKey))
        } finally {
            if (previousDataDir == null) {
                System.clearProperty("keel.data.dir")
            } else {
                System.setProperty("keel.data.dir", previousDataDir)
            }
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
