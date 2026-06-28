package com.keel.samples.aigateway.airelay.upstream

import com.keel.contract.ai.TokenUsage
import com.keel.samples.aigateway.airelay.pool.PoolSelection
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.protocol.obj
import com.keel.samples.aigateway.airelay.protocol.string
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import io.ktor.sse.ServerSentEvent

interface UpstreamHttpClient {
    suspend fun send(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String> = emptyMap()
    ): UpstreamResponse
    suspend fun openStream(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String> = emptyMap()
    ): OpenedUpstreamStream
    suspend fun countTokens(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String> = emptyMap()
    ): UpstreamResponse
    suspend fun proxyRaw(
        selection: PoolSelection,
        request: RawProxyRequest,
        extraHeaders: Map<String, String> = emptyMap()
    ): RawProxyResponse
    suspend fun openRawStream(
        selection: PoolSelection,
        request: RawProxyRequest,
        extraHeaders: Map<String, String> = emptyMap()
    ): OpenedUpstreamStream
}

data class UpstreamResponse(
    val status: Int,
    val body: JsonObject,
    val headers: Map<String, List<String>> = emptyMap()
)

data class OpenedUpstreamStream(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val events: Flow<ServerSentEvent>
)

/** Result of a channel reachability/auth probe (the admin "Test" action). */
data class PingResult(
    val ok: Boolean,
    val latencyMs: Long,
    val error: String?,
    val sample: String?
)

class UpstreamHttpException(
    val status: Int,
    override val message: String,
    val retryAfterSeconds: Long? = null
) : RuntimeException(message)

class MockableUpstreamHttpClient(
    private val failureOverrides: MutableMap<String, MockFailure> = mutableMapOf(),
    private val usageOverrides: MutableMap<String, com.keel.contract.ai.TokenUsage> = mutableMapOf(),
    private val streamEventOverrides: MutableMap<String, List<ServerSentEvent>> = mutableMapOf(),
    private val rawResponseOverrides: MutableMap<String, RawProxyResponse> = mutableMapOf(),
    private val realClient: UpstreamHttpClient? = null
) : UpstreamHttpClient {
    fun failKey(keyId: String, failure: MockFailure) {
        failureOverrides[keyId] = failure
    }

    fun clearFailures() {
        failureOverrides.clear()
        streamEventOverrides.clear()
        rawResponseOverrides.clear()
    }

    fun forceUsage(keyId: String, usage: com.keel.contract.ai.TokenUsage) {
        usageOverrides[keyId] = usage
    }

    fun streamEventsForKey(keyId: String, events: List<ServerSentEvent>) {
        streamEventOverrides[keyId] = events
    }

    fun rawResponseForKey(keyId: String, response: RawProxyResponse) {
        rawResponseOverrides[keyId] = response
    }

    override suspend fun send(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String>
    ): UpstreamResponse {
        failureOverrides[selection.keyState.key.keyId]?.let { throw it.toException() }
        if (!selection.provider.baseUrl.startsWith("mock://")) {
            val real = realClient
                ?: throw UpstreamHttpException(501, "Real upstream HTTP is not configured in this sample run")
            return real.send(selection, request, extraHeaders)
        }
        return UpstreamResponse(200, mockResponse(selection, request, usageOverrides[selection.keyState.key.keyId]))
    }

    override suspend fun openStream(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String>
    ): OpenedUpstreamStream {
        failureOverrides[selection.keyState.key.keyId]?.let { throw it.toException() }
        streamEventOverrides[selection.keyState.key.keyId]?.let { events ->
            return OpenedUpstreamStream(
                status = 200,
                events = flow {
                    events.forEach { emit(it) }
                }
            )
        }
        if (!selection.provider.baseUrl.startsWith("mock://")) {
            val real = realClient
                ?: throw UpstreamHttpException(501, "Real upstream HTTP streaming is not configured in this sample run")
            return real.openStream(selection, request, extraHeaders)
        }
        return OpenedUpstreamStream(
            status = 200,
            events = flow {
                when (selection.provider.protocol) {
                    WireProtocol.OPENAI_CHAT -> {
                        emit(ServerSentEvent(data = """{"choices":[{"delta":{"content":"mock "}}]}"""))
                        emit(ServerSentEvent(data = """{"choices":[{"delta":{"content":"response"}}]}"""))
                        emit(ServerSentEvent(data = "[DONE]"))
                    }
                    WireProtocol.OPENAI_RESPONSES -> {
                        emit(ServerSentEvent(data = """{"type":"response.output_text.delta","delta":"mock "}""", event = "response.output_text.delta"))
                        emit(ServerSentEvent(data = """{"type":"response.output_text.delta","delta":"response"}""", event = "response.output_text.delta"))
                        emit(ServerSentEvent(data = """{"type":"response.completed"}""", event = "response.completed"))
                    }
                    WireProtocol.ANTHROPIC_MESSAGES -> {
                        emit(ServerSentEvent(data = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"mock "}}""", event = "content_block_delta"))
                        emit(ServerSentEvent(data = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"response"}}""", event = "content_block_delta"))
                        emit(ServerSentEvent(data = """{"type":"message_stop"}""", event = "message_stop"))
                    }
                }
            }
        )
    }

    override suspend fun countTokens(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String>
    ): UpstreamResponse {
        failureOverrides[selection.keyState.key.keyId]?.let { throw it.toException() }
        if (!selection.provider.baseUrl.startsWith("mock://")) {
            val real = realClient
                ?: throw UpstreamHttpException(501, "Real upstream HTTP is not configured in this sample run")
            return real.countTokens(selection, request, extraHeaders)
        }
        val estimated = ((request["messages"]?.toString()?.length ?: 0) / 4).coerceAtLeast(1)
        return UpstreamResponse(
            status = 200,
            body = buildJsonObject { put("input_tokens", JsonPrimitive(estimated)) }
        )
    }

    private fun mockResponse(selection: PoolSelection, request: JsonObject, usageOverride: com.keel.contract.ai.TokenUsage?): JsonObject {
        val model = request.string("model") ?: "mock-model"
        val text = "Mock response from ${selection.provider.providerId} for $model"
        val usage = usageOverride ?: com.keel.contract.ai.TokenUsage(promptTokens = 12, completionTokens = 8)
        return when (selection.provider.protocol) {
            WireProtocol.OPENAI_CHAT -> buildJsonObject {
                put("id", JsonPrimitive("chatcmpl-mock"))
                put("object", JsonPrimitive("chat.completion"))
                put("model", JsonPrimitive(model))
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", JsonPrimitive(0))
                        put("message", buildJsonObject {
                            put("role", JsonPrimitive("assistant"))
                            put("content", JsonPrimitive(text))
                        })
                        put("finish_reason", JsonPrimitive("stop"))
                    })
                })
                put("usage", openAiUsage(usage))
            }
            WireProtocol.OPENAI_RESPONSES -> buildJsonObject {
                put("id", JsonPrimitive("resp-mock"))
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
                                put("text", JsonPrimitive(text))
                            })
                        })
                    })
                })
                put("output_text", JsonPrimitive(text))
                put("usage", openAiUsage(usage))
            }
            WireProtocol.ANTHROPIC_MESSAGES -> buildJsonObject {
                put("id", JsonPrimitive("msg_mock"))
                put("type", JsonPrimitive("message"))
                put("role", JsonPrimitive("assistant"))
                put("model", JsonPrimitive(model))
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text))
                    })
                })
                put("stop_reason", JsonPrimitive("end_turn"))
                put("usage", buildJsonObject {
                    put("input_tokens", JsonPrimitive(usage.promptTokens))
                    put("output_tokens", JsonPrimitive(usage.completionTokens))
                    put("cache_creation_input_tokens", JsonPrimitive(usage.cacheCreationInputTokens))
                    put("cache_read_input_tokens", JsonPrimitive(usage.cacheReadInputTokens))
                })
            }
        }
    }

    override suspend fun proxyRaw(
        selection: PoolSelection,
        request: RawProxyRequest,
        extraHeaders: Map<String, String>
    ): RawProxyResponse {
        failureOverrides[selection.keyState.key.keyId]?.let { throw it.toException() }
        rawResponseOverrides[selection.keyState.key.keyId]?.let { return it }
        if (!selection.provider.baseUrl.startsWith("mock://")) {
            val real = realClient
                ?: throw UpstreamHttpException(501, "Real upstream HTTP is not configured in this sample run")
            return real.proxyRaw(selection, request, extraHeaders)
        }
        return RawProxyResponse(
            status = 200,
            headers = mapOf("X-Mock-Upstream" to listOf(selection.provider.providerId)),
            contentType = request.contentType ?: "application/json",
            body = if (request.body.isEmpty()) "{}".toByteArray() else request.body
        )
    }

    override suspend fun openRawStream(
        selection: PoolSelection,
        request: RawProxyRequest,
        extraHeaders: Map<String, String>
    ): OpenedUpstreamStream {
        failureOverrides[selection.keyState.key.keyId]?.let { throw it.toException() }
        if (!selection.provider.baseUrl.startsWith("mock://")) {
            val real = realClient
                ?: throw UpstreamHttpException(501, "Real upstream HTTP streaming is not configured in this sample run")
            return real.openRawStream(selection, request, extraHeaders)
        }
        return OpenedUpstreamStream(
            status = 200,
            headers = mapOf("Content-Type" to listOf("text/event-stream")),
            events = flow {
                when (selection.provider.protocol) {
                    WireProtocol.OPENAI_CHAT -> {
                        emit(ServerSentEvent(data = """{"choices":[{"delta":{"content":"mock "}}]}"""))
                        emit(ServerSentEvent(data = """{"choices":[{"delta":{"content":"response"}}]}"""))
                        emit(ServerSentEvent(data = "[DONE]"))
                    }
                    WireProtocol.OPENAI_RESPONSES -> {
                        emit(ServerSentEvent(data = """{"type":"response.output_text.delta","delta":"mock "}""", event = "response.output_text.delta"))
                        emit(ServerSentEvent(data = """{"type":"response.output_text.delta","delta":"response"}""", event = "response.output_text.delta"))
                        emit(ServerSentEvent(data = """{"type":"response.completed"}""", event = "response.completed"))
                    }
                    WireProtocol.ANTHROPIC_MESSAGES -> {
                        emit(ServerSentEvent(data = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"mock "}}""", event = "content_block_delta"))
                        emit(ServerSentEvent(data = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"response"}}""", event = "content_block_delta"))
                        emit(ServerSentEvent(data = """{"type":"message_stop"}""", event = "message_stop"))
                    }
                }
            }
        )
    }

    private fun openAiUsage(usage: TokenUsage): JsonObject = buildJsonObject {
        put("prompt_tokens", JsonPrimitive(usage.promptTokens))
        put("completion_tokens", JsonPrimitive(usage.completionTokens))
        put("total_tokens", JsonPrimitive(usage.totalTokens))
        put("prompt_tokens_details", buildJsonObject { put("cached_tokens", JsonPrimitive(usage.cachedPromptTokens)) })
        put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", JsonPrimitive(usage.reasoningTokens)) })
    }
}

sealed interface MockFailure {
    fun toException(): RuntimeException

    data class Http(val status: Int, val message: String = "mock", val retryAfterSeconds: Long? = null) : MockFailure {
        override fun toException(): RuntimeException = UpstreamHttpException(status, message, retryAfterSeconds)
    }

    data class Network(val message: String = "mock network failure") : MockFailure {
        override fun toException(): RuntimeException = RuntimeException(message)
    }
}
