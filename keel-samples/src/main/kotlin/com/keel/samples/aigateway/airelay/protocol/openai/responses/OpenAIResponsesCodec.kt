package com.keel.samples.aigateway.airelay.protocol.openai.responses

import com.keel.contract.ai.TokenUsage
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

class OpenAIResponsesCodec : ProtocolCodec {
    override val protocol: WireProtocol = WireProtocol.OPENAI_RESPONSES
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun decodeRequest(rawJson: JsonObject): IrRequest {
        if (rawJson.string("previous_response_id") != null) {
            throw IllegalArgumentException("previous_response_id is not supported in Phase 1; use store:false and provide full history")
        }
        val input = rawJson["input"]
        val items = when (input) {
            is JsonArray -> input.mapNotNull { decodeInputItem(it.jsonObject) }
            is JsonPrimitive -> listOf(IrItem.Message("user", textParts(input.contentOrNull)))
            else -> emptyList()
        }
        return IrRequest(
            model = rawJson.string("model") ?: "unknown",
            instructions = rawJson.string("instructions"),
            items = items,
            maxOutputTokens = rawJson.int("max_output_tokens"),
            temperature = rawJson.double("temperature"),
            topP = rawJson.double("top_p"),
            stopSequences = rawJson["stop"].stringList(),
            stream = rawJson.boolean("stream") ?: false,
            reasoningEffort = rawJson.obj("reasoning")?.string("effort"),
            responseFormat = rawJson.obj("text")?.get("format")
        )
    }

    override fun encodeRequest(ir: IrRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(ir.model))
        ir.instructions?.let { put("instructions", JsonPrimitive(it)) }
        put("input", buildJsonArray {
            ir.items.forEach { item ->
                when (item) {
                    is IrItem.Message -> add(buildJsonObject {
                        put("role", JsonPrimitive(if (item.role == "assistant") "assistant" else "user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("input_text"))
                                put("text", JsonPrimitive(textOf(item.content)))
                            })
                        })
                    })
                    is IrItem.ToolResult -> add(buildJsonObject {
                        put("type", JsonPrimitive("function_call_output"))
                        put("call_id", JsonPrimitive(item.toolUseId))
                        put("output", JsonPrimitive(item.content))
                    })
                }
            }
        })
        ir.maxOutputTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
        ir.temperature?.let { put("temperature", JsonPrimitive(it)) }
        ir.topP?.let { put("top_p", JsonPrimitive(it)) }
        if (ir.stopSequences.isNotEmpty()) put("stop", stringArray(ir.stopSequences))
        put("stream", JsonPrimitive(ir.stream))
        put("store", JsonPrimitive(false))
        ir.reasoningEffort?.let { put("reasoning", buildJsonObject { put("effort", JsonPrimitive(it)) }) }
        ir.responseFormat?.let { put("text", buildJsonObject { put("format", it) }) }
    }

    override fun decodeResponse(rawJson: JsonObject): IrResponse {
        val output = rawJson.arr("output")?.mapNotNull { decodeOutputItem(it.jsonObject) } ?: emptyList()
        return IrResponse(
            id = rawJson.string("id") ?: "resp-keel",
            model = rawJson.string("model") ?: "unknown",
            output = output.ifEmpty { listOf(IrItem.Message("assistant", textParts(rawJson.string("output_text")))) },
            stopReason = rawJson.string("status") ?: "completed",
            usage = usageFromOpenAi(rawJson.obj("usage"))
        )
    }

    override fun encodeResponse(ir: IrResponse): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(ir.id))
        put("object", JsonPrimitive("response"))
        put("created_at", JsonPrimitive(System.currentTimeMillis() / 1000))
        put("status", JsonPrimitive(if (ir.stopReason == "error") "failed" else "completed"))
        put("model", JsonPrimitive(ir.model))
        put("output", buildJsonArray {
            ir.output.filterIsInstance<IrItem.Message>().forEachIndexed { index, item ->
                add(buildJsonObject {
                    put("id", JsonPrimitive("msg_$index"))
                    put("type", JsonPrimitive("message"))
                    put("role", JsonPrimitive("assistant"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("output_text"))
                            put("text", JsonPrimitive(textOf(item.content)))
                        })
                    })
                })
            }
        })
        put("output_text", JsonPrimitive(ir.output.filterIsInstance<IrItem.Message>().joinToString("\n") { textOf(it.content) }))
        put("usage", tokenUsageJson(ir.usage))
    }

    override fun decodeStream(upstream: Flow<ServerSentEvent>): Flow<IrStreamEvent> = upstream.mapNotNull { event ->
        val data = event.data ?: return@mapNotNull null
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@mapNotNull null
        when (event.event ?: obj.string("type")) {
            "response.output_text.delta" -> IrStreamEvent.TextDelta(0, obj.string("delta") ?: "")
            "response.completed" -> IrStreamEvent.ResponseDone("completed", usageFromOpenAi(obj.obj("response")?.obj("usage") ?: obj.obj("usage")))
            "error", "response.failed" -> IrStreamEvent.Error(obj.string("message") ?: "upstream error")
            else -> null
        }
    }

    override fun encodeStream(events: Flow<IrStreamEvent>): Flow<ServerSentEvent> = events.asJsonLineEvents { event ->
        when (event) {
            is IrStreamEvent.TextDelta -> json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("response.output_text.delta"))
                put("delta", JsonPrimitive(event.delta))
            })
            is IrStreamEvent.ResponseDone -> json.encodeToString(buildJsonObject { put("type", JsonPrimitive("response.completed")) })
            is IrStreamEvent.Error -> json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("error"))
                put("message", JsonPrimitive(event.message))
            })
            else -> json.encodeToString(buildJsonObject { put("type", JsonPrimitive("response.created")) })
        }
    }

    private fun decodeInputItem(obj: JsonObject): IrItem? {
        if (obj.string("type") == "function_call_output") {
            return IrItem.ToolResult(obj.string("call_id") ?: "call", obj.string("output") ?: "")
        }
        val role = obj.string("role") ?: "user"
        return IrItem.Message(role, decodeContent(obj.arr("content")))
    }

    private fun decodeOutputItem(obj: JsonObject): IrItem? = when (obj.string("type")) {
        "message" -> IrItem.Message(obj.string("role") ?: "assistant", decodeContent(obj.arr("content")))
        "function_call" -> IrItem.Message("assistant", listOf(IrContentPart.ToolUse(obj.string("call_id") ?: "call", obj.string("name") ?: "function", obj["arguments"] ?: JsonPrimitive("{}"))))
        else -> null
    }

    private fun decodeContent(content: JsonArray?): List<IrContentPart> {
        return content?.mapNotNull { part ->
            val obj = part.jsonObject
            when (obj.string("type")) {
                "input_text", "output_text", "text" -> IrContentPart.Text(obj.string("text") ?: "")
                "reasoning" -> IrContentPart.Reasoning(summary = obj.string("summary"), encryptedContent = obj.string("encrypted_content"))
                else -> null
            }
        } ?: emptyList()
    }
}
