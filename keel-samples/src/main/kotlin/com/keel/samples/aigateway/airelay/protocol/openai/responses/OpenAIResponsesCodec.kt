package com.keel.samples.aigateway.airelay.protocol.openai.responses

import com.keel.contract.ai.TokenUsage
import com.keel.samples.aigateway.airelay.protocol.IrContentPart
import com.keel.samples.aigateway.airelay.protocol.IrItem
import com.keel.samples.aigateway.airelay.protocol.IrRequest
import com.keel.samples.aigateway.airelay.protocol.IrResponse
import com.keel.samples.aigateway.airelay.protocol.IrStreamEvent
import com.keel.samples.aigateway.airelay.protocol.IrTool
import com.keel.samples.aigateway.airelay.protocol.ProtocolCodec
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.protocol.arr
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
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
            tools = decodeTools(rawJson.arr("tools")),
            toolChoice = rawJson["tool_choice"],
            stream = rawJson.boolean("stream") ?: false,
            reasoningEffort = rawJson.obj("reasoning")?.string("effort"),
            responseFormat = rawJson.obj("text")?.get("format"),
            metadata = rawJson.obj("metadata")?.entries?.associate { it.key to it.value.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyMap(),
            extras = buildExtras(rawJson, setOf(
                "model", "input", "instructions", "max_output_tokens", "temperature", "top_p", "stop",
                "stream", "reasoning", "text", "tools", "tool_choice", "metadata"
            )),
        )
    }

    override fun encodeRequest(ir: IrRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(ir.model))
        ir.instructions?.let { put("instructions", JsonPrimitive(stripAnthropicBillingHeader(it))) }
        put("input", encodeInputItems(ir.items))
        ir.maxOutputTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
        ir.temperature?.let { put("temperature", JsonPrimitive(it)) }
        ir.topP?.let { put("top_p", JsonPrimitive(it)) }
        if (ir.tools.isNotEmpty()) put("tools", encodeTools(ir.tools))
        ir.toolChoice?.let { tc ->
            put("tool_choice", encodeToolChoice(tc))
            (tc as? JsonObject)?.boolean("disable_parallel_tool_use")?.let { disable ->
                put("parallel_tool_calls", JsonPrimitive(!disable))
            }
        }
        put("stream", JsonPrimitive(ir.stream))
        put("store", JsonPrimitive(false))
        ir.reasoningEffort?.let { put("reasoning", buildJsonObject { put("effort", JsonPrimitive(it)) }) }
        ir.extras.forEach { (k, v) -> if (k !in RESERVED) put(k, v) }
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

    override fun decodeStream(upstream: Flow<ServerSentEvent>): Flow<IrStreamEvent> = flow {
        var emittedToolUse = false
        var lastBlockIndex = 0
        val openBlocksByItem = linkedMapOf<String, Int>()
        val openReasoningIndices = mutableSetOf<Int>()

        suspend fun startToolUseBlock(itemId: String, outputIndex: Int, callId: String, name: String) {
            emittedToolUse = true
            lastBlockIndex = outputIndex
            openBlocksByItem[itemId] = outputIndex
            emit(IrStreamEvent.ContentBlockStart(
                index = outputIndex,
                blockType = "tool_use",
                blockData = buildJsonObject {
                    put("type", JsonPrimitive("tool_use"))
                    put("id", JsonPrimitive(callId))
                    put("name", JsonPrimitive(name))
                    put("input", buildJsonObject {})
                }
            ))
        }

        suspend fun stopBlock(itemId: String?, fallbackIndex: Int) {
            val index = itemId?.let { openBlocksByItem.remove(it) } ?: fallbackIndex
            emit(IrStreamEvent.ContentBlockStop(index))
        }

        upstream.collect { event ->
            val data = event.data ?: return@collect
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@collect
            when (event.event ?: obj.string("type")) {
                "response.created" -> {
                    val response = obj.obj("response") ?: obj
                    emit(IrStreamEvent.ResponseStart(
                        id = response.string("id") ?: "resp-keel-stream",
                        model = response.string("model") ?: "unknown",
                        usage = usageFromOpenAi(response.obj("usage")),
                    ))
                }
                "response.content_part.added" -> {
                    val part = obj.obj("part")
                    val partType = part?.string("type")
                    if (partType == "output_text" || partType == "text") {
                        val index = obj.int("content_index") ?: 0
                        lastBlockIndex = index
                        emit(IrStreamEvent.ContentBlockStart(
                            index = index,
                            blockType = "text",
                            blockData = buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(""))
                            }
                        ))
                    }
                }
                "response.output_item.added" -> {
                    val item = obj.obj("item") ?: return@collect
                    if (item.string("type") == "function_call") {
                        val itemId = item.string("id") ?: obj.string("item_id") ?: "item-${obj.int("output_index") ?: 0}"
                        val outputIndex = obj.int("output_index") ?: 0
                        val callId = item.string("call_id") ?: itemId
                        val name = item.string("name") ?: "function"
                        startToolUseBlock(itemId, outputIndex, callId, name)
                    }
                }
                "response.output_text.delta" -> {
                    val index = obj.int("content_index") ?: 0
                    lastBlockIndex = index
                    emit(IrStreamEvent.TextDelta(index, obj.string("delta") ?: ""))
                }
                "response.refusal.delta" -> {
                    val index = obj.int("content_index") ?: 0
                    lastBlockIndex = index
                    emit(IrStreamEvent.TextDelta(index, obj.string("delta") ?: obj.string("refusal") ?: ""))
                }
                "response.reasoning.delta" -> {
                    val index = obj.int("content_index") ?: obj.int("output_index") ?: 0
                    lastBlockIndex = index
                    if (openReasoningIndices.add(index)) {
                        emit(IrStreamEvent.ContentBlockStart(
                            index = index,
                            blockType = "thinking",
                            blockData = buildJsonObject {
                                put("type", JsonPrimitive("thinking"))
                                put("thinking", JsonPrimitive(""))
                            }
                        ))
                    }
                    emit(IrStreamEvent.ThinkingDelta(index, obj.string("delta") ?: obj.string("text") ?: ""))
                }
                "response.output_text.done", "response.content_part.done", "response.refusal.done", "response.reasoning.done" -> {
                    val index = obj.int("content_index") ?: lastBlockIndex
                    openReasoningIndices.remove(index)
                    stopBlock(obj.string("item_id"), index)
                }
                "response.function_call_arguments.delta" -> {
                    val index = obj.int("output_index") ?: openBlocksByItem[obj.string("item_id")].orZero()
                    lastBlockIndex = index
                    emit(IrStreamEvent.InputJsonDelta(index, obj.string("delta") ?: ""))
                }
                "response.function_call_arguments.done" -> {
                    val itemId = obj.string("item_id")
                    val outputIndex = obj.int("output_index") ?: openBlocksByItem[itemId].orZero()
                    if (itemId != null && itemId !in openBlocksByItem) {
                        startToolUseBlock(
                            itemId = itemId,
                            outputIndex = outputIndex,
                            callId = itemId,
                            name = obj.string("name") ?: "function"
                        )
                        obj.string("arguments")?.takeIf { it.isNotBlank() }?.let {
                            emit(IrStreamEvent.InputJsonDelta(outputIndex, it))
                        }
                    }
                    stopBlock(itemId, outputIndex)
                }
                "response.completed" -> {
                    openBlocksByItem.toMap().forEach { (itemId, index) -> stopBlock(itemId, index) }
                    emit(IrStreamEvent.ResponseDone(
                        stopReason = if (emittedToolUse) "tool_use" else "completed",
                        finalUsage = usageFromOpenAi(obj.obj("response")?.obj("usage") ?: obj.obj("usage"))
                    ))
                }
                "error", "response.failed" -> emit(IrStreamEvent.Error(obj.string("message") ?: "upstream error"))
            }
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    override fun encodeStream(events: Flow<IrStreamEvent>): Flow<ServerSentEvent> = flow {
        events.collect { event ->
            val (eventType, data) = when (event) {
                is IrStreamEvent.ResponseStart -> "response.created" to buildJsonObject {
                    put("type", JsonPrimitive("response.created"))
                    put("response", buildJsonObject {
                        put("id", JsonPrimitive(event.id))
                        put("model", JsonPrimitive(event.model))
                        put("status", JsonPrimitive("in_progress"))
                    })
                }
                is IrStreamEvent.ContentBlockStart -> {
                    if (event.blockType == "tool_use") {
                        val block = event.blockData.jsonObject
                        "response.output_item.added" to buildJsonObject {
                            put("type", JsonPrimitive("response.output_item.added"))
                            put("output_index", JsonPrimitive(event.index))
                            put("item", buildJsonObject {
                                put("type", JsonPrimitive("function_call"))
                                put("id", JsonPrimitive("fc_${event.index}"))
                                put("call_id", JsonPrimitive(block.string("id") ?: "call_${event.index}"))
                                put("name", JsonPrimitive(block.string("name") ?: "function"))
                                put("arguments", JsonPrimitive(""))
                                put("status", JsonPrimitive("in_progress"))
                            })
                        }
                    } else {
                        "response.content_part.added" to buildJsonObject {
                            put("type", JsonPrimitive("response.content_part.added"))
                            put("output_index", JsonPrimitive(0))
                            put("content_index", JsonPrimitive(event.index))
                            put("part", buildJsonObject {
                                put("type", JsonPrimitive("output_text"))
                                put("text", JsonPrimitive(""))
                            })
                        }
                    }
                }
                is IrStreamEvent.TextDelta -> "response.output_text.delta" to buildJsonObject {
                    put("type", JsonPrimitive("response.output_text.delta"))
                    put("content_index", JsonPrimitive(event.index))
                    put("delta", JsonPrimitive(event.delta))
                }
                is IrStreamEvent.InputJsonDelta -> "response.function_call_arguments.delta" to buildJsonObject {
                    put("type", JsonPrimitive("response.function_call_arguments.delta"))
                    put("output_index", JsonPrimitive(event.index))
                    put("delta", JsonPrimitive(event.partialJson))
                }
                is IrStreamEvent.ThinkingDelta -> "response.reasoning.delta" to buildJsonObject {
                    put("type", JsonPrimitive("response.reasoning.delta"))
                    put("output_index", JsonPrimitive(event.index))
                    put("delta", JsonPrimitive(event.thinking))
                }
                is IrStreamEvent.ContentBlockStop -> "response.output_item.done" to buildJsonObject {
                    put("type", JsonPrimitive("response.output_item.done"))
                    put("output_index", JsonPrimitive(event.index))
                }
                is IrStreamEvent.MessageDelta -> "response.completed" to buildJsonObject {
                    put("type", JsonPrimitive("response.completed"))
                    put("response", buildJsonObject {
                        put("status", JsonPrimitive(if (event.stopReason == "error") "failed" else "completed"))
                    })
                }
                is IrStreamEvent.ResponseDone -> "response.completed" to buildJsonObject {
                    put("type", JsonPrimitive("response.completed"))
                    put("response", buildJsonObject {
                        put("status", JsonPrimitive("completed"))
                        put("usage", tokenUsageJson(event.finalUsage))
                    })
                }
                is IrStreamEvent.Error -> "error" to buildJsonObject {
                    put("type", JsonPrimitive("error"))
                    put("message", JsonPrimitive(event.message))
                    event.code?.let { put("code", JsonPrimitive(it)) }
                }
                is IrStreamEvent.Ping -> "ping" to buildJsonObject {
                    put("type", JsonPrimitive("ping"))
                }
                else -> "response.created" to buildJsonObject {
                    put("type", JsonPrimitive("response.created"))
                }
            }
            emit(ServerSentEvent(
                data = json.encodeToString(data),
                event = eventType
            ))
        }
    }

    private fun decodeInputItem(obj: JsonObject): IrItem? {
        if (obj.string("type") == "function_call_output") {
            return IrItem.ToolResult(obj.string("call_id") ?: "call", obj.string("output") ?: "")
        }
        if (obj.string("type") == "function_call") {
            return IrItem.Message("assistant", listOf(IrContentPart.ToolUse(
                id = obj.string("call_id") ?: obj.string("id") ?: "call",
                name = obj.string("name") ?: "function",
                input = obj.string("arguments")
                    ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                    ?: obj["arguments"]
                    ?: buildJsonObject {}
            )))
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
                "input_image" -> IrContentPart.Image(
                    mimeType = "image/*",
                    dataBase64 = obj.string("image_url") ?: "",
                    sourceUrl = obj.string("image_url") ?: obj.string("file_id")
                )
                "input_file" -> IrContentPart.Document(
                    mediaType = obj.string("mime_type") ?: "application/octet-stream",
                    dataBase64 = obj.string("file_data").orEmpty(),
                    sourceUrl = obj.string("file_url") ?: obj.string("file_id"),
                    title = obj.string("filename")
                )
                else -> null
            }
        } ?: emptyList()
    }

    private fun encodeInputItems(items: List<IrItem>): JsonArray = buildJsonArray {
        fun JsonArrayBuilder.addMessage(role: String, content: List<JsonObject>) {
            if (content.isEmpty()) return
            add(buildJsonObject {
                put("type", JsonPrimitive("message"))
                put("role", JsonPrimitive(role))
                put("content", JsonArray(content))
            })
        }

        items.forEach { item ->
            when (item) {
                is IrItem.Message -> {
                    val role = if (item.role == "assistant") "assistant" else "user"
                    val messageContent = mutableListOf<JsonObject>()
                    item.content.forEach { part ->
                        when (part) {
                            is IrContentPart.ToolUse -> {
                                addMessage(role, messageContent.toList())
                                messageContent.clear()
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("function_call"))
                                    put("call_id", JsonPrimitive(part.id))
                                    put("name", JsonPrimitive(part.name))
                                    put("arguments", JsonPrimitive(json.encodeToString(part.input)))
                                })
                            }
                            is IrContentPart.ToolResult -> {
                                addMessage(role, messageContent.toList())
                                messageContent.clear()
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("function_call_output"))
                                    put("call_id", JsonPrimitive(part.toolUseId))
                                    put("output", JsonPrimitive(toolResultOutput(part.content)))
                                })
                            }
                            else -> messageContent.add(encodeContentPart(part, role))
                        }
                    }
                    addMessage(role, messageContent)
                }
                is IrItem.ToolResult -> add(buildJsonObject {
                    put("type", JsonPrimitive("function_call_output"))
                    put("call_id", JsonPrimitive(item.toolUseId))
                    put("output", JsonPrimitive(item.content))
                })
            }
        }
    }

    private fun encodeContent(content: List<IrContentPart>): JsonArray = buildJsonArray {
        content.forEach { part ->
            add(encodeContentPart(part, "user"))
        }
    }

    private fun encodeContentPart(part: IrContentPart, role: String): JsonObject = buildJsonObject {
        when (part) {
            is IrContentPart.Text -> {
                put("type", JsonPrimitive(if (role == "assistant") "output_text" else "input_text"))
                put("text", JsonPrimitive(part.text))
            }
            is IrContentPart.Image -> {
                put("type", JsonPrimitive("input_image"))
                part.sourceUrl?.let {
                    if (it.startsWith("file-")) put("file_id", JsonPrimitive(it)) else put("image_url", JsonPrimitive(it))
                } ?: put("image_url", JsonPrimitive(part.dataBase64))
                put("detail", JsonPrimitive("auto"))
            }
            is IrContentPart.Document -> {
                put("type", JsonPrimitive("input_file"))
                part.sourceUrl?.let {
                    if (it.startsWith("file-")) put("file_id", JsonPrimitive(it)) else put("file_url", JsonPrimitive(it))
                }
                if (part.dataBase64.isNotBlank()) put("file_data", JsonPrimitive(part.dataBase64))
                part.title?.let { put("filename", JsonPrimitive(it)) }
            }
            else -> {
                put("type", JsonPrimitive(if (role == "assistant") "output_text" else "input_text"))
                put("text", JsonPrimitive(textOf(listOf(part))))
            }
        }
    }

    private fun toolResultOutput(content: JsonElement): String {
        return (content as? JsonPrimitive)?.contentOrNull ?: json.encodeToString(content)
    }

    private fun decodeTools(arr: JsonArray?): List<IrTool> = arr?.mapNotNull { el ->
        val obj = el.jsonObject
        val name = obj.string("name") ?: obj.string("type") ?: return@mapNotNull null
        IrTool(
            name = name,
            description = obj.string("description"),
            inputSchema = obj.obj("parameters") ?: obj.obj("input_schema") ?: buildJsonObject {}
        )
    } ?: emptyList()

    private fun encodeTools(tools: List<IrTool>): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(tool.name))
                tool.description?.let { put("description", JsonPrimitive(it)) }
                put("parameters", cleanSchema(tool.inputSchema))
            })
        }
    }

    /**
     * Strip fields that cause Responses API strict tool schema validation to fail.
     * Mirrors cc-switch's clean_schema(): remove additionalProperties, unsupported JSON Schema
     * meta-keys that differ between Anthropic and OpenAI tool schema requirements.
     */
    private fun cleanSchema(schema: JsonObject): JsonObject {
        val cleaned = mutableMapOf<String, JsonElement>()
        for ((key, value) in schema.entries) {
            when (key) {
                "additionalProperties" -> {} // strip — Responses API strict mode rejects schemas with this
                "exclusiveMinimum", "exclusiveMaximum" -> {} // not supported by Responses API
                "minLength", "maxLength", "pattern" -> {} // sometimes rejected in strict mode
                else -> {
                    cleaned[key] = when (value) {
                        is JsonObject -> cleanSchema(value)
                        is JsonArray -> buildJsonArray {
                            value.forEach { item ->
                                add(if (item is JsonObject) cleanSchema(item) else item)
                            }
                        }
                        else -> value
                    }
                }
            }
        }
        return JsonObject(cleaned)
    }

    private fun encodeToolChoice(toolChoice: JsonElement): JsonElement {
        if (toolChoice is JsonPrimitive) return toolChoice
        val obj = toolChoice as? JsonObject ?: return toolChoice
        return when (obj.string("type")) {
            "any" -> JsonPrimitive("required")
            "auto" -> JsonPrimitive("auto")
            "none" -> JsonPrimitive("none")
            "tool" -> buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("name", JsonPrimitive(obj.string("name") ?: ""))
            }
            else -> toolChoice
        }
    }

    private fun buildExtras(rawJson: JsonObject, skip: Set<String>): Map<String, JsonElement> =
        rawJson.entries.filter { it.key !in skip }.associate { it.key to it.value }

    /**
     * Strip the x-anthropic-billing-header line that Claude Code injects into system prompts.
     * cc-switch does the same before forwarding to non-Anthropic upstreams.
     */
    private fun stripAnthropicBillingHeader(text: String): String {
        return text.replace(Regex("^x-anthropic-billing-header:.*\\n?", RegexOption.MULTILINE), "").trimStart()
    }

    companion object {
        private val RESERVED = setOf(
            "model", "input", "instructions", "max_output_tokens", "temperature", "top_p", "top_k", "stop",
            "stream", "reasoning", "text", "tools", "tool_choice", "metadata",
            "system", "max_tokens", "stop_sequences", "thinking", "cache_control",
            "service_tier", "container", "inference_geo", "output_config"
        )
    }
}
