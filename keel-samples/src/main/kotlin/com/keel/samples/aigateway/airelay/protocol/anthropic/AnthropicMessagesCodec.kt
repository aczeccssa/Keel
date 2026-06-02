package com.keel.samples.aigateway.airelay.protocol.anthropic

import com.keel.contract.ai.TokenUsage
import com.keel.samples.aigateway.airelay.protocol.IrContentPart
import com.keel.samples.aigateway.airelay.protocol.IrItem
import com.keel.samples.aigateway.airelay.protocol.IrRequest
import com.keel.samples.aigateway.airelay.protocol.IrResponse
import com.keel.samples.aigateway.airelay.protocol.IrStreamEvent
import com.keel.samples.aigateway.airelay.protocol.ProtocolCodec
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.protocol.anthropicUsageJson
import com.keel.samples.aigateway.airelay.protocol.arr
import com.keel.samples.aigateway.airelay.protocol.asJsonLineEvents
import com.keel.samples.aigateway.airelay.protocol.boolean
import com.keel.samples.aigateway.airelay.protocol.double
import com.keel.samples.aigateway.airelay.protocol.int
import com.keel.samples.aigateway.airelay.protocol.obj
import com.keel.samples.aigateway.airelay.protocol.string
import com.keel.samples.aigateway.airelay.protocol.stringArray
import com.keel.samples.aigateway.airelay.protocol.stringList
import com.keel.samples.aigateway.airelay.protocol.textOf
import com.keel.samples.aigateway.airelay.protocol.textParts
import com.keel.samples.aigateway.airelay.protocol.usageFromAnthropic
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AnthropicMessagesCodec : ProtocolCodec {
    override val protocol: WireProtocol = WireProtocol.ANTHROPIC_MESSAGES
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun decodeRequest(rawJson: JsonObject): IrRequest {
        val messages = rawJson.arr("messages")?.mapNotNull { element ->
            val obj = element.jsonObject
            IrItem.Message(obj.string("role") ?: "user", decodeContent(obj.arr("content")))
        } ?: emptyList()
        return IrRequest(
            model = rawJson.string("model") ?: "unknown",
            instructions = when (val system = rawJson["system"]) {
                is JsonArray -> system.joinToString("\n") { it.jsonObject.string("text") ?: "" }
                is JsonPrimitive -> system.contentOrNull
                else -> null
            },
            items = messages,
            maxOutputTokens = rawJson.int("max_tokens"),
            temperature = rawJson.double("temperature"),
            topP = rawJson.double("top_p"),
            stopSequences = rawJson["stop_sequences"].stringList(),
            stream = rawJson.boolean("stream") ?: false
        )
    }

    override fun encodeRequest(ir: IrRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(ir.model))
        put("max_tokens", JsonPrimitive(ir.maxOutputTokens ?: 1024))
        ir.instructions?.let { put("system", JsonPrimitive(it)) }
        put("messages", buildJsonArray {
            ir.items.forEach { item ->
                when (item) {
                    is IrItem.Message -> add(buildJsonObject {
                        put("role", JsonPrimitive(if (item.role == "assistant") "assistant" else "user"))
                        put("content", encodeContent(item.content))
                    })
                    is IrItem.ToolResult -> add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("tool_result"))
                                put("tool_use_id", JsonPrimitive(item.toolUseId))
                                put("content", JsonPrimitive(item.content))
                                put("is_error", JsonPrimitive(item.isError))
                            })
                        })
                    })
                }
            }
        })
        ir.temperature?.let { put("temperature", JsonPrimitive(it)) }
        ir.topP?.let { put("top_p", JsonPrimitive(it)) }
        if (ir.stopSequences.isNotEmpty()) put("stop_sequences", stringArray(ir.stopSequences))
        put("stream", JsonPrimitive(ir.stream))
    }

    override fun decodeResponse(rawJson: JsonObject): IrResponse {
        return IrResponse(
            id = rawJson.string("id") ?: "msg-keel",
            model = rawJson.string("model") ?: "unknown",
            output = listOf(IrItem.Message("assistant", decodeContent(rawJson.arr("content")))),
            stopReason = rawJson.string("stop_reason") ?: "end_turn",
            usage = usageFromAnthropic(rawJson.obj("usage"))
        )
    }

    override fun encodeResponse(ir: IrResponse): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(ir.id))
        put("type", JsonPrimitive("message"))
        put("role", JsonPrimitive("assistant"))
        put("model", JsonPrimitive(ir.model))
        put("content", buildJsonArray {
            ir.output.filterIsInstance<IrItem.Message>().forEach { item ->
                item.content.forEach { part ->
                    when (part) {
                        is IrContentPart.Text -> add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(part.text))
                        })
                        is IrContentPart.ToolUse -> add(buildJsonObject {
                            put("type", JsonPrimitive("tool_use"))
                            put("id", JsonPrimitive(part.id))
                            put("name", JsonPrimitive(part.name))
                            put("input", part.input)
                        })
                        is IrContentPart.Reasoning -> add(buildJsonObject {
                            put("type", JsonPrimitive("thinking"))
                            put("thinking", JsonPrimitive(part.summary ?: ""))
                            part.encryptedContent?.let { put("signature", JsonPrimitive(it)) }
                        })
                        is IrContentPart.Image -> Unit
                    }
                }
            }
        })
        put("stop_reason", JsonPrimitive(ir.stopReason))
        put("usage", anthropicUsageJson(ir.usage))
    }

    override fun decodeStream(upstream: Flow<ServerSentEvent>): Flow<IrStreamEvent> = upstream.mapNotNull { event ->
        val data = event.data ?: return@mapNotNull null
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@mapNotNull null
        when (event.event ?: obj.string("type")) {
            "content_block_delta" -> obj.obj("delta")?.string("text")?.let { IrStreamEvent.TextDelta(0, it) }
            "message_delta" -> IrStreamEvent.UsageUpdate(usageFromAnthropic(obj.obj("usage")))
            "message_stop" -> IrStreamEvent.ResponseDone("end_turn", TokenUsage())
            "error" -> IrStreamEvent.Error(obj.obj("error")?.string("message") ?: "upstream error")
            else -> null
        }
    }

    override fun encodeStream(events: Flow<IrStreamEvent>): Flow<ServerSentEvent> = events.asJsonLineEvents { event ->
        when (event) {
            is IrStreamEvent.TextDelta -> json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("content_block_delta"))
                put("delta", buildJsonObject {
                    put("type", JsonPrimitive("text_delta"))
                    put("text", JsonPrimitive(event.delta))
                })
            })
            is IrStreamEvent.ResponseDone -> json.encodeToString(buildJsonObject { put("type", JsonPrimitive("message_stop")) })
            is IrStreamEvent.Error -> json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("error"))
                put("error", buildJsonObject { put("message", JsonPrimitive(event.message)) })
            })
            else -> json.encodeToString(buildJsonObject { put("type", JsonPrimitive("message_start")) })
        }
    }

    private fun decodeContent(content: JsonArray?): List<IrContentPart> {
        return content?.mapNotNull { part ->
            val obj = part.jsonObject
            when (obj.string("type")) {
                "text" -> IrContentPart.Text(obj.string("text") ?: "")
                "tool_use" -> IrContentPart.ToolUse(obj.string("id") ?: "tool", obj.string("name") ?: "function", obj["input"] ?: JsonPrimitive("{}"))
                "thinking" -> IrContentPart.Reasoning(summary = obj.string("thinking"), encryptedContent = obj.string("signature"))
                "tool_result" -> IrContentPart.Text(obj.string("content") ?: "")
                else -> null
            }
        } ?: textParts(null)
    }

    private fun encodeContent(parts: List<IrContentPart>): JsonArray = buildJsonArray {
        parts.forEach { part ->
            when (part) {
                is IrContentPart.Text -> add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(part.text))
                })
                is IrContentPart.ToolUse -> add(buildJsonObject {
                    put("type", JsonPrimitive("tool_use"))
                    put("id", JsonPrimitive(part.id))
                    put("name", JsonPrimitive(part.name))
                    put("input", part.input)
                })
                is IrContentPart.Reasoning -> add(buildJsonObject {
                    put("type", JsonPrimitive("thinking"))
                    put("thinking", JsonPrimitive(part.summary ?: ""))
                })
                is IrContentPart.Image -> add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("[image:${part.mimeType}]"))
                })
            }
        }
    }
}
