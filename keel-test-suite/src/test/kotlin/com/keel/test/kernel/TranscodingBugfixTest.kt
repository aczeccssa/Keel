package com.keel.test.kernel

import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.protocol.openai.responses.OpenAIResponsesCodec
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicMessagesCodec
import com.keel.samples.aigateway.airelay.protocol.IrContentPart
import com.keel.samples.aigateway.airelay.protocol.IrItem
import com.keel.samples.aigateway.airelay.protocol.IrRequest
import com.keel.samples.aigateway.airelay.protocol.IrResponse
import com.keel.samples.aigateway.airelay.protocol.IrStreamEvent
import com.keel.samples.aigateway.airelay.protocol.IrTool
import com.keel.samples.aigateway.airelay.protocol.ProtocolTranscoder
import com.keel.samples.aigateway.airelay.protocol.string
import com.keel.samples.aigateway.airelay.protocol.openai.responses.ResponsesStreamObserver
import com.keel.contract.ai.RequestOutcome
import com.keel.contract.ai.TokenUsage
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-cutting tests for transcoding bugfixes (Bugs #2-#8).
 */
class TranscodingBugfixTest {
    private val anthropicCodec = AnthropicMessagesCodec()
    private val responsesCodec = OpenAIResponsesCodec()
    private val transcoder = ProtocolTranscoder(listOf(anthropicCodec, responsesCodec))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun responsesEncodedRequestDoesNotContainAnthropicFields() {
        val anthropicRequest = json.parseToJsonElement("""
            {
                "model": "claude-opus-4-8",
                "max_tokens": 8192,
                "system": "You are helpful",
                "messages": [{"role": "user", "content": "hello"}],
                "stream": true
            }
        """.trimIndent()).jsonObject
        val ir = anthropicCodec.decodeRequest(anthropicRequest)
        val responsesRequest = responsesCodec.encodeRequest(ir)
        assertNull(responsesRequest["system"], "Anthropic 'system' field must not leak to Responses request")
        assertNull(responsesRequest["max_tokens"], "Anthropic 'max_tokens' must become 'max_output_tokens'")
        assertNull(responsesRequest["messages"], "Anthropic 'messages' must become 'input'")
        assertNotNull(responsesRequest["instructions"], "system must map to instructions")
        assertNotNull(responsesRequest["input"], "messages must map to input")
        assertNotNull(responsesRequest["max_output_tokens"], "max_tokens must map to max_output_tokens")
    }

    @Test
    fun responsesStreamErrorEventDecodesAsIrError() = runBlocking {
        val errorSse = ServerSentEvent(
            data = """{"type":"error","message":"Invalid request body","code":"400"}""",
            event = "error"
        )
        val irEvents = responsesCodec.decodeStream(kotlinx.coroutines.flow.flowOf(errorSse)).toList()
        assertEquals(1, irEvents.size)
        val error = irEvents.single() as IrStreamEvent.Error
        assertEquals("Invalid request body", error.message)
    }

    @Test
    fun responsesStreamObserverDetectsCompletedStream() {
        val observer = ResponsesStreamObserver()
        observer.observe(ServerSentEvent(
            data = """{"type":"response.created","response":{"id":"resp_1","model":"gpt-5.4","status":"in_progress"}}""",
            event = "response.created"
        ))
        observer.observe(ServerSentEvent(
            data = """{"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.4","status":"completed","usage":{"input_tokens":10,"output_tokens":20}}}""",
            event = "response.completed"
        ))
        val outcome = observer.outcome(transportStatus = 200)
        assertEquals(RequestOutcome.SUCCESS, outcome.outcome)
        assertEquals(200, outcome.semanticStatus)
        assertEquals("resp_1", outcome.requestId)
        assertEquals("gpt-5.4", outcome.model)
        assertEquals(10, outcome.usage.promptTokens)
        assertEquals(20, outcome.usage.completionTokens)
    }

    @Test
    fun responsesStreamObserverDetectsErrorStream() {
        val observer = ResponsesStreamObserver()
        observer.observe(ServerSentEvent(
            data = """{"type":"error","code":"invalid_request_error","message":"bad input"}""",
            event = "error"
        ))
        val outcome = observer.outcome(transportStatus = 200)
        assertEquals(RequestOutcome.ERROR, outcome.outcome)
        assertEquals(500, outcome.semanticStatus)
        assertEquals("invalid_request_error", outcome.errorType)
        assertEquals("bad input", outcome.errorMessage)
    }

    @Test
    fun responsesStreamObserverDetectsFailedResponse() {
        val observer = ResponsesStreamObserver()
        observer.observe(ServerSentEvent(
            data = """{"type":"response.failed","response":{"id":"resp_2","status":"failed","error":{"type":"server_error","message":"internal error"}}}""",
            event = "response.failed"
        ))
        val outcome = observer.outcome(transportStatus = 200)
        assertEquals(RequestOutcome.ERROR, outcome.outcome)
        assertEquals(500, outcome.semanticStatus)
        assertEquals("server_error", outcome.errorType)
    }

    @Test
    fun upstreamErrorResponseShouldNotDecodeAsSuccess() {
        val errorBody = json.parseToJsonElement("""
            {"error": {"message": "Internal server error", "type": "server_error"}}
        """.trimIndent()).jsonObject

        val ir = responsesCodec.decodeResponse(errorBody)
        val outputText = ir.output.filterIsInstance<IrItem.Message>()
            .flatMap { it.content }
            .filterIsInstance<IrContentPart.Text>()
            .joinToString("") { it.text }
        assertTrue(outputText.isBlank(), "Error response body should not produce meaningful output text")
    }

    @Test
    fun responsesEncodeStreamHandlesToolUseEvents() = runBlocking {
        val events = listOf(
            IrStreamEvent.ResponseStart("resp_1", "gpt-5.4"),
            IrStreamEvent.ContentBlockStart(0, "tool_use", buildJsonObject {
                put("type", JsonPrimitive("tool_use"))
                put("id", JsonPrimitive("call_abc"))
                put("name", JsonPrimitive("Read"))
                put("input", buildJsonObject {})
            }),
            IrStreamEvent.InputJsonDelta(0, """{"file_path":"""),
            IrStreamEvent.InputJsonDelta(0, """"test.kt"}"""),
            IrStreamEvent.ContentBlockStop(0),
            IrStreamEvent.ResponseDone("tool_use", TokenUsage(promptTokens = 100, completionTokens = 50)),
        )
        val sseEvents = responsesCodec.encodeStream(events.asFlow()).toList()

        val eventTypes = sseEvents.map { it.event }
        assertTrue("response.created" in eventTypes, "Should have response.created")
        assertTrue("response.output_item.added" in eventTypes, "Should have output_item.added for tool_use")
        assertTrue("response.function_call_arguments.delta" in eventTypes, "Should have function_call_arguments.delta")
        assertTrue("response.completed" in eventTypes, "Should have response.completed")

        val argDeltaEvents = sseEvents.filter { it.event == "response.function_call_arguments.delta" }
        assertEquals(2, argDeltaEvents.size, "Should have 2 argument delta events")
    }

    @Test
    fun responsesEncodeStreamHandlesTextEvents() = runBlocking {
        val events = listOf(
            IrStreamEvent.ResponseStart("resp_2", "gpt-5.4"),
            IrStreamEvent.ContentBlockStart(0, "text", buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(""))
            }),
            IrStreamEvent.TextDelta(0, "Hello"),
            IrStreamEvent.TextDelta(0, " world"),
            IrStreamEvent.ContentBlockStop(0),
            IrStreamEvent.ResponseDone("end_turn", TokenUsage(promptTokens = 10, completionTokens = 5)),
        )
        val sseEvents = responsesCodec.encodeStream(events.asFlow()).toList()
        val eventTypes = sseEvents.map { it.event }
        assertTrue("response.content_part.added" in eventTypes, "Should have content_part.added for text")
        assertTrue("response.output_text.delta" in eventTypes, "Should have output_text.delta")
        val textDeltas = sseEvents.filter { it.event == "response.output_text.delta" }
        assertEquals(2, textDeltas.size)
    }

    @Test
    fun anthropicDisableParallelToolUseMapsToResponsesParallelToolCalls() {
        val anthropicRequest = json.parseToJsonElement("""
            {
                "model": "claude-opus-4-8",
                "max_tokens": 8192,
                "messages": [{"role": "user", "content": "hello"}],
                "tools": [{"name": "read_file", "description": "Read a file", "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}],
                "tool_choice": {"type": "auto", "disable_parallel_tool_use": true}
            }
        """.trimIndent()).jsonObject

        val ir = anthropicCodec.decodeRequest(anthropicRequest)
        val responsesRequest = responsesCodec.encodeRequest(ir)

        assertEquals(JsonPrimitive("auto"), responsesRequest["tool_choice"],
            "tool_choice type=auto should map to string 'auto'")
        assertEquals(JsonPrimitive(false), responsesRequest["parallel_tool_calls"],
            "disable_parallel_tool_use=true should map to parallel_tool_calls=false")
    }

    @Test
    fun anthropicToolChoiceWithoutDisableOmitsParallelToolCalls() {
        val anthropicRequest = json.parseToJsonElement("""
            {
                "model": "claude-opus-4-8",
                "max_tokens": 8192,
                "messages": [{"role": "user", "content": "hello"}],
                "tools": [{"name": "read_file", "description": "Read", "input_schema": {"type": "object", "properties": {}, "required": []}}],
                "tool_choice": {"type": "auto"}
            }
        """.trimIndent()).jsonObject

        val ir = anthropicCodec.decodeRequest(anthropicRequest)
        val responsesRequest = responsesCodec.encodeRequest(ir)

        assertNull(responsesRequest["parallel_tool_calls"],
            "Without disable_parallel_tool_use, parallel_tool_calls should not be set")
    }

    @Test
    fun fullAnthropicToResponsesRoundTripWithThinkingAndTools() {
        val anthropicRequest = json.parseToJsonElement("""
            {
                "model": "claude-opus-4-8",
                "max_tokens": 16384,
                "thinking": {"type": "enabled", "budget_tokens": 10240},
                "system": [
                    {"type": "text", "text": "You are a helpful assistant."},
                    {"type": "text", "text": "Follow user instructions."}
                ],
                "messages": [
                    {"role": "user", "content": "Read the file test.kt"},
                    {"role": "assistant", "content": [
                        {"type": "tool_use", "id": "toolu_1", "name": "Read", "input": {"file_path": "test.kt"}}
                    ]},
                    {"role": "user", "content": [
                        {"type": "tool_result", "tool_use_id": "toolu_1", "content": "fun main() {}"}
                    ]},
                    {"role": "assistant", "content": "The file contains a main function."}
                ],
                "tools": [
                    {"name": "Read", "description": "Read a file", "input_schema": {"type": "object", "properties": {"file_path": {"type": "string"}}, "required": ["file_path"]}}
                ],
                "tool_choice": {"type": "auto", "disable_parallel_tool_use": true},
                "stream": true
            }
        """.trimIndent()).jsonObject

        val ir = anthropicCodec.decodeRequest(anthropicRequest)

        assertEquals("medium", ir.reasoningEffort, "budget_tokens=10240 should map to medium")

        val responsesRequest = responsesCodec.encodeRequest(ir)

        assertEquals("claude-opus-4-8", responsesRequest.string("model"))
        assertNotNull(responsesRequest["instructions"], "system should map to instructions")
        assertTrue(responsesRequest["instructions"]!!.jsonPrimitive.content.contains("helpful assistant"))
        assertNotNull(responsesRequest["input"], "messages should map to input")
        assertEquals(JsonPrimitive(16384), responsesRequest["max_output_tokens"],
            "max_tokens should map to max_output_tokens")
        assertEquals(JsonPrimitive(false), responsesRequest["parallel_tool_calls"])
        assertNotNull(responsesRequest["reasoning"], "thinking should produce reasoning field")
        assertEquals("medium", responsesRequest["reasoning"]!!.jsonObject.string("effort"))

        val tools = responsesRequest["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val tool = tools[0].jsonObject
        assertEquals("function", tool.string("type"))
        assertEquals("Read", tool.string("name"))
        assertNotNull(tool["parameters"], "input_schema should map to parameters")

        val input = responsesRequest["input"]!!.jsonArray
        assertTrue(input.any { it.jsonObject.string("type") == "function_call" },
            "tool_use should become function_call in input")
        assertTrue(input.any { it.jsonObject.string("type") == "function_call_output" },
            "tool_result should become function_call_output in input")

        val funcCall = input.first { it.jsonObject.string("type") == "function_call" }.jsonObject
        val args = funcCall["arguments"]!!.jsonPrimitive.content
        assertTrue(args.contains("file_path"), "arguments should be a JSON string, not object")
    }
}
