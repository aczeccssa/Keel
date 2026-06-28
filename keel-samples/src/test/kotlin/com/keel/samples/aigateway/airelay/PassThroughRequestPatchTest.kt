package com.keel.samples.aigateway.airelay

import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.aigateway.airelay.protocol.IrRequest
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PassThroughRequestPatchTest {
    @Test
    fun compatibility403MessagesMapToClientRejected() {
        assertEquals(
            RelayFailureKind.CLIENT_REJECTED,
            classifyUpstreamFailure(403, "Request blocked: this endpoint only accepts requests from the official Claude Code CLI")
        )
    }

    @Test
    fun passThroughRequestAppliesModelStreamAndResponsesStoreOverrides() {
        val rawRequest = buildJsonObject {
            put("model", JsonPrimitive("client-model"))
            put("stream", JsonPrimitive(false))
        }

        val patched = patchPassThroughRequest(
            rawRequest = rawRequest,
            upstreamProtocol = WireProtocol.OPENAI_RESPONSES,
            ir = IrRequest(model = "upstream-model", stream = true)
        )

        assertEquals(JsonPrimitive("upstream-model"), patched["model"])
        assertEquals(JsonPrimitive(true), patched["stream"])
        assertEquals(JsonPrimitive(false), patched["store"])
    }

    @Test
    fun outboundResponsesStreamingRequestFailsFastWhenStreamFlagIsMissing() {
        val request = buildJsonObject {
            put("model", JsonPrimitive("gpt-5.5"))
            put("store", JsonPrimitive(false))
        }

        val error = assertFailsWith<PluginApiException> {
            validateUpstreamRequest(
                upstreamProtocol = WireProtocol.OPENAI_RESPONSES,
                ir = IrRequest(model = "gpt-5.5", stream = true),
                request = request
            )
        }

        assertEquals(400, error.status)
    }

    @Test
    fun outboundAnthropicContextManagementObjectPassesValidation() {
        val request = buildJsonObject {
            put("model", JsonPrimitive("claude-opus-4-8"))
            put("stream", JsonPrimitive(true))
            put("context_management", buildJsonObject {
                put("mode", JsonPrimitive("truncate"))
            })
        }

        validateUpstreamRequest(
            upstreamProtocol = WireProtocol.ANTHROPIC_MESSAGES,
            ir = IrRequest(model = "claude-opus-4-8", stream = true),
            request = request
        )
    }

    @Test
    fun passThroughAnthropicRequestPreservesContextManagementObject() {
        val rawRequest = buildJsonObject {
            put("model", JsonPrimitive("client-model"))
            put("stream", JsonPrimitive(false))
            put("context_management", buildJsonObject {
                put("mode", JsonPrimitive("truncate"))
            })
        }

        val patched = patchPassThroughRequest(
            rawRequest = rawRequest,
            upstreamProtocol = WireProtocol.ANTHROPIC_MESSAGES,
            ir = IrRequest(model = "claude-opus-4-8", stream = true)
        )

        assertEquals(JsonPrimitive("claude-opus-4-8"), patched["model"])
        assertEquals(JsonPrimitive(true), patched["stream"])
        assertEquals(rawRequest["context_management"], patched["context_management"])
    }
}
