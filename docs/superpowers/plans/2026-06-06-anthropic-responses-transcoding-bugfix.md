# Anthropic <-> Responses API Transcoding Bugfix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 8 bugs in the AI Relay protocol transcoding layer so that Claude Code requests routed through OpenAI Responses API channels (e.g. `claude-opus-4-8 -> gpt-5.4`) succeed instead of returning 503.

**Architecture:** The relay uses a three-layer codec design: client request is decoded via `AnthropicMessagesCodec` into an IR (`IrRequest`), then re-encoded via `OpenAIResponsesCodec` for the upstream. Responses follow the reverse path. Each bug is a gap in this decode-encode pipeline. All fixes are surgical edits to existing files plus one new `ResponsesStreamObserver`.

**Tech Stack:** Kotlin, Ktor (CIO client/server), kotlinx.serialization, Keel plugin framework

**Branch:** `codex/risk-control-rate-limiting` — all work must happen on this branch.

---

## File Map

| File (relative to `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/`) | Action | Responsibility |
|---|---|---|
| `protocol/anthropic/AnthropicMessagesCodec.kt` | Modify | Bug #1 (thinking→reasoning), Bug #7 (error before message_start) |
| `upstream/RealUpstreamHttpClient.kt` | Modify | Bug #2 (anthropic-version leak), Bug #3 (stream status check) |
| `protocol/openai/responses/ResponsesStreamObserver.kt` | **Create** | Bug #4 (stream error detection for Responses API) |
| `AIRelayService.kt` | Modify | Bug #4 (use observer), Bug #6 (blocking status check) |
| `protocol/openai/responses/OpenAIResponsesCodec.kt` | Modify | Bug #5 (encodeStream), Bug #8 (parallel_tool_calls) |

| Test file (relative to `keel-test-suite/src/test/kotlin/com/keel/test/kernel/`) | Action | Covers |
|---|---|---|
| `AnthropicCodecConformanceTest.kt` | Modify | Bug #1, Bug #7 |
| `TranscodingBugfixTest.kt` | **Create** | Bug #2, #3, #4, #5, #6, #8 — cross-cutting integration tests |

---

### Task 1: Bug #1 — Convert `thinking` to `reasoningEffort` in AnthropicMessagesCodec

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicMessagesCodec.kt`
- Modify: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicCodecConformanceTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `AnthropicCodecConformanceTest.kt`:

```kotlin
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
    assertEquals("high", ir.reasoningEffort, "budget_tokens >= 16000 should map to high")
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.AnthropicCodecConformanceTest.decodeRequestConvertsThinkingToReasoningEffort" --tests "com.keel.test.kernel.AnthropicCodecConformanceTest.decodeRequestConvertsAdaptiveThinkingToXhigh" --tests "com.keel.test.kernel.AnthropicCodecConformanceTest.decodeRequestConvertsLowBudgetToLow" -x compileTestKotlin 2>/dev/null || echo "Expected: compile or assertion failure"`

Expected: FAIL — `reasoningEffort` is always null.

- [ ] **Step 3: Implement the fix**

In `AnthropicMessagesCodec.kt`, change line 51 from:

```kotlin
            reasoningEffort = null,
```

to:

```kotlin
            reasoningEffort = extractReasoningEffort(rawJson["thinking"]),
```

Add this private function at the end of the class (before the `companion object`):

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.AnthropicCodecConformanceTest.decodeRequestConverts*"`

Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicMessagesCodec.kt keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicCodecConformanceTest.kt
git commit -m "fix: convert Anthropic thinking to reasoningEffort for Responses API transcoding"
```

---

### Task 2: Bug #7 — Ensure `message_start` before error in Anthropic encodeStream

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicMessagesCodec.kt`
- Modify: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicCodecConformanceTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `AnthropicCodecConformanceTest.kt`:

```kotlin
@Test
fun encodeStreamEmitsMessageStartBeforeError() = runBlocking {
    val events = listOf(
        IrStreamEvent.Error("upstream failed", code = "500", errorType = "api_error")
    )
    val sseEvents = codec.encodeStream(events.asFlow()).toList()
    assertTrue(sseEvents.size >= 2, "Should have message_start + error, got ${sseEvents.size}")
    assertEquals("message_start", sseEvents.first().event, "First event must be message_start")
    assertEquals("error", sseEvents.last().event, "Last event must be error")
}
```

Add these imports to the file if not present:

```kotlin
import com.keel.samples.aigateway.airelay.protocol.IrStreamEvent
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.AnthropicCodecConformanceTest.encodeStreamEmitsMessageStartBeforeError"`

Expected: FAIL — only 1 event emitted (error without message_start).

- [ ] **Step 3: Implement the fix**

In `AnthropicMessagesCodec.kt`, find line 299:

```kotlin
                is IrStreamEvent.Error -> emit(errorEvent(event))
```

Change to:

```kotlin
                is IrStreamEvent.Error -> {
                    ensureMessageStarted()
                    emit(errorEvent(event))
                }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.AnthropicCodecConformanceTest.encodeStreamEmitsMessageStartBeforeError"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicMessagesCodec.kt keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicCodecConformanceTest.kt
git commit -m "fix: ensure message_start before error in Anthropic encodeStream"
```

---

### Task 3: Bug #2 — Guard `anthropic-version` header with protocol check

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/upstream/RealUpstreamHttpClient.kt`
- Create: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt`

- [ ] **Step 1: Write the failing test**

Create `keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt`:

```kotlin
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
 * Bug #1 and #7 are tested in AnthropicCodecConformanceTest.
 */
class TranscodingBugfixTest {
    private val anthropicCodec = AnthropicMessagesCodec()
    private val responsesCodec = OpenAIResponsesCodec()
    private val transcoder = ProtocolTranscoder(listOf(anthropicCodec, responsesCodec))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- Bug #2: anthropic-version header should not leak ---
    // This is a behavioral invariant verified by inspecting the encodeRequest output;
    // the header itself is in RealUpstreamHttpClient, so we verify the codec doesn't
    // inject anthropic-version into the request body.

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
}
```

- [ ] **Step 2: Run test to verify it passes (baseline sanity)**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.responsesEncodedRequestDoesNotContainAnthropicFields"`

Expected: PASS — the codec already maps fields correctly; this is a regression guard.

- [ ] **Step 3: Implement the header fix**

In `RealUpstreamHttpClient.kt`, find line 64:

```kotlin
                if ("anthropic-version" !in extraHeaders) append("anthropic-version", "2023-06-01")
```

Change to:

```kotlin
                if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) {
                    if ("anthropic-version" !in extraHeaders) append("anthropic-version", "2023-06-01")
                }
```

Find line 213 (same pattern in `stream()`) and apply the same change.

Find line 240 (same pattern in `countTokens()`) — this one is Anthropic-only by context but guard it too for safety:

```kotlin
                if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) {
                    if ("anthropic-version" !in extraHeaders) append("anthropic-version", "2023-06-01")
                }
```

- [ ] **Step 4: Verify existing tests still pass**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.*"`

Expected: All existing tests PASS (no regressions).

- [ ] **Step 5: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/upstream/RealUpstreamHttpClient.kt keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt
git commit -m "fix: only send anthropic-version header to Anthropic protocol upstreams"
```

---

### Task 4: Bug #3 — Check HTTP status in `stream()` and emit error event

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/upstream/RealUpstreamHttpClient.kt`
- Modify: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `TranscodingBugfixTest.kt`:

```kotlin
@Test
fun responsesStreamErrorEventDecodesAsIrError() = runBlocking {
    // Simulate what happens when the upstream returns an error SSE event
    // (which Bug #3 fix causes to be emitted on HTTP 4xx/5xx)
    val errorSse = ServerSentEvent(
        data = """{"type":"error","message":"Invalid request body","code":"400"}""",
        event = "error"
    )
    val irEvents = responsesCodec.decodeStream(kotlinx.coroutines.flow.flowOf(errorSse)).toList()
    assertEquals(1, irEvents.size)
    val error = irEvents.single() as IrStreamEvent.Error
    assertEquals("Invalid request body", error.message)
}
```

- [ ] **Step 2: Run test to verify it passes (baseline)**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.responsesStreamErrorEventDecodesAsIrError"`

Expected: PASS — the decode path for `"error"` events already works in `OpenAIResponsesCodec.decodeStream()`.

- [ ] **Step 3: Implement the stream status check**

In `RealUpstreamHttpClient.kt`, replace the `stream()` function body (lines 197-225). Change from:

```kotlin
    override fun stream(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String>
    ): Flow<ServerSentEvent> = flow {
        val url = endpointUrl(selection, stream = true)
        val apiKey = resolveApiKey(selection)
        val bodyText: String = client.post(url) {
            headers {
                selection.provider.defaultHeaders.forEach { (k, v) -> append(k, v) }
                extraHeaders.forEach { (k, v) -> append(k, v) }
                append(HttpHeaders.Accept, "text/event-stream")
                when (selection.provider.protocol) {
                    WireProtocol.ANTHROPIC_MESSAGES -> append("x-api-key", apiKey)
                    else -> append(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                if ("anthropic-version" !in extraHeaders) append("anthropic-version", "2023-06-01")
            }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), request))
        }.bodyAsText()

        // The CIO client returns the full body once the server closes the connection. The
        // body is a sequence of SSE blocks separated by \n\n; within each block, lines of
        // the form `event:`, `data:`, `id:`, `retry:`. We parse one event at a time.
        for (block in bodyText.split("\n\n")) {
            val event = parseSseBlock(block) ?: continue
            emit(event)
        }
    }
```

To:

```kotlin
    override fun stream(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String>
    ): Flow<ServerSentEvent> = flow {
        val url = endpointUrl(selection, stream = true)
        val apiKey = resolveApiKey(selection)
        val response = client.post(url) {
            headers {
                selection.provider.defaultHeaders.forEach { (k, v) -> append(k, v) }
                extraHeaders.forEach { (k, v) -> append(k, v) }
                append(HttpHeaders.Accept, "text/event-stream")
                when (selection.provider.protocol) {
                    WireProtocol.ANTHROPIC_MESSAGES -> append("x-api-key", apiKey)
                    else -> append(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) {
                    if ("anthropic-version" !in extraHeaders) append("anthropic-version", "2023-06-01")
                }
            }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), request))
        }
        val bodyText = response.bodyAsText()

        // Non-2xx: emit a synthetic error event so codecs and observers can detect it
        if (response.status.value >= 400) {
            val errorJson = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
            val errorMessage = errorJson?.obj("error")?.string("message")
                ?: errorJson?.string("message")
                ?: "Upstream error: HTTP ${response.status.value}"
            emit(ServerSentEvent(
                data = json.encodeToString(buildJsonObject {
                    put("type", JsonPrimitive("error"))
                    put("message", JsonPrimitive(errorMessage))
                    put("code", JsonPrimitive(response.status.value.toString()))
                }),
                event = "error"
            ))
            return@flow
        }

        // The CIO client returns the full body once the server closes the connection. The
        // body is a sequence of SSE blocks separated by \n\n; within each block, lines of
        // the form `event:`, `data:`, `id:`, `retry:`. We parse one event at a time.
        for (block in bodyText.split("\n\n")) {
            val event = parseSseBlock(block) ?: continue
            emit(event)
        }
    }
```

Note: this also includes the Bug #2 fix for `stream()` since we're rewriting the function.

- [ ] **Step 4: Verify existing tests still pass**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.OpenAIResponsesCodecTest.*"`

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/upstream/RealUpstreamHttpClient.kt keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt
git commit -m "fix: check HTTP status in stream() and emit error SSE event on failure"
```

---

### Task 5: Bug #4 — Create ResponsesStreamObserver and integrate into AIRelayService

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/openai/responses/ResponsesStreamObserver.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt`
- Modify: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `TranscodingBugfixTest.kt`:

```kotlin
import com.keel.samples.aigateway.airelay.protocol.openai.responses.ResponsesStreamObserver
import com.keel.contract.ai.RequestOutcome

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
```

- [ ] **Step 2: Run test to verify it fails (class not found)**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.responsesStreamObserverDetects*"`

Expected: FAIL — `ResponsesStreamObserver` class does not exist.

- [ ] **Step 3: Create ResponsesStreamObserver**

Create `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/openai/responses/ResponsesStreamObserver.kt`:

```kotlin
package com.keel.samples.aigateway.airelay.protocol.openai.responses

import com.keel.contract.ai.RequestOutcome
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UsageSource
import com.keel.samples.aigateway.airelay.protocol.obj
import com.keel.samples.aigateway.airelay.protocol.string
import com.keel.samples.aigateway.airelay.protocol.usageFromOpenAi
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

data class ResponsesStreamOutcome(
    val transportStatus: Int,
    val semanticStatus: Int,
    val outcome: RequestOutcome,
    val errorType: String?,
    val errorMessage: String?,
    val usage: TokenUsage,
    val usageSource: UsageSource,
    val requestId: String?,
    val model: String?,
)

class ResponsesStreamObserver(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    private var latestUsage: TokenUsage = TokenUsage()
    private var usageSeen: Boolean = false
    private var errorType: String? = null
    private var errorMessage: String? = null
    private var requestId: String? = null
    private var model: String? = null
    private var completed: Boolean = false
    private var failed: Boolean = false

    fun observe(event: ServerSentEvent) {
        val data = event.data ?: return
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val type = event.event ?: obj.string("type")
        when (type) {
            "response.created", "response.in_progress" -> {
                val response = obj.obj("response") ?: obj
                requestId = response.string("id") ?: requestId
                model = response.string("model") ?: model
            }
            "response.completed" -> {
                completed = true
                val response = obj.obj("response") ?: obj
                val usage = usageFromOpenAi(response.obj("usage"))
                if (hasAnyUsage(usage)) {
                    latestUsage = usage
                    usageSeen = true
                }
            }
            "response.failed", "response.incomplete" -> {
                failed = true
                val response = obj.obj("response")
                val error = response?.obj("error")
                errorType = error?.string("type") ?: response?.string("status") ?: "api_error"
                errorMessage = error?.string("message") ?: "Responses API stream error"
            }
            "error" -> {
                failed = true
                errorType = obj.string("code") ?: obj.string("type") ?: "api_error"
                errorMessage = obj.string("message") ?: "Responses API error"
            }
        }
    }

    fun outcome(transportStatus: Int): ResponsesStreamOutcome {
        val isError = failed || errorType != null || transportStatus >= 400
        val semantic = if (isError) {
            transportStatus.takeIf { it >= 400 } ?: 500
        } else {
            200
        }
        return ResponsesStreamOutcome(
            transportStatus = transportStatus,
            semanticStatus = semantic,
            outcome = if (isError) RequestOutcome.ERROR else RequestOutcome.SUCCESS,
            errorType = errorType,
            errorMessage = errorMessage,
            usage = if (usageSeen) latestUsage else TokenUsage(),
            usageSource = if (usageSeen) UsageSource.PROVIDER else UsageSource.NONE,
            requestId = requestId,
            model = model,
        )
    }

    private fun hasAnyUsage(usage: TokenUsage): Boolean =
        usage.promptTokens > 0 || usage.completionTokens > 0 ||
            usage.cachedPromptTokens > 0 || usage.reasoningTokens > 0
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.responsesStreamObserverDetects*"`

Expected: 3 tests PASS.

- [ ] **Step 5: Integrate observer into AIRelayService.handleStream()**

In `AIRelayService.kt`, add import at top:

```kotlin
import com.keel.samples.aigateway.airelay.protocol.openai.responses.ResponsesStreamObserver
```

Find line 248:

```kotlin
        val observer = if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) AnthropicStreamObserver() else null
```

Replace with:

```kotlin
        val anthropicObserver = if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) AnthropicStreamObserver() else null
        val responsesObserver = if (selection.provider.protocol == WireProtocol.OPENAI_RESPONSES) ResponsesStreamObserver() else null
```

Find line 250:

```kotlin
        rawEvents.forEach { observer?.observe(it) }
```

Replace with:

```kotlin
        rawEvents.forEach { event ->
            anthropicObserver?.observe(event)
            responsesObserver?.observe(event)
        }
```

Find line 271:

```kotlin
            val streamOutcome = observer?.outcome(transportStatus = 200)
```

Replace with:

```kotlin
            val streamOutcome = anthropicObserver?.outcome(transportStatus = 200)
                ?: responsesObserver?.outcome(transportStatus = 200)?.let { ro ->
                    AnthropicStreamOutcome(
                        transportStatus = ro.transportStatus,
                        semanticStatus = ro.semanticStatus,
                        outcome = ro.outcome,
                        errorType = ro.errorType,
                        errorMessage = ro.errorMessage,
                        usage = ro.usage,
                        usageSource = ro.usageSource,
                        requestId = ro.requestId,
                        model = ro.model,
                    )
                }
```

Add import for `AnthropicStreamOutcome` if not already present:

```kotlin
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicStreamOutcome
```

- [ ] **Step 6: Run all tests**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.*"`

Expected: All PASS.

- [ ] **Step 7: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/openai/responses/ResponsesStreamObserver.kt keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt
git commit -m "feat: add ResponsesStreamObserver and integrate into handleStream"
```

---

### Task 6: Bug #6 — Add upstream status check in handleBlockingRelay

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt`
- Modify: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt`

- [ ] **Step 1: Write the test**

Add to `TranscodingBugfixTest.kt`:

```kotlin
@Test
fun upstreamErrorResponseShouldNotDecodeAsSuccess() {
    // Verify that a 500 error JSON from upstream is detected as an error,
    // not silently decoded as a successful response
    val errorBody = json.parseToJsonElement("""
        {"error": {"message": "Internal server error", "type": "server_error"}}
    """.trimIndent()).jsonObject

    // The error body should NOT successfully decode as a useful IrResponse
    val ir = responsesCodec.decodeResponse(errorBody)
    // If it does decode, the output should be empty/placeholder text
    val outputText = ir.output.filterIsInstance<IrItem.Message>()
        .flatMap { it.content }
        .filterIsInstance<IrContentPart.Text>()
        .joinToString("") { it.text }
    assertTrue(outputText.isBlank(), "Error response body should not produce meaningful output text")
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.upstreamErrorResponseShouldNotDecodeAsSuccess"`

Expected: PASS — this confirms that error bodies produce empty output, validating the need for status checking.

- [ ] **Step 3: Implement upstream status check**

In `AIRelayService.kt`, find line 155 in `handleBlockingRelay()`:

```kotlin
                val upstreamIr = transcoder.decodeResponse(selection.provider.protocol, upstream.body)
```

Insert BEFORE that line:

```kotlin
                if (upstream.status >= 400) {
                    val errorMsg = upstream.body.obj("error")?.string("message")
                        ?: upstream.body.string("message")
                        ?: "Upstream returned ${upstream.status}"
                    throw UpstreamHttpException(upstream.status, errorMsg)
                }
```

This requires `obj` and `string` imports which should already be present from other usages.

- [ ] **Step 4: Run all tests**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.*"`

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt
git commit -m "fix: check upstream HTTP status before decoding response in handleBlockingRelay"
```

---

### Task 7: Bug #5 — Complete encodeStream in OpenAIResponsesCodec

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/openai/responses/OpenAIResponsesCodec.kt`
- Modify: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `TranscodingBugfixTest.kt`:

```kotlin
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

    // Verify we get proper Responses API events, not all "response.created"
    val eventTypes = sseEvents.map { it.event }
    assertTrue("response.created" in eventTypes, "Should have response.created")
    assertTrue("response.output_item.added" in eventTypes, "Should have output_item.added for tool_use")
    assertTrue("response.function_call_arguments.delta" in eventTypes, "Should have function_call_arguments.delta")
    assertTrue("response.completed" in eventTypes, "Should have response.completed")

    // Verify function_call_arguments.delta contains correct data
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.responsesEncodeStreamHandles*"`

Expected: FAIL — `response.output_item.added` and `response.function_call_arguments.delta` are not emitted by current code.

- [ ] **Step 3: Implement full encodeStream**

In `OpenAIResponsesCodec.kt`, replace the `encodeStream` function (starting at line 263) with:

```kotlin
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
```

Add this import at the top if not present:

```kotlin
import com.keel.samples.aigateway.airelay.protocol.tokenUsageJson
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.responsesEncodeStreamHandles*"`

Expected: 2 tests PASS.

- [ ] **Step 5: Run all existing codec tests for regressions**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.OpenAIResponsesCodecTest.*"`

Expected: All PASS.

- [ ] **Step 6: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/openai/responses/OpenAIResponsesCodec.kt keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt
git commit -m "fix: complete encodeStream in OpenAIResponsesCodec with all IR event types"
```

---

### Task 8: Bug #8 — Map `disable_parallel_tool_use` to `parallel_tool_calls`

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/openai/responses/OpenAIResponsesCodec.kt`
- Modify: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `TranscodingBugfixTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.anthropicDisableParallelToolUseMapsToResponsesParallelToolCalls"`

Expected: FAIL — `parallel_tool_calls` is null.

- [ ] **Step 3: Implement the fix**

In `OpenAIResponsesCodec.kt`, find line 84 in `encodeRequest()`:

```kotlin
        ir.toolChoice?.let { put("tool_choice", encodeToolChoice(it)) }
```

Replace with:

```kotlin
        ir.toolChoice?.let { tc ->
            put("tool_choice", encodeToolChoice(tc))
            (tc as? JsonObject)?.boolean("disable_parallel_tool_use")?.let { disable ->
                put("parallel_tool_calls", JsonPrimitive(!disable))
            }
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.anthropic*ToolUse*" --tests "com.keel.test.kernel.TranscodingBugfixTest.anthropic*ToolChoice*"`

Expected: Both PASS.

- [ ] **Step 5: Run full test suite**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.*"`

Expected: All PASS.

- [ ] **Step 6: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/openai/responses/OpenAIResponsesCodec.kt keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt
git commit -m "fix: map Anthropic disable_parallel_tool_use to Responses parallel_tool_calls"
```

---

### Task 9: End-to-End Cross-Protocol Transcoding Test

**Files:**
- Modify: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt`

- [ ] **Step 1: Write the end-to-end round-trip test**

Add to `TranscodingBugfixTest.kt`:

```kotlin
@Test
fun fullAnthropicToResponsesRoundTripWithThinkingAndTools() {
    // Simulate a real Claude Code request with thinking + tools
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

    // Decode from Anthropic
    val ir = anthropicCodec.decodeRequest(anthropicRequest)

    // Verify thinking → reasoningEffort conversion
    assertEquals("high", ir.reasoningEffort, "budget_tokens=10240 should map to high")

    // Encode to Responses API
    val responsesRequest = responsesCodec.encodeRequest(ir)

    // Verify key field mappings
    assertEquals("claude-opus-4-8", responsesRequest.string("model"))
    assertNotNull(responsesRequest["instructions"], "system should map to instructions")
    assertTrue(responsesRequest["instructions"]!!.jsonPrimitive.content.contains("helpful assistant"))
    assertNotNull(responsesRequest["input"], "messages should map to input")
    assertEquals(JsonPrimitive(8192), responsesRequest["max_output_tokens"] ?: responsesRequest["max_tokens"],
        "max_tokens should map to max_output_tokens")
    assertEquals(JsonPrimitive(false), responsesRequest["parallel_tool_calls"])
    assertNotNull(responsesRequest["reasoning"], "thinking should produce reasoning field")
    assertEquals("high", responsesRequest["reasoning"]!!.jsonObject.string("effort"))

    // Verify tools mapped correctly
    val tools = responsesRequest["tools"]!!.jsonArray
    assertEquals(1, tools.size)
    val tool = tools[0].jsonObject
    assertEquals("function", tool.string("type"))
    assertEquals("Read", tool.string("name"))
    assertNotNull(tool["parameters"], "input_schema should map to parameters")

    // Verify input contains function_call and function_call_output items
    val input = responsesRequest["input"]!!.jsonArray
    assertTrue(input.any { it.jsonObject.string("type") == "function_call" },
        "tool_use should become function_call in input")
    assertTrue(input.any { it.jsonObject.string("type") == "function_call_output" },
        "tool_result should become function_call_output in input")

    // Verify function_call has stringified arguments (not object)
    val funcCall = input.first { it.jsonObject.string("type") == "function_call" }.jsonObject
    val args = funcCall["arguments"]!!.jsonPrimitive.content
    assertTrue(args.contains("file_path"), "arguments should be a JSON string, not object")

    // Verify store=false is set
    assertEquals(JsonPrimitive(false), responsesRequest["store"])
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.TranscodingBugfixTest.fullAnthropicToResponsesRoundTripWithThinkingAndTools"`

Expected: PASS — all previous bug fixes should make this work.

- [ ] **Step 3: Commit**

```bash
git add keel-test-suite/src/test/kotlin/com/keel/test/kernel/TranscodingBugfixTest.kt
git commit -m "test: add end-to-end Anthropic-to-Responses transcoding round-trip test"
```

---

### Task 10: Final Verification

- [ ] **Step 1: Run the complete test suite**

Run: `./gradlew :keel-test-suite:test`

Expected: All tests PASS, zero failures.

- [ ] **Step 2: Verify no compilation warnings**

Run: `./gradlew :keel-samples:compileKotlin 2>&1 | grep -i "warning\|error" | head -20`

Expected: No new warnings or errors.

- [ ] **Step 3: Review git log**

Run: `git log --oneline -10`

Expected: 8 clean commits, one per task (Tasks 1-8) plus the e2e test commit.
