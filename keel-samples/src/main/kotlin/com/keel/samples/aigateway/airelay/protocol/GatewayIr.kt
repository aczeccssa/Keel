package com.keel.samples.aigateway.airelay.protocol

import com.keel.contract.ai.TokenUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class WireProtocol {
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES
}

@Serializable
data class IrRequest(
    val model: String,
    val instructions: String? = null,
    val systemBlocks: List<IrContentPart> = emptyList(),
    val items: List<IrItem> = emptyList(),
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val tools: List<IrTool> = emptyList(),
    val toolChoice: JsonElement? = null,
    val stream: Boolean = false,
    val reasoningEffort: String? = null,
    val responseFormat: JsonElement? = null,
    val metadata: Map<String, String> = emptyMap(),
    val thinking: JsonElement? = null,
    val cacheControl: JsonElement? = null,
    val serviceTier: String? = null,
    val container: String? = null,
    val inferenceGeo: String? = null,
    val extras: Map<String, JsonElement> = emptyMap()
)

@Serializable
sealed interface IrItem {
    val role: String

    @Serializable
    data class Message(
        override val role: String,
        val content: List<IrContentPart>
    ) : IrItem

    @Serializable
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false
    ) : IrItem {
        override val role: String = "tool"
    }
}

@Serializable
sealed interface IrContentPart {
    @Serializable
    data class Text(val text: String, val cacheControl: JsonElement? = null) : IrContentPart

    @Serializable
    data class Image(val mimeType: String, val dataBase64: String, val sourceUrl: String? = null) : IrContentPart

    @Serializable
    data class ToolUse(val id: String, val name: String, val input: JsonElement) : IrContentPart

    @Serializable
    data class ToolResult(val toolUseId: String, val content: JsonElement, val isError: Boolean = false) : IrContentPart

    @Serializable
    data class Reasoning(val summary: String? = null, val encryptedContent: String? = null, val thinking: String? = null) : IrContentPart

    @Serializable
    data class Document(
        val mediaType: String,
        val dataBase64: String = "",
        val sourceUrl: String? = null,
        val title: String? = null,
        val context: String? = null,
        val citationsEnabled: Boolean = false,
        val textContent: String? = null,
    ) : IrContentPart

    /** Carries any content block we don't have a typed representation for. */
    @Serializable
    data class Passthrough(val type: String, val data: JsonElement) : IrContentPart
}

@Serializable
data class IrTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject
)

@Serializable
data class IrResponse(
    val id: String,
    val model: String,
    val output: List<IrItem>,
    val stopReason: String,
    val usage: TokenUsage,
    val extras: Map<String, JsonElement> = emptyMap()
)

@Serializable
sealed interface IrStreamEvent {
    @Serializable
    data class ResponseStart(val id: String, val model: String, val usage: TokenUsage = TokenUsage()) : IrStreamEvent

    @Serializable
    data class ContentBlockStart(val index: Int, val blockType: String, val blockData: JsonElement) : IrStreamEvent

    @Serializable
    data class TextDelta(val index: Int, val delta: String) : IrStreamEvent

    @Serializable
    data class InputJsonDelta(val index: Int, val partialJson: String) : IrStreamEvent

    @Serializable
    data class ThinkingDelta(val index: Int, val thinking: String) : IrStreamEvent

    @Serializable
    data class SignatureDelta(val index: Int, val signature: String) : IrStreamEvent

    @Serializable
    data class CitationsDelta(val index: Int, val citation: JsonElement) : IrStreamEvent

    @Serializable
    data class ContentBlockStop(val index: Int) : IrStreamEvent

    @Serializable
    data class UsageUpdate(val usage: TokenUsage) : IrStreamEvent

    @Serializable
    data class MessageDelta(val stopReason: String, val stopSequence: String?, val usage: TokenUsage) : IrStreamEvent

    @Serializable
    data class ResponseDone(val stopReason: String, val finalUsage: TokenUsage) : IrStreamEvent

    /** Upstream keepalive — pass through to client. */
    @Serializable
    data object Ping : IrStreamEvent

    @Serializable
    data class Error(val message: String, val code: String? = null, val errorType: String? = null) : IrStreamEvent
}
