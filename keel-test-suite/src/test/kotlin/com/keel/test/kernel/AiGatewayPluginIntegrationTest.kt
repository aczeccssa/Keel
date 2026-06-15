package com.keel.test.kernel

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.token.TokenPlugin
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        manager.registerPlugin(AIRelayPlugin())

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
            val rawKey = json.parseToJsonElement(keyResponse.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content
            assertTrue(rawKey.startsWith("sk-keel-"))

            with(TestContext(client, accessToken, rawKey, json)) {
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
        private val json: Json
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
}
