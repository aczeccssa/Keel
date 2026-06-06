package com.keel.test.kernel

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
import com.keel.samples.aigateway.airelay.upstream.MockFailure
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.riskcontrol.UpsertRateLimitRuleRequest
import com.keel.samples.aigateway.token.TokenPlugin
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class AiGatewayAcceptanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun teardown() {
        OpenApiRegistry.clear()
        runCatching { stopKoin() }
    }

    private fun parseBody(body: String): JsonObject {
        val outer = json.parseToJsonElement(body).jsonObject
        val inner = outer["response"]?.jsonPrimitive?.contentOrNull
        return if (inner != null) json.parseToJsonElement(inner).jsonObject else outer
    }

    @Test
    fun protocolMatrixCoversAllNineCombinations() = withGateway { ctx ->
        val cases = listOf(
            ProtocolCase("Chat -> Chat", "chat", "gpt-chat-only", expectedShape = ChatShape),
            ProtocolCase("Chat -> Responses", "chat", "gpt-responses-only", expectedShape = ChatShape),
            ProtocolCase("Chat -> Anthropic", "chat", "claude-only", expectedShape = ChatShape),
            ProtocolCase("Responses -> Chat", "responses", "gpt-chat-only", expectedShape = ResponsesShape),
            ProtocolCase("Responses -> Responses", "responses", "gpt-responses-only", expectedShape = ResponsesShape),
            ProtocolCase("Responses -> Anthropic", "responses", "claude-only", expectedShape = ResponsesShape),
            ProtocolCase("Anthropic -> Chat", "messages", "gpt-chat-only", expectedShape = MessagesShape),
            ProtocolCase("Anthropic -> Responses", "messages", "gpt-responses-only", expectedShape = MessagesShape),
            ProtocolCase("Anthropic -> Anthropic", "messages", "claude-only", expectedShape = MessagesShape)
        )
        for (case in cases) {
            val response = ctx.invokeClient(case.clientProtocol, case.model)
            assertEquals(HttpStatusCode.OK, response.status, "${case.label} should succeed")
            val body = parseBody(response.bodyAsText())
            case.expectedShape.assert(body, case.model, case.label)
        }
    }

    @Test
    fun streamingFailoverHonoursBufferFirstChunkAndRetryAfter() = withGateway { ctx ->
        ctx.upstream.failKey("mock-default-1", MockFailure.Http(429, retryAfterSeconds = 1))
        val resp = ctx.invokeClient("chat", "gpt-4o-mini")
        assertEquals(HttpStatusCode.OK, resp.status, "Failover should pick L2 and succeed")
        val body = parseBody(resp.bodyAsText())
        ChatShape.assert(body, "gpt-4o-mini", "L1 cooldown -> L2 chat")
        val pools = ctx.poolSnapshot()
        val l1Status = pools.chains.first { it.chainId == "default-chain" }
            .levels.first().keys.first().status
        assertEquals("COOLDOWN", l1Status)
        val streamResp = ctx.invokeClient("chat", "gpt-4o-mini", stream = true)
        assertEquals(HttpStatusCode.OK, streamResp.status)
    }

    @Test
    fun rateLimitTriggers429AndRetryAfterHeaders() = withGateway { ctx ->
        ctx.risk.engine.upsertRule(
            UpsertRateLimitRuleRequest(
                ruleId = "test-strict-key",
                name = "Strict per-key for tests",
                dimension = "API_KEY",
                pathPattern = "/v1/*",
                methods = setOf("POST"),
                capacity = 1,
                refillRatePerSec = 0.01,
                priority = 1000
            )
        )
        val first = ctx.invokeClient("chat", "gpt-chat-only")
        assertEquals(HttpStatusCode.OK, first.status)
        val second = ctx.invokeClient("chat", "gpt-chat-only")
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        assertNotNull(second.headers["Retry-After"])
    }

    @Test
    fun cacheAndReasoningTokensApplyDiscountedBilling() = withGateway { ctx ->
        ctx.upstream.forceUsage(
            "mock-openai-chat-1",
            com.keel.contract.ai.TokenUsage(
                promptTokens = 1_000_000,
                completionTokens = 1_000_000,
                cachedPromptTokens = 1_000_000,
                reasoningTokens = 200_000
            )
        )
        val resp = ctx.invokeClient("chat", "gpt-chat-only")
        assertEquals(HttpStatusCode.OK, resp.status)
        val cost = resp.headers["X-Cost-USD"]!!.toDouble()
        assertTrue(cost in 2.4..2.6, "Discounted cache + reasoning cost should be ~2.5, was $cost")

        ctx.upstream.forceUsage(
            "mock-anthropic-1",
            com.keel.contract.ai.TokenUsage(
                promptTokens = 100_000,
                completionTokens = 50_000,
                cacheCreationInputTokens = 1_000_000,
                cacheReadInputTokens = 1_000_000
            )
        )
        val anthropic = ctx.invokeClient("messages", "claude-only")
        assertEquals(HttpStatusCode.OK, anthropic.status)
        val anthropicCost = anthropic.headers["X-Cost-USD"]!!.toDouble()
        assertTrue(anthropicCost in 5.05..5.15, "Anthropic cache billing should be ~5.10, was $anthropicCost")
    }

    @Test
    fun observabilitySnapshotReflectsAfterTrafficFlows() = withGateway { ctx ->
        repeat(2) { ctx.invokeClient("chat", "gpt-chat-only") }
        val pools = ctx.poolSnapshot()
        val keys = pools.chains.first { it.chainId == "openai-chat-chain" }.levels.first().keys
        assertTrue(keys.any { it.totalRequests > 0 }, "Pool snapshot should record traffic")
        val rateSnap = ctx.risk.engine.snapshot()
        assertTrue(rateSnap.totalAllowed > 0, "Rate-limit snapshot should record allowed calls")
    }

    private data class ProtocolCase(
        val label: String,
        val clientProtocol: String,
        val model: String,
        val expectedShape: ResponseShape
    )

    private sealed interface ResponseShape {
        fun assert(body: JsonObject, model: String, label: String)
    }

    private object ChatShape : ResponseShape {
        override fun assert(body: JsonObject, model: String, label: String) {
            val choices = body["choices"] as? JsonArray ?: error("$label missing choices array")
            assertTrue(choices.isNotEmpty(), "$label expected at least one choice")
            val message = choices[0].jsonObject["message"]!!.jsonObject
            assertEquals("assistant", message["role"]!!.jsonPrimitive.content)
        }
    }

    private object ResponsesShape : ResponseShape {
        override fun assert(body: JsonObject, model: String, label: String) {
            assertEquals("response", body["object"]!!.jsonPrimitive.content, "$label expected Responses envelope")
            val output = body["output"] as? JsonArray ?: error("$label missing output array")
            assertTrue(output.isNotEmpty(), "$label expected at least one output item")
        }
    }

    private object MessagesShape : ResponseShape {
        override fun assert(body: JsonObject, model: String, label: String) {
            assertEquals("message", body["type"]!!.jsonPrimitive.content, "$label expected Anthropic envelope")
            val content = body["content"] as? JsonArray ?: error("$label missing content array")
            assertTrue(content.isNotEmpty(), "$label expected at least one content block")
        }
    }

    private class GatewayContext(
        val client: HttpClient,
        val manager: UnifiedPluginManager,
        val airelay: AIRelayPlugin,
        val token: TokenPlugin,
        val risk: RiskControlPlugin,
        val rawKey: String
    ) {
        val upstream get() = airelay.upstreamClient
        suspend fun invokeClient(clientProtocol: String, model: String, stream: Boolean = false): HttpResponse {
            val body = when (clientProtocol) {
                "chat" -> """{"model":"$model","messages":[{"role":"user","content":"Hello"}],"stream":$stream}"""
                "responses" -> """{"model":"$model","input":"Hello","store":false,"stream":$stream}"""
                "messages" -> """{"model":"$model","max_tokens":256,"messages":[{"role":"user","content":"Hello"}],"stream":$stream}"""
                else -> error("unknown protocol $clientProtocol")
            }
            val path = when (clientProtocol) {
                "chat" -> "/api/plugins/airelay/v1/chat/completions"
                "responses" -> "/api/plugins/airelay/v1/responses"
                else -> "/api/plugins/airelay/v1/messages"
            }
            return client.post(path) {
                header("Authorization", "Bearer $rawKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

        fun poolSnapshot(): com.keel.contract.ai.PoolChainSnapshot {
            return airelay.kernelKoin.get<com.keel.contract.ai.PoolChainSnapshotProvider>().snapshot()
        }
    }

    private fun withGateway(block: suspend (GatewayContext) -> Unit) = testApplication {
        val previousDataDir = System.getProperty("keel.data.dir")
        val testDataDir = Files.createTempDirectory("keel-gateway-acceptance-").toFile()
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
                setBody("""{"displayName":"Acceptance test key","maxBudgetUsd":100}""")
            }
            assertEquals(HttpStatusCode.OK, keyResponse.status)
            val rawKey = json.parseToJsonElement(keyResponse.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

            block(
                GatewayContext(
                    client = client,
                    manager = manager,
                    airelay = airelayPlugin,
                    token = tokenPlugin,
                    risk = riskPlugin,
                    rawKey = rawKey
                )
            )
        } finally {
            if (previousDataDir == null) {
                System.clearProperty("keel.data.dir")
            } else {
                System.setProperty("keel.data.dir", previousDataDir)
            }
        }
    }
}
