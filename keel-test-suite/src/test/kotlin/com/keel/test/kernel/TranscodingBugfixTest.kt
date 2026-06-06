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
}
