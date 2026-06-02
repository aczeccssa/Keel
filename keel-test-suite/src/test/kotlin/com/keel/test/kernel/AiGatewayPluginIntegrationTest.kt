package com.keel.test.kernel

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        OpenApiRegistry.clear()
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(AccountPlugin())
        manager.registerPlugin(TokenPlugin())
        manager.registerPlugin(RiskControlPlugin())
        manager.registerPlugin(AIRelayPlugin())
        manager.registerPlugin(ObservabilityPlugin())

        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing { manager.mountRoutes(this) }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("account")
                manager.startPlugin("token")
                manager.startPlugin("riskcontrol")
                manager.startPlugin("airelay")
                manager.startPlugin("observability")
            }
        }

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

        block(TestContext(client, accessToken, rawKey))
    }

    /** Unwrap the RelayResponse wrapper: { "response": "<json>" } -> parse inner JSON */
    private fun unwrapRelayResponse(body: String): kotlinx.serialization.json.JsonElement {
        val outer = json.parseToJsonElement(body).jsonObject
        val inner = outer["response"]!!.jsonPrimitive.content
        return json.parseToJsonElement(inner)
    }

    private class TestContext(
        val client: io.ktor.client.HttpClient,
        val accessToken: String,
        val rawKey: String
    )

    @Test
    fun loginCreateKeyAndChatCompletionsRoundTrip() = setupApp { ctx ->
        val chatResponse = ctx.client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer ${ctx.rawKey}")
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
        val chatBody = unwrapRelayResponse(chatResponse.bodyAsText()).jsonObject
        val content = chatBody["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("Mock response"))
        assertTrue((chatResponse.headers["X-Upstream-Protocol"] ?: "").isNotBlank())
        assertTrue((chatResponse.headers["X-Cost-USD"] ?: "0.0").toDouble() >= 0.0)
    }

    @Test
    fun allNineProtocolCombinationsSucceed() = setupApp { ctx ->
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
            val response = ctx.client.post("/api/plugins/airelay${combo.endpoint}") {
                header("Authorization", "Bearer ${ctx.rawKey}")
                contentType(ContentType.Application.Json)
                setBody(combo.bodyBuilder())
            }
            assertEquals(HttpStatusCode.OK, response.status, "Expected 200 for ${combo.name}, got ${response.status}: ${response.bodyAsText()}")
            val body = unwrapRelayResponse(response.bodyAsText())
            combo.validate(body)
        }
    }

    @Test
    fun responsesRejectsPreviousResponseId() = setupApp { ctx ->
        val response = ctx.client.post("/api/plugins/airelay/v1/responses") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","input":"Hi","previous_response_id":"resp-123","store":false}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("previous_response_id"))
    }

    @Test
    fun unauthorizedRequestIsRejected() = setupApp { ctx ->
        val response = ctx.client.post("/api/plugins/airelay/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun modelNotAllowedIsRejected() = setupApp { ctx ->
        val response = ctx.client.post("/api/plugins/airelay/v1/chat/completions") {
            header("Authorization", "Bearer ${ctx.rawKey}")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"unknown-model-xyz","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
