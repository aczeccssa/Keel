package com.keel.samples.aigateway.airelay.protocol.anthropic

import com.keel.contract.ai.TokenUsage
import com.keel.samples.aigateway.airelay.protocol.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json

class AnthropicMessagesCodec : ProtocolCodec {
    override val protocol: WireProtocol = WireProtocol.ANTHROPIC_MESSAGES
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- decode request ----

    override fun decodeRequest(rawJson: JsonObject): IrRequest {
        // system: string | TextBlockParam[]
        val systemString: String? = when (val sys = rawJson["system"]) {
            is JsonArray -> sys.joinToString("\n") { it.jsonObject.string("text") ?: "" }.takeIf(String::isNotBlank)
            is JsonPrimitive -> sys.contentOrNull
            else -> null
        }
        val systemBlocks = when (val sys = rawJson["system"]) {
            is JsonArray -> decodeContent(sys)
            else -> emptyList()
        }

        val messages = rawJson.arr("messages")?.mapNotNull { el ->
            val obj = el.jsonObject
            IrItem.Message(obj.string("role") ?: "user", decodeMessageContent(obj["content"]))
        } ?: emptyList()

        return IrRequest(
            model = rawJson.string("model") ?: "unknown",
            instructions = systemString,
            systemBlocks = systemBlocks,
            items = messages,
            maxOutputTokens = rawJson.int("max_tokens"),
            temperature = rawJson.double("temperature"),
            topP = rawJson.double("top_p"),
            topK = rawJson.int("top_k"),
            stopSequences = rawJson["stop_sequences"].stringList(),
            tools = decodeTools(rawJson.arr("tools")),
            toolChoice = rawJson["tool_choice"],
            stream = rawJson.boolean("stream") ?: false,
            reasoningEffort = extractReasoningEffort(rawJson["thinking"]),
            responseFormat = rawJson["output_config"]?.jsonObject?.get("format"),
            metadata = rawJson.obj("metadata")?.let { m ->
                mapOf("user_id" to (m.string("user_id") ?: ""))
            } ?: emptyMap(),
            thinking = rawJson["thinking"],
            cacheControl = rawJson["cache_control"],
            serviceTier = rawJson.string("service_tier"),
            container = rawJson.string("container"),
            inferenceGeo = rawJson.string("inference_geo"),
            extras = buildExtras(rawJson, setOf("model", "system", "messages", "max_tokens", "temperature",
                "top_p", "top_k", "stop_sequences", "tools", "tool_choice", "stream", "metadata",
                "thinking", "cache_control", "service_tier", "container", "inference_geo", "output_config",
                "context_management")),
        )
    }

    // ---- encode request ----

    override fun encodeRequest(ir: IrRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(ir.model))
        put("max_tokens", JsonPrimitive(ir.maxOutputTokens ?: 1024))
        val encodedSystemBlocks = encodeContent(ir.systemBlocks)
        when {
            encodedSystemBlocks.isNotEmpty() -> put("system", encodedSystemBlocks)
            ir.instructions != null -> put("system", JsonPrimitive(ir.instructions))
        }
        put("messages", buildJsonArray {
            ir.items.forEach { item ->
                when (item) {
                    is IrItem.Message -> {
                        val encodedContent = encodeContent(item.content)
                        if (encodedContent.isNotEmpty()) {
                            add(buildJsonObject {
                                put("role", JsonPrimitive(if (item.role == "assistant") "assistant" else "user"))
                                put("content", encodedContent)
                            })
                        }
                    }
                    is IrItem.ToolResult -> add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("tool_result"))
                                put("tool_use_id", JsonPrimitive(item.toolUseId))
                                put("content", item.content)
                                put("is_error", JsonPrimitive(item.isError))
                            })
                        })
                    })
                }
            }
        })
        ir.temperature?.let { put("temperature", JsonPrimitive(it)) }
        ir.topP?.let { put("top_p", JsonPrimitive(it)) }
        ir.topK?.let { put("top_k", JsonPrimitive(it)) }
        if (ir.stopSequences.isNotEmpty()) put("stop_sequences", stringArray(ir.stopSequences))
        if (ir.tools.isNotEmpty()) put("tools", encodeTools(ir.tools))
        ir.toolChoice?.let { put("tool_choice", it) }
        put("stream", JsonPrimitive(ir.stream))
        ir.thinking?.let { put("thinking", it) }
        ir.cacheControl?.let { put("cache_control", it) }
        ir.serviceTier?.let { put("service_tier", JsonPrimitive(it)) }
        ir.container?.let { put("container", JsonPrimitive(it)) }
        ir.inferenceGeo?.let { put("inference_geo", JsonPrimitive(it)) }
        ir.extras.forEach { (k, v) -> if (k !in RESERVED) put(k, v) }
    }

    // ---- decode response ----

    override fun decodeResponse(rawJson: JsonObject): IrResponse {
        return IrResponse(
            id = rawJson.string("id") ?: "msg-keel",
            model = rawJson.string("model") ?: "unknown",
            output = listOf(IrItem.Message("assistant", decodeContent(rawJson.arr("content")))),
            stopReason = rawJson.string("stop_reason") ?: "end_turn",
            usage = usageFromAnthropic(rawJson.obj("usage")),
        )
    }

    // ---- encode response ----

    override fun encodeResponse(ir: IrResponse): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(ir.id))
        put("type", JsonPrimitive("message"))
        put("role", JsonPrimitive("assistant"))
        put("model", JsonPrimitive(ir.model))
        put("content", buildJsonArray {
            ir.output.filterIsInstance<IrItem.Message>().forEach { item ->
                item.content.forEach { part -> add(encodePart(part)) }
            }
        })
        put("stop_reason", JsonPrimitive(ir.stopReason))
        put("usage", anthropicUsageJson(ir.usage))
    }

    // ---- decode stream ----

    override fun decodeStream(upstream: Flow<ServerSentEvent>): Flow<IrStreamEvent> = upstream.mapNotNull { event ->
        val data = event.data ?: return@mapNotNull null
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@mapNotNull null
        val eventType = event.event ?: obj.string("type")
        when (eventType) {
            "message_start" -> {
                val msg = obj.obj("message") ?: return@mapNotNull null
                IrStreamEvent.ResponseStart(
                    id = msg.string("id") ?: "",
                    model = msg.string("model") ?: "unknown",
                    usage = usageFromAnthropic(msg.obj("usage")),
                )
            }
            "content_block_start" -> {
                val idx = obj.int("index") ?: 0
                val block = obj.obj("content_block") ?: return@mapNotNull null
                val blockType = block.string("type") ?: "unknown"
                IrStreamEvent.ContentBlockStart(idx, blockType, block)
            }
            "content_block_delta" -> {
                val idx = obj.int("index") ?: 0
                val delta = obj.obj("delta") ?: return@mapNotNull null
                when (delta.string("type")) {
                    "text_delta" -> IrStreamEvent.TextDelta(idx, delta.string("text") ?: "")
                    "input_json_delta" -> IrStreamEvent.InputJsonDelta(idx, delta.string("partial_json") ?: "")
                    "thinking_delta" -> IrStreamEvent.ThinkingDelta(idx, delta.string("thinking") ?: "")
                    "signature_delta" -> IrStreamEvent.SignatureDelta(idx, delta.string("signature") ?: "")
                    "citations_delta" -> delta["citation"]?.let { IrStreamEvent.CitationsDelta(idx, it) }
                        ?: IrStreamEvent.TextDelta(idx, delta.toString())
                    else -> IrStreamEvent.TextDelta(idx, delta.toString())
                }
            }
            "content_block_stop" -> {
                IrStreamEvent.ContentBlockStop(obj.int("index") ?: 0)
            }
            "message_delta" -> {
                val d = obj.obj("delta") ?: return@mapNotNull null
                IrStreamEvent.MessageDelta(
                    stopReason = d.string("stop_reason") ?: "end_turn",
                    stopSequence = d.string("stop_sequence"),
                    usage = usageFromAnthropic(obj.obj("usage")),
                )
            }
            "message_stop" -> IrStreamEvent.ResponseDone("end_turn", TokenUsage())
            "ping" -> IrStreamEvent.Ping
            "error" -> {
                val err = obj.obj("error")
                IrStreamEvent.Error(
                    message = err?.string("message") ?: "upstream error",
                    code = err?.string("type"),
                    errorType = err?.string("type"),
                )
            }
            else -> null
        }
    }

    // ---- encode stream ----

    override fun encodeStream(events: Flow<IrStreamEvent>): Flow<ServerSentEvent> = flow {
        var messageStarted = false
        val openBlockIndices = linkedSetOf<Int>()
        var streamId = "msg-keel-stream"
        var streamModel = "unknown"

        suspend fun ensureMessageStarted() {
            if (messageStarted) return
            messageStarted = true
            emit(messageStartEvent(streamId, streamModel, TokenUsage()))
        }

        suspend fun ensureTextBlockStarted(index: Int) {
            ensureMessageStarted()
            if (index in openBlockIndices) return
            openBlockIndices.add(index)
            emit(contentBlockStartEvent(index, buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(""))
            }))
        }

        suspend fun stopContentBlock(index: Int) {
            if (!openBlockIndices.remove(index)) return
            emit(contentBlockStopEvent(index))
        }

        suspend fun stopAllContentBlocks() {
            val remaining = openBlockIndices.toList().sorted()
            remaining.forEach { stopContentBlock(it) }
        }

        events.collect { event ->
            when (event) {
                is IrStreamEvent.ResponseStart -> {
                    streamId = event.id.ifBlank { streamId }
                    streamModel = event.model.ifBlank { streamModel }
                    if (!messageStarted) {
                        messageStarted = true
                        emit(messageStartEvent(streamId, streamModel, event.usage))
                    }
                }
                is IrStreamEvent.ContentBlockStart -> {
                    ensureMessageStarted()
                    openBlockIndices.add(event.index)
                    emit(contentBlockStartEvent(event.index, event.blockData))
                }
                is IrStreamEvent.TextDelta -> {
                    ensureTextBlockStarted(event.index)
                    emit(contentBlockDeltaEvent(event.index, "text_delta") {
                        put("text", JsonPrimitive(event.delta))
                    })
                }
                is IrStreamEvent.InputJsonDelta -> {
                    ensureTextBlockStarted(event.index)
                    emit(contentBlockDeltaEvent(event.index, "input_json_delta") {
                        put("partial_json", JsonPrimitive(event.partialJson))
                    })
                }
                is IrStreamEvent.ThinkingDelta -> {
                    ensureTextBlockStarted(event.index)
                    emit(contentBlockDeltaEvent(event.index, "thinking_delta") {
                        put("thinking", JsonPrimitive(event.thinking))
                    })
                }
                is IrStreamEvent.SignatureDelta -> {
                    ensureTextBlockStarted(event.index)
                    emit(contentBlockDeltaEvent(event.index, "signature_delta") {
                        put("signature", JsonPrimitive(event.signature))
                    })
                }
                is IrStreamEvent.CitationsDelta -> {
                    ensureTextBlockStarted(event.index)
                    emit(contentBlockDeltaEvent(event.index, "citations_delta") {
                        put("citation", event.citation)
                    })
                }
                is IrStreamEvent.ContentBlockStop -> {
                    stopContentBlock(event.index)
                }
                is IrStreamEvent.MessageDelta -> {
                    ensureMessageStarted()
                    emit(messageDeltaEvent(anthropicStopReason(event.stopReason), event.stopSequence, event.usage))
                }
                is IrStreamEvent.ResponseDone -> {
                    ensureMessageStarted()
                    stopAllContentBlocks()
                    emit(messageDeltaEvent(anthropicStopReason(event.stopReason), null, event.finalUsage))
                    emit(messageStopEvent())
                }
                is IrStreamEvent.Ping -> emit(ServerSentEvent(
                    data = json.encodeToString(buildJsonObject { put("type", JsonPrimitive("ping")) }),
                    event = "ping"
                ))
                is IrStreamEvent.UsageUpdate -> {
                    ensureMessageStarted()
                    emit(messageDeltaEvent("end_turn", null, event.usage))
                }
                is IrStreamEvent.Error -> {
                    ensureMessageStarted()
                    emit(errorEvent(event))
                }
            }
        }
    }

    private fun messageStartEvent(id: String, model: String, usage: TokenUsage): ServerSentEvent = ServerSentEvent(
        data = json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("message_start"))
            put("message", buildJsonObject {
                put("id", JsonPrimitive(id))
                put("type", JsonPrimitive("message"))
                put("role", JsonPrimitive("assistant"))
                put("model", JsonPrimitive(model))
                put("content", JsonArray(emptyList()))
                put("stop_reason", JsonNull)
                put("stop_sequence", JsonNull)
                put("usage", anthropicUsageJson(usage))
            })
        }),
        event = "message_start"
    )

    private fun contentBlockStartEvent(index: Int, blockData: JsonElement): ServerSentEvent = ServerSentEvent(
        data = json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("content_block_start"))
            put("index", JsonPrimitive(index))
            put("content_block", blockData)
        }),
        event = "content_block_start"
    )

    private fun contentBlockDeltaEvent(index: Int, deltaType: String, deltaFields: JsonObjectBuilder.() -> Unit): ServerSentEvent =
        ServerSentEvent(
            data = json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("content_block_delta"))
                put("index", JsonPrimitive(index))
                put("delta", buildJsonObject {
                    put("type", JsonPrimitive(deltaType))
                    deltaFields()
                })
            }),
            event = "content_block_delta"
        )

    private fun contentBlockStopEvent(index: Int): ServerSentEvent = ServerSentEvent(
        data = json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("content_block_stop"))
            put("index", JsonPrimitive(index))
        }),
        event = "content_block_stop"
    )

    private fun messageDeltaEvent(stopReason: String, stopSequence: String?, usage: TokenUsage): ServerSentEvent = ServerSentEvent(
        data = json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("message_delta"))
            put("delta", buildJsonObject {
                put("stop_reason", JsonPrimitive(stopReason))
                stopSequence?.let { put("stop_sequence", JsonPrimitive(it)) }
            })
            put("usage", anthropicUsageJson(usage))
        }),
        event = "message_delta"
    )

    private fun messageStopEvent(): ServerSentEvent = ServerSentEvent(
        data = json.encodeToString(buildJsonObject { put("type", JsonPrimitive("message_stop")) }),
        event = "message_stop"
    )

    private fun errorEvent(event: IrStreamEvent.Error): ServerSentEvent = ServerSentEvent(
        data = json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("error"))
            put("error", buildJsonObject {
                put("type", JsonPrimitive(event.errorType ?: "api_error"))
                put("message", JsonPrimitive(event.message))
            })
        }),
        event = "error"
    )

    private fun anthropicStopReason(reason: String): String = when (reason) {
        "completed", "stop", "stop_sequence" -> "end_turn"
        "length", "max_tokens" -> "max_tokens"
        "tool_calls", "tool_use" -> "tool_use"
        else -> reason.ifBlank { "end_turn" }
    }

    // ---- content blocks ----

    private fun decodeContent(content: JsonArray?): List<IrContentPart> {
        return content?.mapNotNull { part -> decodePart(part.jsonObject) } ?: textParts(null)
    }

    private fun decodeMessageContent(content: JsonElement?): List<IrContentPart> = when (content) {
        is JsonArray -> decodeContent(content)
        is JsonPrimitive -> textParts(content.contentOrNull)
        else -> textParts(null)
    }

    private fun decodePart(obj: JsonObject): IrContentPart? {
        return when (obj.string("type")) {
            "text" -> IrContentPart.Text(
                text = obj.string("text") ?: "",
                cacheControl = obj["cache_control"],
            )
            "image" -> {
                val src = obj.obj("source")
                IrContentPart.Image(
                    mimeType = src?.string("media_type") ?: "image/jpeg",
                    dataBase64 = src?.string("data") ?: "",
                    sourceUrl = src?.string("url"),
                )
            }
            "document" -> {
                val src = obj.obj("source")
                IrContentPart.Document(
                    mediaType = src?.string("media_type") ?: "application/pdf",
                    dataBase64 = src?.string("data") ?: "",
                    sourceUrl = src?.string("url"),
                    title = obj.string("title"),
                    context = obj.string("context"),
                    citationsEnabled = obj.obj("citations")?.boolean("enabled") == true,
                    textContent = if (src?.string("type") == "text") src.string("data") else null,
                )
            }
            "tool_use" -> IrContentPart.ToolUse(
                id = obj.string("id") ?: "tool",
                name = obj.string("name") ?: "function",
                input = obj["input"] ?: JsonPrimitive("{}"),
            )
            "tool_result" -> IrContentPart.ToolResult(
                toolUseId = obj.string("tool_use_id") ?: "",
                content = obj["content"] ?: JsonPrimitive(""),
                isError = obj.boolean("is_error") ?: false,
            )
            "thinking" -> IrContentPart.Reasoning(
                summary = obj.string("thinking"),
                encryptedContent = obj.string("signature"),
            )
            "redacted_thinking" -> IrContentPart.Reasoning(
                summary = null,
                encryptedContent = obj.string("data"),
            )
            "search_result", "web_search_tool_result", "web_fetch_tool_result",
            "code_execution_tool_result", "bash_code_execution_tool_result",
            "text_editor_code_execution_tool_result", "tool_search_tool_result",
            "container_upload", "mid_conv_system",
            "server_tool_use" -> IrContentPart.Passthrough(obj.string("type") ?: "passthrough", obj)
            else -> null
        }
    }

    private fun encodeContent(parts: List<IrContentPart>): JsonArray = buildJsonArray {
        parts.forEach { part ->
            if (part is IrContentPart.Text && part.text.isEmpty()) return@forEach
            add(encodePart(part))
        }
    }

    private fun encodePart(part: IrContentPart): JsonObject = buildJsonObject {
        when (part) {
            is IrContentPart.Text -> {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(part.text))
                part.cacheControl?.let { put("cache_control", it) }
            }
            is IrContentPart.Image -> {
                put("type", JsonPrimitive("image"))
                put("source", buildJsonObject {
                    part.sourceUrl?.let {
                        put("type", JsonPrimitive("url"))
                        put("url", JsonPrimitive(it))
                    } ?: run {
                        put("type", JsonPrimitive("base64"))
                        put("media_type", JsonPrimitive(part.mimeType))
                        put("data", JsonPrimitive(part.dataBase64))
                    }
                })
            }
            is IrContentPart.ToolUse -> {
                put("type", JsonPrimitive("tool_use"))
                put("id", JsonPrimitive(part.id))
                put("name", JsonPrimitive(part.name))
                put("input", part.input)
            }
            is IrContentPart.ToolResult -> {
                put("type", JsonPrimitive("tool_result"))
                put("tool_use_id", JsonPrimitive(part.toolUseId))
                put("content", part.content)
                put("is_error", JsonPrimitive(part.isError))
            }
            is IrContentPart.Reasoning -> {
                if (part.encryptedContent != null && part.summary == null) {
                    put("type", JsonPrimitive("redacted_thinking"))
                    put("data", JsonPrimitive(part.encryptedContent))
                } else {
                    put("type", JsonPrimitive("thinking"))
                    put("thinking", JsonPrimitive(part.summary ?: ""))
                    part.encryptedContent?.let { put("signature", JsonPrimitive(it)) }
                }
            }
            is IrContentPart.Document -> {
                put("type", JsonPrimitive("document"))
                put("source", buildJsonObject {
                    part.sourceUrl?.let {
                        put("type", JsonPrimitive("url"))
                        put("url", JsonPrimitive(it))
                    } ?: part.textContent?.let {
                        put("type", JsonPrimitive("text"))
                        put("data", JsonPrimitive(it))
                    } ?: run {
                        put("type", JsonPrimitive("base64"))
                        put("media_type", JsonPrimitive(part.mediaType))
                        put("data", JsonPrimitive(part.dataBase64))
                    }
                })
                part.title?.let { put("title", JsonPrimitive(it)) }
                part.context?.let { put("context", JsonPrimitive(it)) }
                if (part.citationsEnabled) put("citations", buildJsonObject { put("enabled", JsonPrimitive(true)) })
            }
            is IrContentPart.Passthrough -> {
                // Merge the passthrough data, preserving "type"
                part.data.jsonObject.entries.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    // ---- tools ----

    private fun decodeTools(arr: JsonArray?): List<IrTool> {
        return arr?.mapNotNull { el ->
            val obj = el.jsonObject
            val type = obj.string("type") ?: "custom"
            if (type != "custom" && obj.containsKey("name") && obj.string("name") != null) {
                // Server tool: store as a tool with name = type discriminator
                IrTool(
                    name = obj.string("name") ?: type,
                    description = obj.string("description"),
                    inputSchema = obj.obj("input_schema") ?: buildJsonObject {},
                )
            } else if (obj.containsKey("name")) {
                IrTool(
                    name = obj.string("name") ?: type,
                    description = obj.string("description"),
                    inputSchema = obj.obj("input_schema") ?: buildJsonObject {},
                )
            } else {
                null
            }
        } ?: emptyList()
    }

    private fun encodeTools(tools: List<IrTool>): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                tool.description?.let { put("description", JsonPrimitive(it)) }
                put("input_schema", tool.inputSchema)
            })
        }
    }

    // ---- helpers ----

    private fun buildExtras(rawJson: JsonObject, skip: Set<String>): Map<String, JsonElement> {
        return rawJson.entries.filter { it.key !in skip }.associate { it.key to it.value }
    }

    private fun extractReasoningEffort(thinking: JsonElement?): String? {
        val obj = thinking as? JsonObject ?: return null
        val type = obj.string("type") ?: return null
        if (type == "adaptive") return "xhigh"
        if (type != "enabled") return null
        val budget = obj.int("budget_tokens") ?: return "medium"
        return when {
            budget < 4000 -> "low"
            budget < 16000 -> "medium"
            else -> "high"
        }
    }

    companion object {
        private val RESERVED = setOf(
            "model", "system", "messages", "max_tokens", "temperature", "top_p", "top_k",
            "stop_sequences", "tools", "tool_choice", "stream", "metadata", "thinking", "context_management",
            "cache_control", "service_tier", "container", "inference_geo", "output_config",
        )
    }
}
