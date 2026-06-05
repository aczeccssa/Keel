package com.keel.samples.aigateway.airelay.protocol.anthropic

import com.keel.contract.ai.RequestOutcome
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UsageSource
import com.keel.samples.aigateway.airelay.protocol.obj
import com.keel.samples.aigateway.airelay.protocol.string
import com.keel.samples.aigateway.airelay.protocol.usageFromAnthropic
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

data class AnthropicStreamOutcome(
    val transportStatus: Int,
    val semanticStatus: Int,
    val outcome: RequestOutcome,
    val errorType: String?,
    val errorMessage: String?,
    val usage: TokenUsage,
    val usageSource: UsageSource,
    val requestId: String?,
    val model: String?,
)

class AnthropicStreamObserver(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    private var latestUsage: TokenUsage = TokenUsage()
    private var usageSeen: Boolean = false
    private var errorType: String? = null
    private var errorMessage: String? = null
    private var requestId: String? = null
    private var model: String? = null
    private var sawStop: Boolean = false

    fun observe(event: ServerSentEvent) {
        val data = event.data ?: return
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val type = event.event ?: obj.string("type")
        when (type) {
            "message_start" -> {
                val message = obj.obj("message")
                requestId = message?.string("id") ?: requestId
                model = message?.string("model") ?: model
                val usage = usageFromAnthropic(message?.obj("usage"))
                if (hasAnyUsage(usage)) {
                    latestUsage = usage
                    usageSeen = true
                }
            }
            "message_delta" -> {
                val usage = usageFromAnthropic(obj.obj("usage"))
                if (hasAnyUsage(usage)) {
                    latestUsage = usage
                    usageSeen = true
                }
            }
            "message_stop" -> sawStop = true
            "error" -> {
                val error = obj.obj("error")
                errorType = error?.string("type") ?: "api_error"
                errorMessage = error?.string("message") ?: "Anthropic stream error"
            }
        }
    }

    fun outcome(transportStatus: Int): AnthropicStreamOutcome {
        val finalErrorType = errorType
        val isError = finalErrorType != null || transportStatus >= 400
        val semantic = if (isError) {
            AnthropicErrorMapper.semanticStatus(finalErrorType, transportStatus)
        } else {
            transportStatus
        }
        return AnthropicStreamOutcome(
            transportStatus = transportStatus,
            semanticStatus = semantic,
            outcome = if (isError) RequestOutcome.ERROR else RequestOutcome.SUCCESS,
            errorType = finalErrorType,
            errorMessage = errorMessage,
            usage = if (usageSeen) latestUsage else TokenUsage(),
            usageSource = if (usageSeen) UsageSource.PROVIDER else UsageSource.NONE,
            requestId = requestId,
            model = model,
        )
    }

    private fun hasAnyUsage(usage: TokenUsage): Boolean =
        usage.promptTokens > 0 || usage.completionTokens > 0 ||
            usage.cacheCreationInputTokens > 0 || usage.cacheReadInputTokens > 0 ||
            usage.cachedPromptTokens > 0 || usage.reasoningTokens > 0
}
