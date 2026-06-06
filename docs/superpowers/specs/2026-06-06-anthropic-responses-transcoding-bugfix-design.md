# Anthropic Messages <-> OpenAI Responses API Transcoding Bugfix

**Date:** 2026-06-06
**Branch:** `codex/risk-control-rate-limiting`
**Status:** Approved

## Problem

Claude Code via Keel AI Relay returns 503 for models routed through OpenAI Responses API channels (e.g. `claude-opus-4-8 -> gpt-5.4`), while models on other channel protocols work fine (e.g. `claude-sonnet-4-6 -> mimo-v2.5-pro` returns 200). Logs show the upstream returns HTTP 500.

The transcoding layer between Anthropic Messages API and OpenAI Responses API exists but contains 8 bugs that cause incorrect request formatting, missing error detection, and silent data loss.

## Root Cause

The relay sends a transcoded request to the Responses API upstream, but:
1. `thinking` config from Anthropic is not converted to `reasoning` for Responses API
2. `anthropic-version` header leaks to non-Anthropic upstreams
3. Stream error responses (HTTP 500 with non-SSE body) are silently discarded
4. No stream observer exists for Responses API protocol, so errors go undetected

The upstream returns 500 (likely due to missing/malformed reasoning config or other format issues), which cascades through failover exhaustion to 503.

## Scope

Fix all 8 identified bugs. Add `ResponsesStreamObserver`. No architectural changes.

### Files Modified

| File | Change |
|------|--------|
| `protocol/anthropic/AnthropicMessagesCodec.kt` | Bug #1, #7 |
| `protocol/openai/responses/OpenAIResponsesCodec.kt` | Bug #5, #8 |
| `upstream/RealUpstreamHttpClient.kt` | Bug #2, #3 |
| `AIRelayService.kt` | Bug #4, #6 |

### Files Added

| File | Purpose |
|------|---------|
| `protocol/openai/responses/ResponsesStreamObserver.kt` | Stream error detection for Responses API |

All paths relative to `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/`.

## Bug Details & Fixes

### Bug #1: `thinking` -> `reasoningEffort` Not Converted

**File:** `AnthropicMessagesCodec.kt` - `decodeRequest()`

**Problem:** `ir.thinking` is populated from the raw Anthropic request but `ir.reasoningEffort` is always `null`. `OpenAIResponsesCodec.encodeRequest()` only reads `ir.reasoningEffort` for the `reasoning` field, so thinking/reasoning config is completely lost in cross-protocol transcoding.

**Fix:** Add `extractReasoningEffort()` function and call it during decode:

```kotlin
// In decodeRequest():
reasoningEffort = extractReasoningEffort(rawJson["thinking"]),

// New private function:
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

Mapping follows cc-switch's `map_thinking_to_reasoning` in `transform_responses.rs`.

### Bug #2: `anthropic-version` Header Sent to All Upstreams

**File:** `RealUpstreamHttpClient.kt` - `send()` and `stream()` (two locations)

**Problem:** `if ("anthropic-version" !in extraHeaders) append("anthropic-version", "2023-06-01")` executes for ALL protocols, sending an Anthropic-specific header to OpenAI Responses API endpoints.

**Fix:** Guard with protocol check:

```kotlin
if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) {
    if ("anthropic-version" !in extraHeaders) append("anthropic-version", "2023-06-01")
}
```

Apply to both `send()` (line ~65) and `stream()` (line ~214).

### Bug #3: `stream()` Ignores HTTP Status Code

**File:** `RealUpstreamHttpClient.kt` - `stream()`

**Problem:** The function calls `.bodyAsText()` and parses as SSE regardless of HTTP status. When upstream returns HTTP 500 with a JSON error body, `parseSseBlock()` returns null for each "block" (the JSON doesn't have `event:` / `data:` prefixes), producing an empty event stream. The error is silently swallowed.

**Fix:** Check response status before SSE parsing. On non-2xx, extract the error message and emit a synthetic SSE error event:

```kotlin
val response = client.post(url) { ... }
val bodyText = response.bodyAsText()
if (response.status.value >= 400) {
    val errorJson = runCatching {
        json.parseToJsonElement(bodyText).jsonObject
    }.getOrNull()
    val errorMessage = errorJson?.obj("error")?.string("message")
        ?: errorJson?.string("message")
        ?: "Upstream error: HTTP ${response.status.value}"
    val errorData = buildJsonObject {
        put("type", JsonPrimitive("error"))
        put("message", JsonPrimitive(errorMessage))
        put("code", JsonPrimitive(response.status.value.toString()))
    }
    emit(ServerSentEvent(
        data = json.encodeToString(errorData),
        event = "error"
    ))
    return@flow
}
for (block in bodyText.split("\n\n")) {
    val event = parseSseBlock(block) ?: continue
    emit(event)
}
```

### Bug #4: No Stream Observer for Responses API

**File:** `AIRelayService.kt` - `handleStream()`

**Problem:** `val observer = if (protocol == ANTHROPIC_MESSAGES) AnthropicStreamObserver() else null` means Responses API streams have no error detection. `semanticStatus` defaults to 200 even when the stream contains errors.

**New file:** `protocol/openai/responses/ResponsesStreamObserver.kt`

Mirrors `AnthropicStreamObserver` pattern. Observes raw SSE events for:
- `response.created` / `response.in_progress` - extract requestId and model
- `response.completed` - extract final usage
- `response.failed` / `response.incomplete` - mark error state
- `error` - mark error state with type and message

Returns `ResponsesStreamOutcome` with same fields as `AnthropicStreamOutcome`.

**Fix in `handleStream()`:**

```kotlin
val anthropicObserver = if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES)
    AnthropicStreamObserver() else null
val responsesObserver = if (selection.provider.protocol == WireProtocol.OPENAI_RESPONSES)
    ResponsesStreamObserver() else null

rawEvents.forEach { event ->
    anthropicObserver?.observe(event)
    responsesObserver?.observe(event)
}

// Unify outcome:
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

### Bug #5: `OpenAIResponsesCodec.encodeStream()` Handles Only 3 Event Types

**File:** `OpenAIResponsesCodec.kt` - `encodeStream()`

**Problem:** Only `TextDelta`, `ResponseDone`, and `Error` are mapped. All other IR events (`ContentBlockStart`, `InputJsonDelta`, `ThinkingDelta`, `ContentBlockStop`, `Ping`, etc.) collapse to a generic `response.created` event, losing tool call streaming and reasoning data.

**Fix:** Full event mapping:

| IrStreamEvent | Responses SSE Event |
|---------------|-------------------|
| `ResponseStart` | `response.created` with id, model, status |
| `ContentBlockStart` (text) | `response.content_part.added` |
| `ContentBlockStart` (tool_use) | `response.output_item.added` (function_call) |
| `TextDelta` | `response.output_text.delta` |
| `InputJsonDelta` | `response.function_call_arguments.delta` |
| `ThinkingDelta` | `response.reasoning.delta` |
| `ContentBlockStop` | `response.output_item.done` |
| `ResponseDone` | `response.completed` |
| `Error` | `error` |
| `Ping` | `ping` (no-op keepalive) |

### Bug #6: No Upstream Status Check in `handleBlockingRelay`

**File:** `AIRelayService.kt` - `handleBlockingRelay()`

**Problem:** After `upstreamClient.send()` returns successfully (body parsed as JSON), there is no check for `upstream.status >= 400`. A 500 error response is decoded as a normal response and returned to the client with garbled content.

**Fix:** Add status check after `send()`, before `decodeResponse()`:

```kotlin
val upstream = upstreamClient.send(selection, upstreamRequest, upstreamHeaders)
if (upstream.status >= 400) {
    val errorMsg = upstream.body.obj("error")?.string("message")
        ?: upstream.body.string("message")
        ?: "Upstream returned ${upstream.status}"
    throw UpstreamHttpException(upstream.status, errorMsg)
}
val upstreamIr = transcoder.decodeResponse(selection.provider.protocol, upstream.body)
```

This routes 4xx/5xx responses through the existing failover/catch logic.

### Bug #7: Error SSE Event Before `message_start`

**File:** `AnthropicMessagesCodec.kt` - `encodeStream()`

**Problem:** `IrStreamEvent.Error` does not call `ensureMessageStarted()`. If the upstream fails immediately (no `ResponseStart` event), Claude Code receives an error event without the required `message_start` prefix, causing a parse failure.

**Fix:**

```kotlin
is IrStreamEvent.Error -> {
    ensureMessageStarted()
    emit(errorEvent(event))
}
```

### Bug #8: `tool_choice` `disable_parallel_tool_use` Lost

**File:** `OpenAIResponsesCodec.kt` - `encodeRequest()`

**Problem:** Anthropic's `tool_choice` can carry `disable_parallel_tool_use: true`. The `encodeToolChoice()` function correctly maps `type` values but drops this extra field. Responses API uses `parallel_tool_calls` as a top-level request field.

**Fix:** In `encodeRequest()`, after emitting `tool_choice`:

```kotlin
ir.toolChoice?.let { tc ->
    put("tool_choice", encodeToolChoice(tc))
    (tc as? JsonObject)?.boolean("disable_parallel_tool_use")?.let { disable ->
        put("parallel_tool_calls", JsonPrimitive(!disable))
    }
}
```

## Verification Matrix

| Scenario | Bugs | Expected |
|----------|------|----------|
| Claude Code opus -> gpt-5.4 (Responses) blocking | #1,#2,#6 | 200, reasoning config present |
| Claude Code opus -> gpt-5.4 (Responses) streaming | #1,#2,#3,#4 | 200, full SSE, errors detected |
| Claude Code sonnet -> mimo-v2.5-pro | none | 200, no regression |
| Upstream 500 blocking | #6 | failover -> 503 with clear error |
| Upstream 500 streaming | #3,#4 | error SSE event, observer records |
| Upstream SSE error event | #4,#7 | message_start before error |
| Request with tools + tool_choice | #8 | parallel_tool_calls mapped |

## Non-Goals

- No refactoring of the existing codec architecture
- No new protocol support (e.g. OpenAI Chat Completions codec changes)
- No changes to pool/alias resolution logic
- No changes to the console manager UI
