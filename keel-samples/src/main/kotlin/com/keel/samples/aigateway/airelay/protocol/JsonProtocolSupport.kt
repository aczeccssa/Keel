package com.keel.samples.aigateway.airelay.protocol

import com.keel.contract.ai.TokenUsage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull
fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray

fun textOf(parts: List<IrContentPart>): String = parts.joinToString("") { part ->
    when (part) {
        is IrContentPart.Text -> part.text
        is IrContentPart.Reasoning -> part.summary.orEmpty()
        is IrContentPart.Image -> "[image:${part.mimeType}]"
        is IrContentPart.ToolUse -> ""
    }
}

fun textParts(value: String?): List<IrContentPart> = listOf(IrContentPart.Text(value ?: ""))

fun usageFromOpenAi(raw: JsonObject?): TokenUsage {
    if (raw == null) return TokenUsage()
    val prompt = raw.int("prompt_tokens") ?: raw.int("input_tokens") ?: 0
    val completion = raw.int("completion_tokens") ?: raw.int("output_tokens") ?: 0
    val promptDetails = raw.obj("prompt_tokens_details")
    val completionDetails = raw.obj("completion_tokens_details")
    return TokenUsage(
        promptTokens = prompt,
        completionTokens = completion,
        cachedPromptTokens = promptDetails?.int("cached_tokens") ?: raw.int("cached_tokens") ?: 0,
        reasoningTokens = completionDetails?.int("reasoning_tokens") ?: raw.int("reasoning_tokens") ?: 0
    )
}

fun usageFromAnthropic(raw: JsonObject?): TokenUsage {
    if (raw == null) return TokenUsage()
    return TokenUsage(
        promptTokens = raw.int("input_tokens") ?: 0,
        completionTokens = raw.int("output_tokens") ?: 0,
        cacheCreationInputTokens = raw.int("cache_creation_input_tokens") ?: 0,
        cacheReadInputTokens = raw.int("cache_read_input_tokens") ?: 0
    )
}

fun tokenUsageJson(usage: TokenUsage): JsonObject = buildJsonObject {
    put("prompt_tokens", JsonPrimitive(usage.promptTokens))
    put("completion_tokens", JsonPrimitive(usage.completionTokens))
    put("total_tokens", JsonPrimitive(usage.totalTokens))
    put("prompt_tokens_details", buildJsonObject {
        put("cached_tokens", JsonPrimitive(usage.cachedPromptTokens))
    })
    put("completion_tokens_details", buildJsonObject {
        put("reasoning_tokens", JsonPrimitive(usage.reasoningTokens))
    })
}

fun anthropicUsageJson(usage: TokenUsage): JsonObject = buildJsonObject {
    put("input_tokens", JsonPrimitive(usage.promptTokens))
    put("output_tokens", JsonPrimitive(usage.completionTokens))
    put("cache_creation_input_tokens", JsonPrimitive(usage.cacheCreationInputTokens))
    put("cache_read_input_tokens", JsonPrimitive(usage.cacheReadInputTokens))
}

fun stringArray(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

fun JsonElement?.stringList(): List<String> = when (this) {
    is JsonArray -> this.mapNotNull { it.jsonPrimitive.contentOrNull }
    is JsonPrimitive -> listOfNotNull(contentOrNull)
    else -> emptyList()
}

fun jsonNullOr(value: JsonElement?): JsonElement = value ?: JsonNull
