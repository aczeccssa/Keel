package com.keel.samples.aigateway.airelay.protocol.openai.chat

import com.keel.samples.aigateway.airelay.protocol.IrContentPart
import com.keel.samples.aigateway.airelay.protocol.IrItem
import com.keel.samples.aigateway.airelay.protocol.IrRequest
import com.keel.samples.aigateway.airelay.protocol.IrResponse
import com.keel.samples.aigateway.airelay.protocol.IrStreamEvent
import com.keel.samples.aigateway.airelay.protocol.ProtocolCodec
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
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
import com.keel.samples.aigateway.airelay.protocol.tokenUsageJson
import com.keel.samples.aigateway.airelay.protocol.usageFromOpenAi
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

class OpenAIChatCodec : ProtocolCodec {
    override val protocol: WireProtocol = WireProtocol.OPENAI_CHAT
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun decodeRequest(rawJson: JsonObject): IrRequest {
        val system = mutableListOf<String>()
        val items = mutableListOf<IrItem>()
        rawJson.arr("messages")?.forEach { element ->
            val message = element.jsonObject
            val role = message.string("role") ?: "user"
            val content = decodeContent(message["content"])
            if (role == "system") {
                system += textOf(content)
            } else if (role == "tool") {
                items += IrItem.ToolResult(
                    toolUseId = message.string("tool_call_id") ?: "tool-unknown",
                    content = textOf(content)
                )
            } else {
                val toolCalls = message.arr("tool_calls")?.mapNotNull { call ->
                    val obj = call.jsonObject
                    val function = obj.obj("function") ?: return@mapNotNull null
                    IrContentPart.ToolUse(
                        id = obj.string("id") ?: "call-unknown",
                        name = function.string("name") ?: "function",
                        input = JsonPrimitive(function.string("arguments") ?: "{}")
                    )
                } ?: emptyList()
                items += IrItem.Message(role = role, content = content + toolCalls)
            }
        }
        return IrRequest(
            model = rawJson.string("model") ?: "unknown",
            instructions = system.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            items = items,
            maxOutputTokens = rawJson.int("max_tokens"),
            temperature = rawJson.double("temperature"),
            topP = rawJson.double("top_p"),
            stopSequences = rawJson["stop"].stringList(),
            stream = rawJson.boolean("stream") ?: false,
            reasoningEffort = rawJson.string("reasoning_effort"),
            responseFormat = rawJson["response_format"]
        )
    }

    override fun encodeRequest(ir: IrRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(ir.model))
        put("messages", buildJsonArray {
            ir.instructions?.let {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(it))
                })
            }
            ir.items.forEach { item ->
                when (item) {
                    is IrItem.Message -> add(buildJsonObject {
                        put("role", JsonPrimitive(item.role))
                        val textParts = item.content.filterIsInstance<IrContentPart.Text>()
                        val toolUses = item.content.filterIsInstance<IrContentPart.ToolUse>()
                        put("content", JsonPrimitive(textParts.joinToString("") { it.text }))
                        if (toolUses.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                toolUses.forEach { tool ->
                                    add(buildJsonObject {
                                        put("id", JsonPrimitive(tool.id))
                                        put("type", JsonPrimitive("function"))
                                        put("function", buildJsonObject {
                                            put("name", JsonPrimitive(tool.name))
                                            put("arguments", JsonPrimitive(tool.input.toString()))
                                        })
                                    })
                                }
                            })
                        }
                    })
                    is IrItem.ToolResult -> add(buildJsonObject {
                        put("role", JsonPrimitive("tool"))
                        put("tool_call_id", JsonPrimitive(item.toolUseId))
                        put("content", JsonPrimitive(item.content))
                    })
                }
            }
        })
        ir.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
        ir.temperature?.let { put("temperature", JsonPrimitive(it)) }
        ir.topP?.let { put("top_p", JsonPrimitive(it)) }
        if (ir.stopSequences.isNotEmpty()) put("stop", stringArray(ir.stopSequences))
        put("stream", JsonPrimitive(ir.stream))
        ir.reasoningEffort?.let { put("reasoning_effort", JsonPrimitive(it)) }
        ir.responseFormat?.let { put("response_format", it) }
    }

    override fun decodeResponse(rawJson: JsonObject): IrResponse {
        val choice = rawJson.arr("choices")?.firstOrNull()?.jsonObject
        val message = choice?.obj("message")
        val content = decodeContent(message?.get("content"))
        val toolUses = message?.arr("tool_calls")?.mapNotNull { call ->
            val obj = call.jsonObject
            val function = obj.obj("function") ?: return@mapNotNull null
            IrContentPart.ToolUse(
                id = obj.string("id") ?: "call-unknown",
                name = function.string("name") ?: "function",
                input = JsonPrimitive(function.string("arguments") ?: "{}")
            )
        } ?: emptyList()
        return IrResponse(
            id = rawJson.string("id") ?: "chatcmpl-keel",
            model = rawJson.string("model") ?: "unknown",
            output = listOf(IrItem.Message("assistant", content + toolUses)),
            stopReason = choice?.string("finish_reason") ?: "stop",
            usage = usageFromOpenAi(rawJson.obj("usage"))
        )
    }

    override fun encodeResponse(ir: IrResponse): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(ir.id))
        put("object", JsonPrimitive("chat.completion"))
        put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
        put("model", JsonPrimitive(ir.model))
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("message", buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonPrimitive(ir.output.filterIsInstance<IrItem.Message>().joinToString("\n") { textOf(it.content) }))
                })
                put("finish_reason", JsonPrimitive(ir.stopReason))
            })
        })
        put("usage", tokenUsageJson(ir.usage))
    }

    override fun decodeStream(upstream: Flow<ServerSentEvent>): Flow<IrStreamEvent> = upstream.mapNotNull { event ->
        val data = event.data ?: return@mapNotNull null
        if (data == "[DONE]") return@mapNotNull IrStreamEvent.ResponseDone("stop", com.keel.contract.ai.TokenUsage())
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@mapNotNull null
        val choice = obj.arr("choices")?.firstOrNull()?.jsonObject
        val delta = choice?.obj("delta")
        when {
            delta?.string("content") != null -> IrStreamEvent.TextDelta(0, delta.string("content") ?: "")
            choice?.string("finish_reason") != null -> IrStreamEvent.ResponseDone(choice.string("finish_reason") ?: "stop", usageFromOpenAi(obj.obj("usage")))
            else -> null
        }
    }

    override fun encodeStream(events: Flow<IrStreamEvent>): Flow<ServerSentEvent> = events.asJsonLineEvents { event ->
        when (event) {
            is IrStreamEvent.TextDelta -> json.encodeToString(buildChatDelta(event.delta))
            is IrStreamEvent.ResponseDone -> "[DONE]"
            is IrStreamEvent.Error -> json.encodeToString(buildJsonObject { put("error", JsonPrimitive(event.message)) })
            else -> json.encodeToString(buildChatDelta(""))
        }
    }

    private fun decodeContent(value: kotlinx.serialization.json.JsonElement?): List<IrContentPart> = when (value) {
        is JsonArray -> value.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            when (obj.string("type")) {
                "text" -> IrContentPart.Text(obj.string("text") ?: "")
                "input_text" -> IrContentPart.Text(obj.string("text") ?: "")
                "image_url" -> IrContentPart.Image("image/*", obj.obj("image_url")?.string("url") ?: "")
                else -> null
            }
        }
        is JsonPrimitive -> textParts(value.contentOrNull)
        else -> textParts(null)
    }

    private fun buildChatDelta(delta: String): JsonObject = buildJsonObject {
        put("id", JsonPrimitive("chatcmpl-keel-stream"))
        put("object", JsonPrimitive("chat.completion.chunk"))
        put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
        put("model", JsonPrimitive("keel"))
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("delta", buildJsonObject { put("content", JsonPrimitive(delta)) })
            })
        })
    }
}
