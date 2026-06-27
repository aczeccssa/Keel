package com.keel.test.kernel

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
import com.keel.samples.aigateway.airelay.AliasRouteConfig
import com.keel.samples.aigateway.airelay.AliasRoutingPolicy
import com.keel.samples.aigateway.airelay.AliasTargetConfig
import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.upstream.MockFailure
import com.keel.samples.aigateway.airelay.upstream.OpenedUpstreamStream
import com.keel.samples.aigateway.airelay.upstream.RawProxyRequest
import com.keel.samples.aigateway.airelay.upstream.RawProxyResponse
import com.keel.samples.aigateway.airelay.upstream.UpstreamHttpClient
import com.keel.samples.aigateway.airelay.upstream.UpstreamResponse
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.token.TokenPlugin
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.sse.ServerSentEvent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class AiGatewayPluginIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun teardown() {
        OpenApiRegistry.clear()
        runCatching { stopKoin() }
    }

    private fun setupApp(block: suspend TestContext.() -> Unit) = testApplication {
        val previousDataDir = System.getProperty("keel.data.dir")
        val testDataDir = Files.createTempDirectory("keel-gateway-integration-").toFile()
        System.setProperty("keel.data.dir", testDataDir.absolutePath)
        OpenApiRegistry.clear()
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(AccountPlugin())
        manager.registerPlugin(TokenPlugin())
        manager.registerPlugin(RiskControlPlugin())
        val relayPlugin = AIRelayPlugin()
        manager.registerPlugin(relayPlugin)

        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing { manager.mountRoutes(this) }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("account")
                manager.startPlugin("token")
                manager.startPlugin("riskcontrol")
                manager.startPlugin("airelay")
            }
        }

        try {
            val loginResponse = client.post("/api/plugins/account/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"user@example.com","password":"user123"}""")
            }
            assertEquals(HttpStatusCode.OK, loginResponse.status)
            val accessToken = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content

            val keyResponse = client.post("/api/plugins/token/v1/keys") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Matrix test key","maxBudgetUsd":100}""")
            }
            assertEquals(HttpStatusCode.OK, keyResponse.status)
            val keyBody = json.parseToJsonElement(keyResponse.bodyAsText()).jsonObject
            val keyId = keyBody["key"]!!.jsonObject["keyId"]!!.jsonPrimitive.content
            val rawKey = keyBody["rawKey"]!!.jsonPrimitive.content
            assertTrue(rawKey.startsWith("sk-keel-"))

            with(TestContext(client, accessToken, rawKey, keyId, json, relayPlugin)) {
                block()
            }
        } finally {
            if (previousDataDir == null) {
                System.clearProperty("keel.data.dir")
            } else {
                System.setProperty("keel.data.dir", previousDataDir)
            }
        }
    }

    private fun parseGatewayBody(body: String): kotlinx.serialization.json.JsonElement {
        val outer = json.parseToJsonElement(body)
        val wrapped = outer.jsonObject["response"]?.jsonPrimitive?.contentOrNull
        return if (wrapped != null) json.parseToJsonElement(wrapped) else outer
    }

    private class TestContext(
        val client: io.ktor.client.HttpClient,
        val accessToken: String,
        val rawKey: String,
        val keyId: String,
        private val json: Json,
        val relayPlugin: AIRelayPlugin,
    ) {
        suspend fun readUsageSnapshot(path: String): kotlinx.serialization.json.JsonObject {
            return client.prepareGet(path).execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.headers["Content-Type"]?.startsWith("text/event-stream") == true)

                val channel = response.bodyAsChannel()
                var payload: String? = null
                withTimeout(2_000) {
                    while (payload == null) {
                        val line = channel.readUTF8Line() ?: break
                        if (line.startsWith("data: ")) {
                            payload = line.removePrefix("data: ").trim()
                        }
                    }
                }
                json.parseToJsonElement(requireNotNull(payload) { "expected one SSE payload" }).jsonObject
            }
        }
    }

    private class RecordingAnthropicUpstream(
        private val streamEvents: List<ServerSentEvent> = emptyList(),
        private val rawProxyBody: ByteArray = """{"ok":true}""".toByteArray(),
    ) : UpstreamHttpClient {
        val seenExtraHeaders = mutableListOf<Map<String, String>>()
        val seenStreamRequests = mutableListOf<JsonObject>()
        val seenRawProxyRequests = mutableListOf<RawProxyRequest>()

        override suspend fun send(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: JsonObject,
            extraHeaders: Map<String, String>,
        ): UpstreamResponse {
            seenExtraHeaders += extraHeaders.toMap()
            return UpstreamResponse(
                status = 200,
                body = buildJsonObject {
                    put("id", JsonPrimitive("msg-recorded"))
                    put("type", JsonPrimitive("message"))
                    put("role", JsonPrimitive("assistant"))
                    put("model", JsonPrimitive(request["model"]?.jsonPrimitive?.content ?: "claude-only"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive("recorded"))
                        })
                    })
                    put("stop_reason", JsonPrimitive("end_turn"))
                    put("usage", buildJsonObject {
                        put("input_tokens", JsonPrimitive(3))
                        put("output_tokens", JsonPrimitive(2))
                    })
                },
            )
        }

        override suspend fun openStream(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: JsonObject,
            extraHeaders: Map<String, String>,
        ): OpenedUpstreamStream {
            seenExtraHeaders += extraHeaders.toMap()
            seenStreamRequests += request
            return OpenedUpstreamStream(
                status = 200,
                events = streamEvents.asFlow(),
            )
        }

        override suspend fun countTokens(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: JsonObject,
            extraHeaders: Map<String, String>,
        ): UpstreamResponse {
            seenExtraHeaders += extraHeaders.toMap()
            return UpstreamResponse(
                status = 200,
                body = buildJsonObject { put("input_tokens", JsonPrimitive(7)) },
            )
        }

        override suspend fun proxyRaw(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: RawProxyRequest,
            extraHeaders: Map<String, String>,
        ): RawProxyResponse {
            seenExtraHeaders += extraHeaders.toMap()
            seenRawProxyRequests += request
            return RawProxyResponse(
                status = 200,
                headers = mapOf("X-Test-Upstream" to listOf("anthropic")),
                contentType = "application/json",
                body = rawProxyBody,
            )
        }
    }

    @Test
    fun loginCreateKeyAndChatCompletionsRoundTrip() = setupApp {
        val chatResponse = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "model": "gpt-4o-mini",
                  "messages": [{"role":"user","content":"Hello"}]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, chatResponse.status)
        val chatBody = parseGatewayBody(chatResponse.bodyAsText()).jsonObject
        val content = chatBody["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("Mock response"))
        assertTrue((chatResponse.headers["X-Upstream-Protocol"] ?: "").isNotBlank())
        assertTrue((chatResponse.headers["X-Cost-USD"] ?: "0.0").toDouble() >= 0.0)
    }

    @Test
    fun dashboardStatsExposeCanonicalTrendFieldsAlongsideCompatibilityAliases() = setupApp {
        val chatResponse = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "model": "gpt-4o-mini",
                  "messages": [{"role":"user","content":"Trend contract"}]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, chatResponse.status)

        val response = client.get("/api/plugins/airelay/admin/stats/dashboard?window=7d") {
            header("Authorization", "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val trends = json.parseToJsonElement(response.bodyAsText()).jsonObject["trends"]!!.jsonObject
        assertEquals("1d", trends["bucketGranularity"]!!.jsonPrimitive.content)
        assertEquals("day", trends["bucketLabelMode"]!!.jsonPrimitive.content)
        assertTrue(trends["requests"]!!.jsonArray.isNotEmpty())
        assertTrue(trends["tokens"]!!.jsonArray.isNotEmpty())
        assertTrue(trends["latency"]!!.jsonArray.isNotEmpty())
        assertTrue(trends["requestsByHour"]!!.jsonArray.isNotEmpty())
        assertTrue(trends["tokensByHour"]!!.jsonArray.isNotEmpty())
        assertTrue(trends["latencyByHour"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun allNineProtocolCombinationsSucceed() = setupApp {
        data class ProtoCombo(
            val name: String,
            val endpoint: String,
            val model: String,
            val bodyBuilder: () -> String,
            val validate: (kotlinx.serialization.json.JsonElement) -> Unit
        )

        fun chatBody(model: String) = """{"model":"$model","messages":[{"role":"user","content":"Hi"}]}"""
        fun responsesBody(model: String) = """{"model":"$model","input":"Hi","store":false}"""
        fun anthropicBody(model: String) = """{"model":"$model","messages":[{"role":"user","content":"Hi"}],"max_tokens":64}"""

        val combos = listOf(
            ProtoCombo(
                "Chat→Chat", "/v1/chat/completions", "gpt-chat-only",
                { chatBody("gpt-chat-only") },
                { body -> assertTrue(body.jsonObject["choices"]!!.jsonArray.isNotEmpty()) }
            ),
            ProtoCombo(
                "Chat→Responses", "/v1/chat/completions", "gpt-responses-only",
                { chatBody("gpt-responses-only") },
                { body -> assertTrue(body.jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content.isNotBlank()) }
            ),
            ProtoCombo(
                "Chat→Anthropic", "/v1/chat/completions", "claude-only",
                { chatBody("claude-only") },
                { body -> assertTrue(body.jsonObject["choices"]!!.jsonArray.isNotEmpty()) }
            ),
            ProtoCombo(
                "Responses→Chat", "/v1/responses", "gpt-chat-only",
                { responsesBody("gpt-chat-only") },
                { body -> assertEquals("response", body.jsonObject["object"]!!.jsonPrimitive.content) }
            ),
            ProtoCombo(
                "Responses→Responses", "/v1/responses", "gpt-responses-only",
                { responsesBody("gpt-responses-only") },
                { body -> assertEquals("response", body.jsonObject["object"]!!.jsonPrimitive.content) }
            ),
            ProtoCombo(
                "Responses→Anthropic", "/v1/responses", "claude-only",
                { responsesBody("claude-only") },
                { body -> assertEquals("response", body.jsonObject["object"]!!.jsonPrimitive.content) }
            ),
            ProtoCombo(
                "Anthropic→Chat", "/v1/messages", "gpt-chat-only",
                { anthropicBody("gpt-chat-only") },
                { body -> assertEquals("message", body.jsonObject["type"]!!.jsonPrimitive.content) }
            ),
            ProtoCombo(
                "Anthropic→Responses", "/v1/messages", "gpt-responses-only",
                { anthropicBody("gpt-responses-only") },
                { body -> assertEquals("message", body.jsonObject["type"]!!.jsonPrimitive.content) }
            ),
            ProtoCombo(
                "Anthropic→Anthropic", "/v1/messages", "claude-only",
                { anthropicBody("claude-only") },
                { body -> assertEquals("message", body.jsonObject["type"]!!.jsonPrimitive.content) }
            )
        )

        combos.forEach { combo ->
            val response = client.post("/api/plugins/airelay${combo.endpoint}") {
                header("Authorization", "Bearer $rawKey")
                contentType(ContentType.Application.Json)
                setBody(combo.bodyBuilder())
            }
            assertEquals(HttpStatusCode.OK, response.status, "Expected 200 for ${combo.name}, got ${response.status}: ${response.bodyAsText()}")
            val body = parseGatewayBody(response.bodyAsText())
            combo.validate(body)
        }
    }

    @Test
    fun responsesAcceptsPreviousResponseIdForSameProtocolPassThrough() = setupApp {
        val scopedKeyResponse = client.post("/api/plugins/token/v1/keys") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Responses key","groupId":"openai-responses-chain","maxBudgetUsd":100}""")
        }
        assertEquals(HttpStatusCode.OK, scopedKeyResponse.status)
        val scopedRawKey = json.parseToJsonElement(scopedKeyResponse.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

        val response = client.post("/api/plugins/airelay/v1/responses") {
            header("Authorization", "Bearer $scopedRawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-responses-only","input":"Hi","previous_response_id":"resp-123","store":false}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = parseGatewayBody(response.bodyAsText()).jsonObject
        assertEquals("response", body["object"]!!.jsonPrimitive.content)
    }

    @Test
    fun usageSseStreamIsMountedOnAirelayPlugin() = setupApp {
        val relay = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}""")
        }
        assertEquals(HttpStatusCode.OK, relay.status, relay.bodyAsText())

        val snapshot = readUsageSnapshot("/api/plugins/airelay/usage/stream?intervalMs=1000")
        assertTrue(snapshot["totalRequests"]!!.jsonPrimitive.content.toLong() >= 1L)
        assertTrue(snapshot["topModels"]!!.jsonArray.isNotEmpty(), "expected model summary in SSE payload")
        assertTrue(snapshot["recentRequests"]!!.jsonArray.isNotEmpty(), "expected recent requests in SSE payload")
    }

    @Test
    fun modelsEndpointAndRoutingHonorAliasOnlyGroupExposure() = setupApp {
        val groupResp = client.post("/api/plugins/airelay/admin/groups") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "groupId":"premium",
                  "name":"Premium",
                  "enabled":true,
                  "exposureMode":"ALIAS_ONLY",
                  "aliasRoutes":[
                    {"aliasName":"smart-claude","targetModels":["claude-a","claude-b"],"enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, groupResp.status)

        val channelResp = client.post("/api/plugins/airelay/admin/channels") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name":"Premium Chat",
                  "protocol":"OPENAI_CHAT",
                  "baseUrl":"mock://premium-chat",
                  "apiKey":"mock-key",
                  "groupId":"premium",
                  "priority":50,
                  "weight":100,
                  "models":[
                    {"publicModelName":"claude-b","upstreamModelName":"claude-b","enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, channelResp.status)

        val premiumKeyResponse = client.post("/api/plugins/token/v1/keys") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Premium key","groupId":"premium","maxBudgetUsd":100}""")
        }
        assertEquals(HttpStatusCode.OK, premiumKeyResponse.status)
        val premiumRawKey = json.parseToJsonElement(premiumKeyResponse.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

        val modelsResp = client.get("/api/plugins/airelay/v1/models") {
            header("Authorization", "Bearer $premiumRawKey")
        }
        assertEquals(HttpStatusCode.OK, modelsResp.status)
        val modelsJson = json.parseToJsonElement(modelsResp.bodyAsText()).jsonObject
        val ids = modelsJson["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("smart-claude"), ids)

        val relayResp = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $premiumRawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"smart-claude","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.OK, relayResp.status)
        val relayBody = parseGatewayBody(relayResp.bodyAsText()).jsonObject
        val content = relayBody["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("claude-b"), "alias should fall back to the available target model")
    }

    @Test
    fun anthropicSingleModelLookupAndBracketVariantRoutingAreSupported() = setupApp {
        val groupResp = client.post("/api/plugins/airelay/admin/groups") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "groupId":"opus",
                  "name":"Opus",
                  "enabled":true,
                  "exposureMode":"ALL_MODELS"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, groupResp.status)

        val channelResp = client.post("/api/plugins/airelay/admin/channels") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name":"Opus Relay",
                  "protocol":"OPENAI_CHAT",
                  "baseUrl":"mock://opus-relay",
                  "apiKey":"mock-key",
                  "groupId":"opus",
                  "priority":50,
                  "weight":100,
                  "models":[
                    {"publicModelName":"claude-opus-4-8","upstreamModelName":"gpt-5.5","enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, channelResp.status)

        val premiumKeyResponse = client.post("/api/plugins/token/v1/keys") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Opus key","groupId":"opus","maxBudgetUsd":100}""")
        }
        assertEquals(HttpStatusCode.OK, premiumKeyResponse.status)
        val premiumRawKey = json.parseToJsonElement(premiumKeyResponse.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

        val modelResp = client.get("/api/plugins/airelay/v1/models/claude-opus-4-8%5B1m%5D") {
            header("x-api-key", premiumRawKey)
            header("anthropic-version", "2023-06-01")
        }
        assertEquals(HttpStatusCode.OK, modelResp.status)
        val modelJson = json.parseToJsonElement(modelResp.bodyAsText()).jsonObject
        assertEquals("claude-opus-4-8[1m]", modelJson["id"]!!.jsonPrimitive.content)

        val relayResp = client.post("/api/plugins/airelay/v1/messages") {
            header("x-api-key", premiumRawKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"claude-opus-4-8[1m]","messages":[{"role":"user","content":"Hi"}],"max_tokens":64}""")
        }
        assertEquals(HttpStatusCode.OK, relayResp.status)
        val relayBody = parseGatewayBody(relayResp.bodyAsText()).jsonObject
        assertEquals("message", relayBody["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicMessagesCanRouteToResponsesUpstreamThroughAlias() = setupApp {
        val groupResp = client.post("/api/plugins/airelay/admin/groups") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "groupId":"opus-responses",
                  "name":"Opus Responses",
                  "enabled":true,
                  "exposureMode":"ALIASES_AND_MODELS",
                  "aliasRoutes":[
                    {"aliasName":"claude-opus-4-8","targetModels":["gpt-5.5"],"enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, groupResp.status)

        val channelResp = client.post("/api/plugins/airelay/admin/channels") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name":"Responses Relay",
                  "protocol":"OPENAI_RESPONSES",
                  "baseUrl":"mock://responses-relay",
                  "apiKey":"mock-key",
                  "groupId":"opus-responses",
                  "priority":50,
                  "weight":100,
                  "models":[
                    {"publicModelName":"gpt-5.5","upstreamModelName":"gpt-5.5","enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, channelResp.status)

        val scopedKeyResponse = client.post("/api/plugins/token/v1/keys") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Opus responses key","groupId":"opus-responses","maxBudgetUsd":100}""")
        }
        assertEquals(HttpStatusCode.OK, scopedKeyResponse.status)
        val scopedKeyJson = json.parseToJsonElement(scopedKeyResponse.bodyAsText()).jsonObject
        val scopedRawKey = scopedKeyJson["rawKey"]!!.jsonPrimitive.content
        val scopedKeyId = scopedKeyJson["key"]!!.jsonObject["keyId"]!!.jsonPrimitive.content

        val relayResp = client.post("/api/plugins/airelay/v1/messages") {
            header("x-api-key", scopedRawKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"claude-opus-4-8","messages":[{"role":"user","content":"Reply with pong"}],"max_tokens":64}""")
        }
        assertEquals(HttpStatusCode.OK, relayResp.status, relayResp.bodyAsText())
        assertEquals("OPENAI_RESPONSES", relayResp.headers["X-Upstream-Protocol"])
        val relayBody = parseGatewayBody(relayResp.bodyAsText()).jsonObject
        assertEquals("message", relayBody["type"]!!.jsonPrimitive.content)
        val content = relayBody["content"]!!.jsonArray
        assertTrue(content.isNotEmpty())
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)

        val usageResp = client.get("/api/plugins/token/v1/keys/$scopedKeyId/usage") {
            header("Authorization", "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, usageResp.status)
        val firstRecord = json.parseToJsonElement(usageResp.bodyAsText()).jsonObject["records"]!!.jsonArray.first().jsonObject
        assertEquals("claude-opus-4-8 -> gpt-5.5", firstRecord["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicStreamingCanRouteToResponsesUpstreamThroughAlias() = setupApp {
        val groupResp = client.post("/api/plugins/airelay/admin/groups") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "groupId":"opus-responses-stream",
                  "name":"Opus Responses Stream",
                  "enabled":true,
                  "exposureMode":"ALIASES_AND_MODELS",
                  "aliasRoutes":[
                    {"aliasName":"claude-opus-4-8","targetModels":["gpt-5.4"],"enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, groupResp.status)

        val channelResp = client.post("/api/plugins/airelay/admin/channels") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name":"Responses Stream Relay",
                  "protocol":"OPENAI_RESPONSES",
                  "baseUrl":"mock://responses-stream-relay",
                  "apiKey":"mock-key",
                  "groupId":"opus-responses-stream",
                  "priority":50,
                  "weight":100,
                  "models":[
                    {"publicModelName":"gpt-5.4","upstreamModelName":"gpt-5.4","enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, channelResp.status)

        val scopedKeyResponse = client.post("/api/plugins/token/v1/keys") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Opus responses stream key","groupId":"opus-responses-stream","maxBudgetUsd":100}""")
        }
        assertEquals(HttpStatusCode.OK, scopedKeyResponse.status)
        val scopedRawKey = json.parseToJsonElement(scopedKeyResponse.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

        val relayResp = client.post("/api/plugins/airelay/v1/messages") {
            header("x-api-key", scopedRawKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"claude-opus-4-8","stream":true,"messages":[{"role":"user","content":"Reply with pong"}],"max_tokens":64}""")
        }
        assertEquals(HttpStatusCode.OK, relayResp.status, relayResp.bodyAsText())
        assertEquals("OPENAI_RESPONSES", relayResp.headers["X-Upstream-Protocol"])
        val body = relayResp.bodyAsText()
        assertTrue(body.contains("event: message_start"), body)
        assertTrue(body.contains("event: content_block_start"), body)
        assertTrue(body.contains("event: content_block_delta"), body)
        assertTrue(body.contains("event: message_stop"), body)
    }

    @Test
    fun anthropicStreamingSameProtocolPassesThroughRawSseAndRecordsUsage() = setupApp {
        val upstream = RecordingAnthropicUpstream(
            streamEvents = listOf(
                ServerSentEvent(
                    event = "message_start",
                    data = """{"type":"message_start","message":{"id":"msg_raw","type":"message","role":"assistant","model":"claude-only","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":11,"output_tokens":0}}}""",
                ),
                ServerSentEvent(
                    event = "content_block_start",
                    data = """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_raw","name":"Read","input":{}}}""",
                ),
                ServerSentEvent(
                    event = "content_block_delta",
                    data = """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"pages\":\"\"}"}}""",
                ),
                ServerSentEvent(
                    event = "message_delta",
                    data = """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":11,"output_tokens":5},"context_management":{"applied":[{"type":"clear_tool_results"}]}}""",
                ),
                ServerSentEvent(
                    event = "message_stop",
                    data = """{"type":"message_stop"}""",
                ),
            ),
        )
        relayPlugin.installRealUpstream(
            upstream,
            listOf(
                PoolChainConfig(
                    chainId = "default",
                    modelAliases = listOf("claude-only"),
                    levels = listOf(
                        PoolLevelConfig(
                            levelId = "anthropic-p0",
                            levelIndex = 0,
                            provider = UpstreamProviderConfig("anthropic-upstream", protocol = WireProtocol.ANTHROPIC_MESSAGES),
                            keys = listOf(PooledKeyConfig("anthropic-channel", supportedModels = listOf("claude-only"))),
                        )
                    ),
                )
            ),
        )

        val relayResp = client.post("/api/plugins/airelay/v1/messages") {
            header("x-api-key", rawKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"claude-only","stream":true,"messages":[{"role":"user","content":"Read file"}],"max_tokens":64}""")
        }

        assertEquals(HttpStatusCode.OK, relayResp.status, relayResp.bodyAsText())
        assertEquals("ANTHROPIC_MESSAGES", relayResp.headers["X-Upstream-Protocol"])
        val body = relayResp.bodyAsText()
        assertTrue(body.contains("""event: content_block_start"""), body)
        assertTrue(body.contains("""data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_raw","name":"Read","input":{}}}"""), body)
        assertTrue(body.contains("""data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":11,"output_tokens":5},"context_management":{"applied":[{"type":"clear_tool_results"}]}}"""), body)

        val usageResp = client.get("/api/plugins/token/v1/keys/$keyId/usage") {
            header("Authorization", "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, usageResp.status, usageResp.bodyAsText())
        val firstRecord = json.parseToJsonElement(usageResp.bodyAsText()).jsonObject["records"]!!.jsonArray.first().jsonObject
        assertEquals(200, firstRecord["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("claude-only", firstRecord["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicRequestPreservesAllBetaHeadersWithoutAppendingFallback() = setupApp {
        val upstream = RecordingAnthropicUpstream()
        relayPlugin.installRealUpstream(
            upstream,
            listOf(
                PoolChainConfig(
                    chainId = "default",
                    modelAliases = listOf("claude-opus-4-8[1m]"),
                    levels = listOf(
                        PoolLevelConfig(
                            levelId = "anthropic-p0",
                            levelIndex = 0,
                            provider = UpstreamProviderConfig("anthropic-upstream", protocol = WireProtocol.ANTHROPIC_MESSAGES),
                            keys = listOf(PooledKeyConfig("anthropic-channel", supportedModels = listOf("claude-opus-4-8", "claude-opus-4-8[1m]"))),
                        )
                    ),
                )
            ),
        )

        val relayResp = client.post("/api/plugins/airelay/v1/messages") {
            header("x-api-key", rawKey)
            header("anthropic-version", "2023-06-01")
            header("anthropic-beta", "files-api-2025-04-14")
            header("anthropic-beta", "computer-use-2025-01-24")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"claude-opus-4-8[1m]","messages":[{"role":"user","content":"Hi"}],"max_tokens":64}""")
        }

        assertEquals(HttpStatusCode.OK, relayResp.status, relayResp.bodyAsText())
        val forwarded = upstream.seenExtraHeaders.last()
        assertEquals("files-api-2025-04-14,computer-use-2025-01-24", forwarded["anthropic-beta"])
        assertEquals("2023-06-01", forwarded["anthropic-version"])
        assertFalse((forwarded["anthropic-beta"] ?: "").contains("context-1m-2025-08-07"))
    }

    @Test
    fun anthropicRawProxyDropsBlankPagesQueryButKeepsNonEmptyValues() = setupApp {
        val upstream = RecordingAnthropicUpstream()
        relayPlugin.installRealUpstream(
            upstream,
            listOf(
                PoolChainConfig(
                    chainId = "default",
                    modelAliases = listOf("claude-only"),
                    levels = listOf(
                        PoolLevelConfig(
                            levelId = "anthropic-p0",
                            levelIndex = 0,
                            provider = UpstreamProviderConfig("anthropic-upstream", protocol = WireProtocol.ANTHROPIC_MESSAGES),
                            keys = listOf(PooledKeyConfig("anthropic-channel")),
                        )
                    ),
                )
            ),
        )

        val blankPages = client.get("/api/plugins/airelay/v1/files/file_123/content?pages=") {
            header("x-api-key", rawKey)
            header("anthropic-version", "2023-06-01")
        }
        assertEquals(HttpStatusCode.OK, blankPages.status, blankPages.bodyAsText())
        assertEquals("", upstream.seenRawProxyRequests[0].queryString)

        val nonEmptyPages = client.get("/api/plugins/airelay/v1/files/file_123/content?pages=1,2") {
            header("x-api-key", rawKey)
            header("anthropic-version", "2023-06-01")
        }
        assertEquals(HttpStatusCode.OK, nonEmptyPages.status, nonEmptyPages.bodyAsText())
        assertEquals("pages=1%2C2", upstream.seenRawProxyRequests[1].queryString)
    }

    @Test
    fun unauthorizedRequestIsRejected() = setupApp {
        val response = client.post("/api/plugins/airelay/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun modelNotAllowedIsRejected() = setupApp {
        val response = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"unknown-model-xyz","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun adminGroupPoolsEndpointReturnsRuntimePools() = setupApp {
        val relay = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}""")
        }
        assertEquals(HttpStatusCode.OK, relay.status)

        val response = client.get("/api/plugins/airelay/admin/groups/default/pools")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["pools"]!!.jsonArray.isNotEmpty())
        val pool = body["pools"]!!.jsonArray.map { it.jsonObject }
            .first { it["aliasOrModel"]!!.jsonPrimitive.content == "gpt-4o-mini" }
        assertTrue(pool["metrics1m"]!!.jsonObject["selectedRequests"]!!.jsonPrimitive.content.toLong() >= 1L)
        assertTrue(pool["channels"]!!.jsonArray.first().jsonObject.containsKey("expectedShare"))
        assertTrue(pool["channels"]!!.jsonArray.first().jsonObject.containsKey("metrics15m"))
    }

    @Test
    fun debugHeadersRequireBothServerAndRequestOptIn() = setupApp {
        val defaultResponse = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}""")
        }
        assertEquals(HttpStatusCode.OK, defaultResponse.status)
        assertEquals(null, defaultResponse.headers["X-AIRelay-Selected-Channel"])

        val previous = System.getProperty("keel.airelay.debugHeaders")
        System.setProperty("keel.airelay.debugHeaders", "true")
        try {
            val debugResponse = client.post("/api/plugins/airelay/v1/chat/completions") {
                header("Authorization", "Bearer $rawKey")
                header("X-AIRelay-Debug", "true")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}""")
            }
            assertEquals(HttpStatusCode.OK, debugResponse.status)
            assertTrue((debugResponse.headers["X-AIRelay-Selected-Channel"] ?: "").isNotBlank())
            assertTrue((debugResponse.headers["X-AIRelay-Failover-Count"] ?: "").isNotBlank())
        } finally {
            if (previous == null) {
                System.clearProperty("keel.airelay.debugHeaders")
            } else {
                System.setProperty("keel.airelay.debugHeaders", previous)
            }
        }
    }

    @Test
    fun nonRetryableUpstreamAuthenticationErrorKeepsItsStatus() = setupApp {
        relayPlugin.upstreamClient.failKey("mock-default-1", MockFailure.Http(401, "invalid upstream credential"))

        val response = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid upstream credential"))
    }

    @Test
    fun updatingAChannelClearsDisabledRuntimeState() = setupApp {
        val groupResponse = client.post("/api/plugins/airelay/admin/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"groupId":"recoverable","name":"Recoverable","enabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, groupResponse.status, groupResponse.bodyAsText())

        val channelResponse = client.post("/api/plugins/airelay/admin/channels") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name":"Recoverable Channel",
                  "protocol":"OPENAI_CHAT",
                  "baseUrl":"mock://recoverable-openai",
                  "apiKey":"mock-key",
                  "groupId":"recoverable",
                  "priority":100,
                  "weight":100,
                  "maxConcurrency":10,
                  "timeoutMs":30000,
                  "models":[
                    {"publicModelName":"gpt-4o-mini","upstreamModelName":"gpt-4o-mini","enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, channelResponse.status, channelResponse.bodyAsText())
        val channelId = json.parseToJsonElement(channelResponse.bodyAsText()).jsonObject["channelId"]!!.jsonPrimitive.content

        val routedKeyResponse = client.post("/api/plugins/token/v1/keys") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Recoverable key","groupId":"recoverable","maxBudgetUsd":100}""")
        }
        assertEquals(HttpStatusCode.OK, routedKeyResponse.status, routedKeyResponse.bodyAsText())
        val routedRawKey = json.parseToJsonElement(routedKeyResponse.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

        relayPlugin.upstreamClient.failKey(channelId, MockFailure.Http(401, "invalid upstream credential"))

        val failed = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $routedRawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, failed.status)

        relayPlugin.upstreamClient.clearFailures()

        val update = client.put("/api/plugins/airelay/admin/channels/$channelId") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name":"Recoverable Channel",
                  "protocol":"OPENAI_CHAT",
                  "baseUrl":"mock://recoverable-openai",
                  "apiKey":"",
                  "enabled":true,
                  "priority":100,
                  "weight":100,
                  "maxConcurrency":10,
                  "timeoutMs":30000,
                  "groupId":"recoverable",
                  "models":[
                    {"publicModelName":"gpt-4o-mini","upstreamModelName":"gpt-4o-mini","enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, update.status, update.bodyAsText())

        val recovered = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $routedRawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello again"}]}""")
        }
        assertEquals(HttpStatusCode.OK, recovered.status, recovered.bodyAsText())
    }

    @Test
    fun adminChannelListReflectsRuntimeStatusAndSupportsResetByChannelId() = setupApp {
        val groupResponse = client.post("/api/plugins/airelay/admin/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"groupId":"status-sync","name":"Status Sync","enabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, groupResponse.status, groupResponse.bodyAsText())

        val channelResponse = client.post("/api/plugins/airelay/admin/channels") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name":"Status Sync Channel",
                  "protocol":"OPENAI_CHAT",
                  "baseUrl":"mock://status-sync-openai",
                  "apiKey":"mock-key",
                  "groupId":"status-sync",
                  "priority":100,
                  "weight":100,
                  "maxConcurrency":10,
                  "timeoutMs":30000,
                  "models":[
                    {"publicModelName":"gpt-4o-mini","upstreamModelName":"gpt-4o-mini","enabled":true}
                  ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, channelResponse.status, channelResponse.bodyAsText())
        val channelId = json.parseToJsonElement(channelResponse.bodyAsText()).jsonObject["channelId"]!!.jsonPrimitive.content

        val routedKeyResponse = client.post("/api/plugins/token/v1/keys") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Status sync key","groupId":"status-sync","maxBudgetUsd":100}""")
        }
        assertEquals(HttpStatusCode.OK, routedKeyResponse.status, routedKeyResponse.bodyAsText())
        val routedRawKey = json.parseToJsonElement(routedKeyResponse.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

        relayPlugin.upstreamClient.failKey(channelId, MockFailure.Http(401, "invalid upstream credential"))

        val failed = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $routedRawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, failed.status)

        val listed = client.get("/api/plugins/airelay/admin/channels")
        assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
        val listedChannel = json.parseToJsonElement(listed.bodyAsText()).jsonObject["channels"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["channelId"]!!.jsonPrimitive.content == channelId }
        assertEquals("DISABLED", listedChannel["status"]!!.jsonPrimitive.content)

        relayPlugin.upstreamClient.clearFailures()

        val reset = client.post("/api/plugins/airelay/admin/channels/$channelId/reset") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())

        val relisted = client.get("/api/plugins/airelay/admin/channels")
        assertEquals(HttpStatusCode.OK, relisted.status, relisted.bodyAsText())
        val relistedChannel = json.parseToJsonElement(relisted.bodyAsText()).jsonObject["channels"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["channelId"]!!.jsonPrimitive.content == channelId }
        assertEquals("HEALTHY", relistedChannel["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun nonRetryableUpstream400UsageRecordKeepsSelectedChannelMetadata() = setupApp {
        relayPlugin.upstreamClient.failKey("mock-default-1", MockFailure.Http(400, "bad request"))

        val relayResponse = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, relayResponse.status)

        val usageResponse = client.get("/api/plugins/token/v1/keys/$keyId/usage") {
            header("Authorization", "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, usageResponse.status)
        val records = json.parseToJsonElement(usageResponse.bodyAsText()).jsonObject["records"]!!.jsonArray
        val failedRecord = records.first().jsonObject

        assertEquals(400, failedRecord["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("mock-default-1", failedRecord["channelId"]!!.jsonPrimitive.content)
        assertEquals("default", failedRecord["routingGroupId"]!!.jsonPrimitive.content)
        assertEquals("l1-default-responses", failedRecord["poolLevelId"]!!.jsonPrimitive.content)
        assertEquals("mock-openai-default", failedRecord["provider"]!!.jsonPrimitive.content)
    }

    @Test
    fun explainEndpointReturnsTheRequestedHistoricalTrace() = setupApp {
        listOf("trace-one", "trace-two").forEach { requestId ->
            val relay = client.post("/api/plugins/airelay/v1/chat/completions") {
                header("Authorization", "Bearer $rawKey")
                header("X-Request-Id", requestId)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}""")
            }
            assertEquals(HttpStatusCode.OK, relay.status)
        }

        val response = client.post("/api/plugins/airelay/admin/groups/default/pools/gpt-4o-mini/explain") {
            contentType(ContentType.Application.Json)
            setBody("""{"requestedModel":"gpt-4o-mini","requestId":"trace-one"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val trace = json.parseToJsonElement(response.bodyAsText()).jsonObject["requestTrace"]!!.jsonObject
        assertEquals("trace-one", trace["requestId"]!!.jsonPrimitive.content)
        assertEquals("SUCCESS", trace["outcome"]!!.jsonPrimitive.content)
        assertTrue(trace["attempts"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun debugHeaderReportsOrderedFailoverPolicy() = setupApp {
        relayPlugin.installRealUpstream(
            relayPlugin.upstreamClient,
            listOf(
                PoolChainConfig(
                    chainId = "default",
                    modelAliases = listOf("legacy"),
                    aliasRoutes = listOf(
                        AliasRouteConfig(
                            aliasName = "legacy",
                            targets = listOf(AliasTargetConfig("upstream-model")),
                            routingPolicy = AliasRoutingPolicy.ORDERED_FAILOVER,
                        )
                    ),
                    levels = listOf(
                        PoolLevelConfig(
                            levelId = "default-p0",
                            levelIndex = 0,
                            provider = UpstreamProviderConfig("legacy-provider", protocol = WireProtocol.OPENAI_CHAT),
                            keys = listOf(PooledKeyConfig("legacy-channel", supportedModels = listOf("upstream-model"))),
                        )
                    ),
                )
            ),
        )
        val previous = System.getProperty("keel.airelay.debugHeaders")
        System.setProperty("keel.airelay.debugHeaders", "true")
        try {
            val response = client.post("/api/plugins/airelay/v1/chat/completions") {
                header("Authorization", "Bearer $rawKey")
                header("X-AIRelay-Debug", "true")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"legacy","messages":[{"role":"user","content":"Hello"}]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("ORDERED_FAILOVER", response.headers["X-AIRelay-Routing-Policy"])
        } finally {
            if (previous == null) System.clearProperty("keel.airelay.debugHeaders") else System.setProperty("keel.airelay.debugHeaders", previous)
        }
    }

    @Test
    fun streamingSemanticFailureIsCountedAsFailure() = setupApp {
        relayPlugin.upstreamClient.streamEventsForKey(
            "mock-default-1",
            listOf(
                ServerSentEvent(
                    data = """{"type":"response.failed","response":{"id":"resp-failed","status":"failed","error":{"type":"server_error","message":"stream failed"}}}""",
                    event = "response.failed",
                )
            ),
        )

        val response = client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer $rawKey")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"Hello"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        response.bodyAsText()

        val pools = client.get("/api/plugins/airelay/admin/groups/default/pools")
        val pool = json.parseToJsonElement(pools.bodyAsText()).jsonObject["pools"]!!.jsonArray
            .map { it.jsonObject }
            .first {
                it["aliasOrModel"]!!.jsonPrimitive.content == "gpt-4o-mini" &&
                    it["channels"]!!.jsonArray.any { channel -> channel.jsonObject["channelId"]!!.jsonPrimitive.content == "mock-default-1" }
            }
        assertTrue(pool["metrics1m"]!!.jsonObject["failedRequests"]!!.jsonPrimitive.content.toLong() >= 1L)
    }
}
