package com.keel.samples.aigateway.airelay.protocol.openai.responses

import com.keel.contract.ai.RequestOutcome
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UsageSource
import com.keel.samples.aigateway.airelay.protocol.obj
import com.keel.samples.aigateway.airelay.protocol.string
import com.keel.samples.aigateway.airelay.protocol.usageFromOpenAi
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

data class ResponsesStreamOutcome(
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

class ResponsesStreamObserver(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    private var latestUsage: TokenUsage = TokenUsage()
    private var usageSeen: Boolean = false
    private var errorType: String? = null
    private var errorMessage: String? = null
    private var requestId: String? = null
    private var model: String? = null
    private var completed: Boolean = false
    private var failed: Boolean = false

    fun observe(event: ServerSentEvent) {
        val data = event.data ?: return
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val type = event.event ?: obj.string("type")
        when (type) {
            "response.created", "response.in_progress" -> {
                val response = obj.obj("response") ?: obj
                requestId = response.string("id") ?: requestId
                model = response.string("model") ?: model
            }
            "response.completed" -> {
                completed = true
                val response = obj.obj("response") ?: obj
                val usage = usageFromOpenAi(response.obj("usage"))
                if (hasAnyUsage(usage)) {
                    latestUsage = usage
                    usageSeen = true
                }
            }
            "response.failed", "response.incomplete" -> {
                failed = true
                val response = obj.obj("response")
                val error = response?.obj("error")
                errorType = error?.string("type") ?: response?.string("status") ?: "api_error"
                errorMessage = error?.string("message") ?: "Responses API stream error"
            }
            "error" -> {
                failed = true
                errorType = obj.string("code") ?: obj.string("type") ?: "api_error"
                errorMessage = obj.string("message") ?: "Responses API error"
            }
        }
    }

    fun outcome(transportStatus: Int): ResponsesStreamOutcome {
        val isError = failed || errorType != null || transportStatus >= 400
        val semantic = if (isError) {
            transportStatus.takeIf { it >= 400 } ?: 500
        } else {
            200
        }
        return ResponsesStreamOutcome(
            transportStatus = transportStatus,
            semanticStatus = semantic,
            outcome = if (isError) RequestOutcome.ERROR else RequestOutcome.SUCCESS,
            errorType = errorType,
            errorMessage = errorMessage,
            usage = if (usageSeen) latestUsage else TokenUsage(),
            usageSource = if (usageSeen) UsageSource.PROVIDER else UsageSource.NONE,
            requestId = requestId,
            model = model,
        )
    }

    private fun hasAnyUsage(usage: TokenUsage): Boolean =
        usage.promptTokens > 0 || usage.completionTokens > 0 ||
            usage.cachedPromptTokens > 0 || usage.reasoningTokens > 0
}
