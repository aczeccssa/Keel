package com.keel.test.kernel

import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.pool.PoolChainManager
import com.keel.samples.aigateway.airelay.protocol.ProtocolTranscoder
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicMessagesCodec
import com.keel.samples.aigateway.airelay.protocol.openai.chat.OpenAIChatCodec
import com.keel.samples.aigateway.airelay.protocol.openai.responses.OpenAIResponsesCodec
import com.keel.samples.aigateway.airelay.upstream.RealUpstreamHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live protocol-module test for Anthropic Messages -> OpenAI Responses -> Anthropic Messages.
 *
 * Disabled unless ROUTIN_AI_PLAN_API_KEY is set. The API key must never be committed.
 */
class LiveOpenAIResponsesTranscodingTest {
    @Test
    fun anthropicRequestTranscodesThroughRoutinOpenAIResponsesUpstream() = runBlocking {
        val apiKey = System.getenv("ROUTIN_AI_PLAN_API_KEY")
        assumeTrue(!apiKey.isNullOrBlank(), "Set ROUTIN_AI_PLAN_API_KEY to run routin.ai live Responses transcoding test")
        val upstreamModel = System.getenv("ROUTIN_AI_PLAN_MODEL")?.takeIf { it.isNotBlank() } ?: "gpt-5.3"

        val transcoder = ProtocolTranscoder(
            listOf(
                AnthropicMessagesCodec(),
                OpenAIChatCodec(),
                OpenAIResponsesCodec(),
            )
        )
        val upstream = RealUpstreamHttpClient.create()
        val selection = PoolChainManager(liveChain(apiKey, upstreamModel)).selectCandidates("default", "claude-opus-4-8").single()

        val anthropicRequest = buildJsonObject {
            put("model", JsonPrimitive("claude-opus-4-8"))
            put("max_tokens", JsonPrimitive(64))
            put("system", JsonPrimitive("You are a protocol conformance test. Reply with the single word pong."))
            put("messages", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive("Reply with only: pong"))
                })
            })
        }

        val ir = transcoder.decodeRequest(WireProtocol.ANTHROPIC_MESSAGES, anthropicRequest)
        val upstreamRequest = transcoder.encodeRequest(
            WireProtocol.OPENAI_RESPONSES,
            ir.copy(model = selection.upstreamModel, stream = false)
        )

        assertEquals(upstreamModel, upstreamRequest["model"]!!.jsonPrimitive.content)
        assertNotNull(upstreamRequest["input"], "Anthropic messages must encode to Responses input")
        assertNotNull(upstreamRequest["instructions"], "Anthropic system must encode to Responses instructions")
        assertEquals(JsonPrimitive(false), upstreamRequest["store"], "Responses requests must disable storage")

        val upstreamResponse = upstream.send(selection, upstreamRequest, emptyMap())
        assertTrue(upstreamResponse.status in 200..299, "Upstream failed: ${upstreamResponse.status} ${upstreamResponse.body}")

        val upstreamIr = transcoder.decodeResponse(WireProtocol.OPENAI_RESPONSES, upstreamResponse.body)
        val anthropicResponse = transcoder.encodeResponse(WireProtocol.ANTHROPIC_MESSAGES, upstreamIr)

        assertEquals("message", anthropicResponse["type"]!!.jsonPrimitive.content)
        assertEquals("assistant", anthropicResponse["role"]!!.jsonPrimitive.content)
        val content = anthropicResponse["content"]!!.jsonArray
        assertTrue(content.isNotEmpty(), "Anthropic response content must be non-empty")
    }

    private fun liveChain(apiKey: String, upstreamModel: String): List<PoolChainConfig> = listOf(
        PoolChainConfig(
            chainId = "default",
            modelAliases = listOf("claude-opus-4-8"),
            levels = listOf(
                PoolLevelConfig(
                    levelId = "routin-openai-responses",
                    levelIndex = 1,
                    provider = UpstreamProviderConfig(
                        providerId = "routin-ai-plan",
                        baseUrl = "https://api.routin.ai/plan",
                        protocol = WireProtocol.OPENAI_RESPONSES,
                        timeoutMs = 120_000,
                    ),
                    keys = listOf(
                        PooledKeyConfig(
                            keyId = "routin-plan-key",
                            apiKey = apiKey,
                            supportedModels = listOf("claude-opus-4-8"),
                            modelMap = mapOf("claude-opus-4-8" to upstreamModel),
                            maxConcurrency = 1,
                        )
                    )
                )
            )
        )
    )
}
