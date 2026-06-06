package com.keel.test.kernel

import com.keel.samples.aigateway.airelay.protocol.IrContentPart
import com.keel.samples.aigateway.airelay.protocol.IrItem
import com.keel.samples.aigateway.airelay.protocol.IrRequest
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicMessagesCodec
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnthropicCodecConformanceTest {
    private val codec = AnthropicMessagesCodec()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun decodeTextOnlyRequest() {
        val raw = """{"model":"claude-sonnet-4-20250514","max_tokens":256,"messages":[{"role":"user","content":"Hello"}]}"""
        val ir = codec.decodeRequest(json.parseToJsonElement(raw).jsonObject)
        assertEquals("claude-sonnet-4-20250514", ir.model)
        assertEquals(256, ir.maxOutputTokens)
        assertEquals(1, ir.items.size)
        val msg = ir.items[0] as IrItem.Message
        assertEquals("user", msg.role)
        assertEquals(1, msg.content.size)
        assertTrue(msg.content[0] is IrContentPart.Text)
        assertEquals("Hello", (msg.content[0] as IrContentPart.Text).text)
    }

    @Test
    fun encodeRequestRoundtrip() {
        val raw = """{"model":"claude-haiku","max_tokens":100,"messages":[{"role":"user","content":"ping"}]}"""
        val ir = codec.decodeRequest(json.parseToJsonElement(raw).jsonObject)
        val encoded = codec.encodeRequest(ir)
        assertEquals("claude-haiku", encoded["model"]?.jsonPrimitive?.content)
        assertEquals(100, encoded["max_tokens"]?.jsonPrimitive?.int)
    }

    @Test
    fun decodeRequestWithSystemString() {
        val raw = """{"model":"claude-opus","max_tokens":10,"system":"You are helpful.","messages":[{"role":"user","content":"hi"}]}"""
        val ir = codec.decodeRequest(json.parseToJsonElement(raw).jsonObject)
        assertEquals("You are helpful.", ir.instructions)
    }

    @Test
    fun decodeRequestWithStreaming() {
        val raw = """{"model":"claude-opus","max_tokens":10,"stream":true,"messages":[{"role":"user","content":"x"}]}"""
        val ir = codec.decodeRequest(json.parseToJsonElement(raw).jsonObject)
        assertTrue(ir.stream)
    }

    @Test
    fun decodeRequestWithToolUseAndToolResult() {
        val raw = """
            {"model":"claude-opus","max_tokens":200,
             "messages":[
               {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu_001","content":"22 deg"}]},
               {"role":"assistant","content":[{"type":"tool_use","id":"tu_002","name":"get_weather","input":{"city":"Paris"}}]}
             ]}
        """.trimIndent().replace("\n", "")
        val ir = codec.decodeRequest(json.parseToJsonElement(raw).jsonObject)
        assertEquals(2, ir.items.size)

        val userMsg = ir.items[0] as IrItem.Message
        assertEquals("user", userMsg.role)
        assertTrue(userMsg.content[0] is IrContentPart.ToolResult)
        val tr = userMsg.content[0] as IrContentPart.ToolResult
        assertEquals("tu_001", tr.toolUseId)

        val asstMsg = ir.items[1] as IrItem.Message
        assertTrue(asstMsg.content[0] is IrContentPart.ToolUse)
        val tu = asstMsg.content[0] as IrContentPart.ToolUse
        assertEquals("get_weather", tu.name)
        assertEquals("tu_002", tu.id)
    }

    @Test
    fun decodeRequestWithImage() {
        val raw = """
            {"model":"claude-opus","max_tokens":10,"messages":[{"role":"user","content":[
              {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}
            ]}]}
        """.trimIndent().replace("\n", "")
        val ir = codec.decodeRequest(json.parseToJsonElement(raw).jsonObject)
        val part = (ir.items[0] as IrItem.Message).content[0]
        assertTrue(part is IrContentPart.Image)
        assertEquals("image/png", (part as IrContentPart.Image).mimeType)
    }

    @Test
    fun decodeResponseRoundtrip() {
        val raw = """
            {"id":"msg_123","type":"message","role":"assistant","model":"claude-opus",
             "content":[{"type":"text","text":"Hello!"},{"type":"tool_use","id":"tu_01","name":"search","input":{"q":"test"}}],
             "stop_reason":"tool_use","usage":{"input_tokens":50,"output_tokens":30}}
        """.trimIndent().replace("\n", "")
        val ir = codec.decodeResponse(json.parseToJsonElement(raw).jsonObject)
        assertEquals("msg_123", ir.id)
        assertEquals("tool_use", ir.stopReason)
        assertEquals(50, ir.usage.promptTokens)
        assertEquals(30, ir.usage.completionTokens)

        // Encode back
        val encoded = codec.encodeResponse(ir)
        assertEquals("msg_123", encoded["id"]?.jsonPrimitive?.content)
        assertEquals("message", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("tool_use", encoded["stop_reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun decodeRequestWithMetadata() {
        val raw = """
            {"model":"claude","max_tokens":10,"metadata":{"user_id":"user-42"},"messages":[{"role":"user","content":"x"}]}
        """.trimIndent().replace("\n", "")
        val ir = codec.decodeRequest(json.parseToJsonElement(raw).jsonObject)
        assertEquals("user-42", ir.metadata["user_id"])
    }

    @Test
    fun decodeRequestWithCacheControl() {
        val raw = """
            {"model":"claude","max_tokens":10,
             "messages":[{"role":"user","content":[{"type":"text","text":"long prompt","cache_control":{"type":"ephemeral"}}]}]}
        """.trimIndent().replace("\n", "")
        val ir = codec.decodeRequest(json.parseToJsonElement(raw).jsonObject)
        val part = (ir.items[0] as IrItem.Message).content[0]
        assertTrue(part is IrContentPart.Text)
        assertNotNull((part as IrContentPart.Text).cacheControl)
    }

    @Test
    fun decodeResponseWithThinking() {
        val raw = """
            {"id":"msg_001","type":"message","role":"assistant","model":"claude-opus",
             "content":[{"type":"thinking","thinking":"Let me think about this...","signature":"sig_abc"}],
             "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent().replace("\n", "")
        val ir = codec.decodeResponse(json.parseToJsonElement(raw).jsonObject)
        val part = (ir.output[0] as IrItem.Message).content[0]
        assertTrue(part is IrContentPart.Reasoning)
        assertEquals("Let me think about this...", (part as IrContentPart.Reasoning).summary)
        assertEquals("sig_abc", part.encryptedContent)
    }

    @Test
    fun decodeRequestConvertsThinkingToReasoningEffort() {
        val request = json.parseToJsonElement("""
            {
                "model": "claude-opus-4-8",
                "max_tokens": 16384,
                "thinking": {"type": "enabled", "budget_tokens": 10240},
                "messages": [{"role": "user", "content": "hello"}]
            }
        """.trimIndent()).jsonObject
        val ir = codec.decodeRequest(request)
        assertEquals("medium", ir.reasoningEffort, "budget_tokens 10240 (>= 4000 and < 16000) should map to medium")
        assertNotNull(ir.thinking, "raw thinking should still be preserved")
    }

    @Test
    fun decodeRequestConvertsAdaptiveThinkingToXhigh() {
        val request = json.parseToJsonElement("""
            {
                "model": "claude-opus-4-8",
                "max_tokens": 16384,
                "thinking": {"type": "adaptive"},
                "messages": [{"role": "user", "content": "hello"}]
            }
        """.trimIndent()).jsonObject
        val ir = codec.decodeRequest(request)
        assertEquals("xhigh", ir.reasoningEffort)
    }

    @Test
    fun decodeRequestConvertsLowBudgetToLow() {
        val request = json.parseToJsonElement("""
            {
                "model": "claude-opus-4-8",
                "max_tokens": 16384,
                "thinking": {"type": "enabled", "budget_tokens": 2000},
                "messages": [{"role": "user", "content": "hello"}]
            }
        """.trimIndent()).jsonObject
        val ir = codec.decodeRequest(request)
        assertEquals("low", ir.reasoningEffort)
    }

    @Test
    fun passthroughContentBlockSurvivesRoundtrip() {
        val raw = """
            {"type":"message","id":"msg_1","role":"assistant","model":"claude",
             "content":[{"type":"server_tool_use","id":"stu_1","name":"web_search","input":{"q":"test"}}],
             "stop_reason":"tool_use","usage":{}}
        """.trimIndent().replace("\n", "")
        val ir = codec.decodeResponse(json.parseToJsonElement(raw).jsonObject)
        val encoded = codec.encodeResponse(ir)
        val content = encoded["content"]?.jsonArray
        assertNotNull(content)
        assertTrue(content.size >= 1)
        // Passthrough preserves the original type
        val first = content[0].jsonObject
        assertEquals("server_tool_use", first["type"]?.jsonPrimitive?.content)
    }
}
