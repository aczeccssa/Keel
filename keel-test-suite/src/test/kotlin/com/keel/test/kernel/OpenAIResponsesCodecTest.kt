package com.keel.test.kernel

import com.keel.samples.aigateway.airelay.protocol.IrContentPart
import com.keel.samples.aigateway.airelay.protocol.IrItem
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicMessagesCodec
import com.keel.samples.aigateway.airelay.protocol.openai.responses.OpenAIResponsesCodec
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAIResponsesCodecTest {
    private val codec = OpenAIResponsesCodec()
    private val anthropicCodec = AnthropicMessagesCodec()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun requestDecodesImageAndFileInputs() {
        val request = json.parseToJsonElement(
            """
            {
              "model": "gpt-5.5",
              "instructions": "Inspect the assets",
              "previous_response_id": "resp_123",
              "tools": [{"type":"image_generation"}],
              "input": [{
                "type": "message",
                "role": "user",
                "content": [
                  {"type":"input_text","text":"Compare these"},
                  {"type":"input_image","image_url":"https://example.com/cat.png","detail":"high"},
                  {"type":"input_file","file_id":"file-abc","filename":"brief.pdf"}
                ]
              }]
            }
            """.trimIndent()
        ).jsonObject

        val ir = codec.decodeRequest(request)
        assertEquals("gpt-5.5", ir.model)
        assertEquals("resp_123", ir.extras["previous_response_id"]!!.jsonPrimitive.content)
        assertEquals(1, ir.tools.size)
        val content = (ir.items.single() as IrItem.Message).content
        assertTrue(content.any { it is IrContentPart.Image && it.sourceUrl == "https://example.com/cat.png" })
        assertTrue(content.any { it is IrContentPart.Document && it.sourceUrl == "file-abc" && it.title == "brief.pdf" })
    }

    @Test
    fun requestEncodesImageAndFileInputs() {
        val request = json.parseToJsonElement(
            """
            {
              "model": "gpt-5.5",
              "input": [{
                "type": "message",
                "role": "user",
                "content": [
                  {"type":"input_text","text":"Compare these"},
                  {"type":"input_image","image_url":"https://example.com/cat.png","detail":"high"},
                  {"type":"input_file","file_id":"file-abc","filename":"brief.pdf"}
                ]
              }]
            }
            """.trimIndent()
        ).jsonObject

        val roundTrip = codec.encodeRequest(codec.decodeRequest(request))
        val content = roundTrip["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("input_text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/cat.png", content[1].jsonObject["image_url"]!!.jsonPrimitive.content)
        assertEquals("input_file", content[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("file-abc", content[2].jsonObject["file_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicTextMessageTranscodesToStrictResponsesMessageInput() {
        val anthropicRequest = json.parseToJsonElement(
            """
            {
              "model": "claude-opus-4-8",
              "max_tokens": 64,
              "messages": [
                {"role":"user","content":"Reply with pong"}
              ]
            }
            """.trimIndent()
        ).jsonObject

        val ir = anthropicCodec.decodeRequest(anthropicRequest)
        val encoded = codec.encodeRequest(ir)
        val input = encoded["input"]!!.jsonArray.single().jsonObject
        val content = input["content"]!!.jsonArray.single().jsonObject

        assertEquals("message", input["type"]!!.jsonPrimitive.content)
        assertEquals("user", input["role"]!!.jsonPrimitive.content)
        assertEquals("input_text", content["type"]!!.jsonPrimitive.content)
        assertEquals("Reply with pong", content["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicToolTurnTranscodesToResponsesFunctionItems() {
        val anthropicRequest = json.parseToJsonElement(
            """
            {
              "model": "claude-opus-4-8",
              "max_tokens": 64,
              "tools": [{
                "name": "Read",
                "description": "Read a file",
                "input_schema": {
                  "type": "object",
                  "properties": {"file_path": {"type": "string"}},
                  "required": ["file_path"]
                }
              }],
              "tool_choice": {"type": "tool", "name": "Read"},
              "messages": [
                {"role":"user","content":"read README"},
                {"role":"assistant","content":[
                  {"type":"text","text":"I will read it."},
                  {"type":"tool_use","id":"toolu_123","name":"Read","input":{"file_path":"README.md"}}
                ]},
                {"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"toolu_123","content":"hello"}
                ]}
              ]
            }
            """.trimIndent()
        ).jsonObject

        val encoded = codec.encodeRequest(anthropicCodec.decodeRequest(anthropicRequest))
        val input = encoded["input"]!!.jsonArray
        val tool = encoded["tools"]!!.jsonArray.single().jsonObject
        val toolChoice = encoded["tool_choice"]!!.jsonObject

        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("parameters", tool.keys.single { it == "parameters" })
        assertEquals("function", toolChoice["type"]!!.jsonPrimitive.content)
        assertEquals("Read", toolChoice["name"]!!.jsonPrimitive.content)
        assertEquals("message", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call", input[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_123", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("Read", input[2].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("""{"file_path":"README.md"}""", input[2].jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[3].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_123", input[3].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("hello", input[3].jsonObject["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicToolChoiceAnyMapsToResponsesRequired() {
        val anthropicRequest = json.parseToJsonElement(
            """
            {
              "model": "claude-opus-4-8",
              "max_tokens": 64,
              "tool_choice": {"type": "any"},
              "messages": [{"role":"user","content":"use a tool"}]
            }
            """.trimIndent()
        ).jsonObject

        val encoded = codec.encodeRequest(anthropicCodec.decodeRequest(anthropicRequest))

        assertEquals("required", encoded["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun responsesStreamTranscodesToCompleteAnthropicLifecycle() = runBlocking {
        val upstreamEvents = listOf(
            ServerSentEvent(
                event = "response.output_text.delta",
                data = """{"type":"response.output_text.delta","delta":"pong"}"""
            ),
            ServerSentEvent(
                event = "response.completed",
                data = """{"type":"response.completed","response":{"id":"resp_123","model":"gpt-5.4","usage":{"input_tokens":10,"output_tokens":2}}}"""
            )
        )

        val irEvents = codec.decodeStream(upstreamEvents.asFlow())
        val anthropicEvents = anthropicCodec.encodeStream(irEvents).toList()
        val eventNames = anthropicEvents.map { it.event }

        assertEquals(
            listOf("message_start", "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop"),
            eventNames
        )
    }

    @Test
    fun responsesFunctionCallStreamTranscodesToAnthropicToolUseLifecycle() = runBlocking {
        val upstreamEvents = listOf(
            ServerSentEvent(
                event = "response.output_item.added",
                data = """{"type":"response.output_item.added","output_index":0,"item":{"id":"fc_123","type":"function_call","call_id":"call_123","name":"read_file","arguments":"","status":"in_progress"}}"""
            ),
            ServerSentEvent(
                event = "response.function_call_arguments.delta",
                data = """{"type":"response.function_call_arguments.delta","item_id":"fc_123","output_index":0,"delta":"{\"path\":"}"""
            ),
            ServerSentEvent(
                event = "response.function_call_arguments.delta",
                data = """{"type":"response.function_call_arguments.delta","item_id":"fc_123","output_index":0,"delta":"\"README.md\"}"}"""
            ),
            ServerSentEvent(
                event = "response.function_call_arguments.done",
                data = """{"type":"response.function_call_arguments.done","item_id":"fc_123","output_index":0,"name":"read_file","arguments":"{\"path\":\"README.md\"}"}"""
            ),
            ServerSentEvent(
                event = "response.completed",
                data = """{"type":"response.completed","response":{"id":"resp_123","model":"gpt-5.4","usage":{"input_tokens":10,"output_tokens":2}}}"""
            )
        )

        val anthropicEvents = anthropicCodec.encodeStream(codec.decodeStream(upstreamEvents.asFlow())).toList()
        val eventNames = anthropicEvents.map { it.event }
        val toolStart = json.parseToJsonElement(anthropicEvents[1].data!!).jsonObject["content_block"]!!.jsonObject
        val messageDelta = json.parseToJsonElement(anthropicEvents[5].data!!).jsonObject["delta"]!!.jsonObject

        assertEquals(
            listOf("message_start", "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop", "message_delta", "message_stop"),
            eventNames
        )
        assertEquals("tool_use", toolStart["type"]!!.jsonPrimitive.content)
        assertEquals("call_123", toolStart["id"]!!.jsonPrimitive.content)
        assertEquals("read_file", toolStart["name"]!!.jsonPrimitive.content)
        assertEquals("tool_use", messageDelta["stop_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun responsesMultipleFunctionCallsCloseEveryAnthropicBlock() = runBlocking {
        val upstreamEvents = listOf(
            ServerSentEvent(
                event = "response.created",
                data = """{"type":"response.created","response":{"id":"resp_123","model":"gpt-5.4"}}"""
            ),
            ServerSentEvent(
                event = "response.output_item.added",
                data = """{"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"first_tool"}}"""
            ),
            ServerSentEvent(
                event = "response.output_item.added",
                data = """{"type":"response.output_item.added","output_index":1,"item":{"id":"fc_2","type":"function_call","call_id":"call_2","name":"second_tool"}}"""
            ),
            ServerSentEvent(
                event = "response.function_call_arguments.delta",
                data = """{"type":"response.function_call_arguments.delta","item_id":"fc_2","output_index":1,"delta":"{\"b\":2}"}"""
            ),
            ServerSentEvent(
                event = "response.function_call_arguments.delta",
                data = """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"a\":1}"}"""
            ),
            ServerSentEvent(
                event = "response.function_call_arguments.done",
                data = """{"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":0}"""
            ),
            ServerSentEvent(
                event = "response.function_call_arguments.done",
                data = """{"type":"response.function_call_arguments.done","item_id":"fc_2","output_index":1}"""
            ),
            ServerSentEvent(
                event = "response.completed",
                data = """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":8,"output_tokens":4}}}"""
            )
        )

        val anthropicEvents = anthropicCodec.encodeStream(codec.decodeStream(upstreamEvents.asFlow())).toList()
        val eventNames = anthropicEvents.map { it.event }

        assertEquals(2, eventNames.count { it == "content_block_start" })
        assertEquals(2, eventNames.count { it == "content_block_stop" })
        assertEquals("message_stop", eventNames.last())
    }

    @Test
    fun responsesReasoningAndRefusalStreamTranscodesToAnthropicBlocks() = runBlocking {
        val upstreamEvents = listOf(
            ServerSentEvent(
                event = "response.created",
                data = """{"type":"response.created","response":{"id":"resp_123","model":"gpt-5.4"}}"""
            ),
            ServerSentEvent(
                event = "response.reasoning.delta",
                data = """{"type":"response.reasoning.delta","delta":"thinking"}"""
            ),
            ServerSentEvent(
                event = "response.reasoning.done",
                data = """{"type":"response.reasoning.done"}"""
            ),
            ServerSentEvent(
                event = "response.refusal.delta",
                data = """{"type":"response.refusal.delta","delta":"cannot"}"""
            ),
            ServerSentEvent(
                event = "response.refusal.done",
                data = """{"type":"response.refusal.done"}"""
            ),
            ServerSentEvent(
                event = "response.completed",
                data = """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":8,"output_tokens":4}}}"""
            )
        )

        val anthropicEvents = anthropicCodec.encodeStream(codec.decodeStream(upstreamEvents.asFlow())).toList()
        val merged = anthropicEvents.joinToString("\n") { it.data.orEmpty() }

        assertTrue(merged.contains("thinking_delta"), merged)
        assertTrue(merged.contains("text_delta"), merged)
        assertEquals("message_stop", anthropicEvents.last().event)
    }
}
