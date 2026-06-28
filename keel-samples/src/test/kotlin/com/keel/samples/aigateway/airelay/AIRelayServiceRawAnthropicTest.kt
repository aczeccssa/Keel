package com.keel.samples.aigateway.airelay

import com.keel.contract.ai.ApiKeyVerifier
import com.keel.contract.ai.CostBreakdown
import com.keel.contract.ai.RateLimitContext
import com.keel.contract.ai.RateLimitDecision
import com.keel.contract.ai.RateLimitGate
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UsageRecorder
import com.keel.contract.ai.UsageSnapshot
import com.keel.contract.ai.UserDirectory
import com.keel.contract.ai.UserGroupSummary
import com.keel.contract.ai.UserSummary
import com.keel.contract.ai.VerifiedApiKey
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.RawPluginRequest
import com.keel.samples.aigateway.airelay.pool.PoolChainManager
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.upstream.OpenedUpstreamStream
import com.keel.samples.aigateway.airelay.upstream.RawProxyRequest
import com.keel.samples.aigateway.airelay.upstream.RawProxyResponse
import com.keel.samples.aigateway.airelay.upstream.UpstreamHttpClient
import com.keel.samples.aigateway.airelay.upstream.UpstreamResponse
import com.keel.samples.aigateway.airelay.usage.CostCalculator
import com.keel.samples.aigateway.airelay.usage.ModelPricingRegistry
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AIRelayServiceRawAnthropicTest {
    @Test
    fun claudeCodeRequestsUseRawAnthropicFidelityForwarding() = runBlocking {
        val upstream = RecordingUpstreamHttpClient()
        val service = AIRelayService(
            apiKeyVerifier = testApiKeyVerifier,
            usageRecorder = testUsageRecorder,
            rateLimitGate = testRateLimitGate,
            userDirectory = testUserDirectory,
            poolChainManager = testPoolChainManager(),
            transcoder = protocolTranscoder(),
            upstreamClient = upstream,
            costCalculator = testCostCalculator(),
        )

        val rawBody = """
            {"model":"claude-sonnet-4-5","max_tokens":64,"messages":[{"role":"user","content":"ping"}],"extra_field":{"nested":true}}
        """.trimIndent().encodeToByteArray()
        val response = service.handleAnthropicRaw(
            context = testContext(
                rawPath = "/api/plugins/airelay/v1/messages",
                headers = mapOf(
                    "Content-Type" to listOf("application/json"),
                    "Accept" to listOf("application/json"),
                    "x-api-key" to listOf("sk-keel-test"),
                    "anthropic-version" to listOf("2023-06-01"),
                    "user-agent" to listOf("claude-cli/1.0.0"),
                    "x-app" to listOf("cli"),
                    "anthropic-beta" to listOf("code-tools-2026-05-14"),
                    "anthropic-dangerous-direct-browser-access" to listOf("true"),
                ),
                query = mapOf("beta" to listOf("true")),
            ),
            raw = RawPluginRequest(
                method = "POST",
                path = "/api/plugins/airelay/v1/messages",
                query = mapOf("beta" to listOf("true")),
                headers = mapOf(
                    "Content-Type" to listOf("application/json"),
                    "Accept" to listOf("application/json"),
                    "x-api-key" to listOf("sk-keel-test"),
                    "anthropic-version" to listOf("2023-06-01"),
                    "user-agent" to listOf("claude-cli/1.0.0"),
                    "x-app" to listOf("cli"),
                    "anthropic-beta" to listOf("code-tools-2026-05-14"),
                    "anthropic-dangerous-direct-browser-access" to listOf("true"),
                ),
                body = rawBody,
            ),
        )

        assertEquals(200, response.status)
        assertEquals(0, upstream.sendCalls, "structured send path should not be used for Claude Code fidelity requests")
        val captured = assertNotNull(upstream.rawRequest)
        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/v1/messages", captured.path)
        assertEquals("beta=true", captured.queryString)
        assertEquals("claude-cli/1.0.0", captured.headers.getValue("user-agent").single())
        assertEquals("cli", captured.headers.getValue("x-app").single())
        assertEquals("true", captured.headers.getValue("anthropic-dangerous-direct-browser-access").single())
        assertContentEquals(rawBody, captured.body)
        assertTrue(response.body.isNotEmpty())
    }

    @Test
    fun handleBlockingUsesFidelityForwardingForClaudeCodeRequests() = runBlocking {
        val upstream = RecordingUpstreamHttpClient()
        val service = AIRelayService(
            apiKeyVerifier = testApiKeyVerifier,
            usageRecorder = testUsageRecorder,
            rateLimitGate = testRateLimitGate,
            userDirectory = testUserDirectory,
            poolChainManager = testPoolChainManager(),
            transcoder = protocolTranscoder(),
            upstreamClient = upstream,
            costCalculator = testCostCalculator(),
        )
        val rawJson = buildJsonObject {
            put("model", JsonPrimitive("claude-sonnet-4-5"))
            put("max_tokens", JsonPrimitive(64))
            put(
                "messages",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("ping"))
                        }
                    )
                }
            )
        }
        val originalBody = """{"model":"claude-sonnet-4-5","max_tokens":64,"messages":[{"role":"user","content":"ping"}]}""".encodeToByteArray()
        val context = testContext(
            rawPath = "/api/plugins/airelay/v1/messages",
            headers = mapOf(
                "Content-Type" to listOf("application/json"),
                "Accept" to listOf("application/json"),
                "x-api-key" to listOf("sk-keel-test"),
                "anthropic-version" to listOf("2023-06-01"),
                "user-agent" to listOf("claude-cli/1.0.0"),
                "x-app" to listOf("cli"),
                "anthropic-beta" to listOf("code-tools-2026-05-14"),
                "anthropic-dangerous-direct-browser-access" to listOf("true"),
            ),
            query = mapOf("beta" to listOf("true")),
        )
        context.attributes[RAW_REQUEST_BODY_ATTRIBUTE] = originalBody

        val response = service.handleBlocking(
            context = context,
            rawRequest = rawJson,
            clientProtocol = WireProtocol.ANTHROPIC_MESSAGES,
        )

        assertEquals(200, response.status)
        assertEquals(0, upstream.sendCalls)
        assertContentEquals(originalBody, assertNotNull(upstream.rawRequest).body)
        assertTrue((response.body as String).contains("\"id\":\"msg_test\""))
    }

    @Test
    fun repeatedOfficialCliCompatibility403sDoNotCascadeIntoPoolExhausted() = runBlocking {
        val upstream = RecordingUpstreamHttpClient().apply {
            rawResponse = RawProxyResponse(
                status = 403,
                headers = mapOf("Content-Type" to listOf("application/json")),
                contentType = "application/json",
                body = """
                    {"type":"error","error":{"type":"permission_error","message":"Request blocked: this endpoint only accepts requests from the official Claude Code CLI"}}
                """.trimIndent().encodeToByteArray(),
            )
        }
        val service = AIRelayService(
            apiKeyVerifier = testApiKeyVerifier,
            usageRecorder = testUsageRecorder,
            rateLimitGate = testRateLimitGate,
            userDirectory = testUserDirectory,
            poolChainManager = testPoolChainManager(),
            transcoder = protocolTranscoder(),
            upstreamClient = upstream,
            costCalculator = testCostCalculator(),
        )
        val context = testContext(
            rawPath = "/api/plugins/airelay/v1/messages",
            headers = mapOf(
                "Content-Type" to listOf("application/json"),
                "Accept" to listOf("application/json"),
                "x-api-key" to listOf("sk-keel-test"),
                "anthropic-version" to listOf("2023-06-01"),
                "user-agent" to listOf("claude-cli/1.0.0"),
                "x-app" to listOf("cli"),
            ),
            query = mapOf("beta" to listOf("true")),
        )
        val rawJson = buildJsonObject {
            put("model", JsonPrimitive("claude-sonnet-4-5"))
            put("max_tokens", JsonPrimitive(64))
            put(
                "messages",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("ping"))
                        }
                    )
                }
            )
        }

        val first = service.handleBlocking(context, rawJson, WireProtocol.ANTHROPIC_MESSAGES)
        val second = service.handleBlocking(context, rawJson, WireProtocol.ANTHROPIC_MESSAGES)

        assertEquals(403, first.status)
        assertEquals(403, second.status)
    }

    private val testApiKeyVerifier = object : ApiKeyVerifier {
        override suspend fun verify(rawKey: String, clientIp: String?): VerifiedApiKey = VerifiedApiKey(
            keyId = "vk-1",
            userId = "user-1",
            userGroupId = "default",
            routingGroupId = "default",
            allowedModels = emptyList(),
            rpmLimit = null,
            tpmLimit = null,
            remainingBudgetUsd = 100.0,
        )
    }

    private val testUsageRecorder = object : UsageRecorder {
        override suspend fun record(record: com.keel.contract.ai.UsageRecordInput) = Unit
        override suspend fun snapshot(): UsageSnapshot = UsageSnapshot(0, 0.0, 0, emptyList(), emptyList(), emptyList())
    }

    private val testRateLimitGate = object : RateLimitGate {
        override suspend fun tryAcquire(context: RateLimitContext): RateLimitDecision =
            RateLimitDecision.Allowed(limit = 100, remaining = 99, resetAtEpochMs = 0L)
    }

    private val testUserDirectory = object : UserDirectory {
        override suspend fun findById(userId: String): UserSummary? = UserSummary(
            userId = userId,
            email = "$userId@example.com",
            displayName = userId,
            role = "user",
            groupId = "default",
            status = "active",
        )

        override suspend fun findGroup(groupId: String): UserGroupSummary? = UserGroupSummary(
            groupId = groupId,
            name = groupId,
            costMultiplier = 1.0,
            defaultRpm = null,
            defaultTpm = null,
            defaultBudgetUsd = 0.0,
        )
    }

    private fun testPoolChainManager() = PoolChainManager(
        listOf(
            PoolChainConfig(
                chainId = "default",
                modelAliases = listOf("claude-sonnet-4-5"),
                levels = listOf(
                    PoolLevelConfig(
                        levelId = "default-p0",
                        levelIndex = 0,
                        provider = UpstreamProviderConfig(
                            providerId = "anthropic-upstream",
                            baseUrl = "mock://anthropic",
                            protocol = WireProtocol.ANTHROPIC_MESSAGES,
                        ),
                        keys = listOf(
                            PooledKeyConfig(
                                keyId = "anthropic-key-1",
                                apiKey = "provider-key",
                                supportedModels = listOf("claude-sonnet-4-5"),
                            )
                        ),
                    )
                ),
            )
        )
    )

    private fun testCostCalculator() = CostCalculator(
        ModelPricingRegistry(listOf(ModelPricing("claude-sonnet-4-5", inputCostPerMTok = 1.0, outputCostPerMTok = 2.0))),
        object : UserDirectory {
            override suspend fun findById(userId: String): UserSummary? = null
            override suspend fun findGroup(groupId: String): UserGroupSummary? =
                UserGroupSummary(groupId, groupId, 1.0, null, null, 0.0)
        }
    )

    private fun protocolTranscoder() = com.keel.samples.aigateway.airelay.protocol.ProtocolTranscoder(
        listOf(
            com.keel.samples.aigateway.airelay.protocol.openai.chat.OpenAIChatCodec(),
            com.keel.samples.aigateway.airelay.protocol.openai.responses.OpenAIResponsesCodec(),
            com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicMessagesCodec(),
        )
    )

    private fun testContext(
        rawPath: String,
        headers: Map<String, List<String>>,
        query: Map<String, List<String>>,
    ) = object : KeelRequestContext {
        override val pluginId: String = "airelay"
        override val method: String = "POST"
        override val rawPath: String = rawPath
        override val pathParameters: Map<String, String> = emptyMap()
        override val queryParameters: Map<String, List<String>> = query
        override val requestHeaders: Map<String, List<String>> = headers
        override val requestId: String = "req-1"
        override val attributes: MutableMap<String, Any?> = mutableMapOf()
        override var principal: Any? = null
        override var tenant: Any? = null
    }

    private class RecordingUpstreamHttpClient : UpstreamHttpClient {
        var sendCalls: Int = 0
        var rawRequest: RawProxyRequest? = null
        var rawResponse: RawProxyResponse? = null

        override suspend fun send(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: kotlinx.serialization.json.JsonObject,
            extraHeaders: Map<String, String>,
        ): UpstreamResponse {
            sendCalls += 1
            fail("structured send path should not be used")
        }

        override suspend fun openStream(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: kotlinx.serialization.json.JsonObject,
            extraHeaders: Map<String, String>,
        ): OpenedUpstreamStream = fail("stream path should not be used")

        override suspend fun countTokens(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: kotlinx.serialization.json.JsonObject,
            extraHeaders: Map<String, String>,
        ): UpstreamResponse = fail("countTokens path should not be used")

        override suspend fun proxyRaw(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: RawProxyRequest,
            extraHeaders: Map<String, String>,
        ): RawProxyResponse {
            rawRequest = request
            return rawResponse ?: RawProxyResponse(
                status = 200,
                headers = mapOf("Content-Type" to listOf("application/json")),
                contentType = "application/json",
                body = buildJsonObject {
                    put("id", JsonPrimitive("msg_test"))
                    put("type", JsonPrimitive("message"))
                    put("role", JsonPrimitive("assistant"))
                    put("model", JsonPrimitive("claude-sonnet-4-5"))
                    put(
                        "content",
                        kotlinx.serialization.json.buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive("ok"))
                                }
                            )
                        }
                    )
                    put("stop_reason", JsonPrimitive("end_turn"))
                    put(
                        "usage",
                        buildJsonObject {
                            put("input_tokens", JsonPrimitive(1))
                            put("output_tokens", JsonPrimitive(1))
                        }
                    )
                }.toString().encodeToByteArray(),
            )
        }

        override suspend fun openRawStream(
            selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
            request: RawProxyRequest,
            extraHeaders: Map<String, String>,
        ): OpenedUpstreamStream = fail("raw stream path should not be used")
    }
}
