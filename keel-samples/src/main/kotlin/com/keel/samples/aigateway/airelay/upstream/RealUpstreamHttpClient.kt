package com.keel.samples.aigateway.airelay.upstream

import com.keel.samples.aigateway.airelay.AI_RELAY_MIN_TIMEOUT_MS
import com.keel.samples.aigateway.airelay.SseChunkDecoder
import com.keel.samples.aigateway.airelay.parseSseBlock
import com.keel.samples.aigateway.airelay.pool.PoolSelection
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.protocol.int
import com.keel.samples.aigateway.airelay.protocol.obj
import com.keel.samples.aigateway.airelay.protocol.string
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Real HTTP upstream client. Hits the actual provider at [selection.provider.baseUrl]
 * using the key from [selection.keyState.key]. Sends requests in the provider's [WireProtocol]
 * and streams the SSE response back without transcoding — transcoding is the AIRelayService's
 * job. This client is a pure transport.
 *
 * Constructed via [RealUpstreamHttpClient.create] so we get a fresh HttpClient.
 */
class RealUpstreamHttpClient private constructor(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) : UpstreamHttpClient {

    override suspend fun send(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String>
    ): UpstreamResponse {
        val url = endpointUrl(selection, stream = false)
        val apiKey = resolveApiKey(selection)
        val response: HttpResponse = client.post(url) {
            applyChannelTimeout(selection, streaming = false)
            headers {
                selection.provider.defaultHeaders.forEach { (k, v) -> append(k, v) }
                extraHeaders.forEach { (k, v) -> append(k, v) }
                append(HttpHeaders.Accept, "application/json")
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
        val body = runCatching { json.parseToJsonElement(bodyText).jsonObject }
            .getOrElse {
                if (selection.provider.protocol == WireProtocol.OPENAI_RESPONSES) {
                    aggregateResponsesSseBody(bodyText)?.let { return@getOrElse it }
                }
                throw UpstreamHttpException(
                    response.status.value,
                    "Invalid JSON body from upstream: ${bodyText.take(200)}"
                )
            }
        val headers: Map<String, List<String>> = response.headers.entries()
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.flatten() }
        return UpstreamResponse(status = response.status.value, body = body, headers = headers)
    }

    private data class PartialFunctionCall(
        val id: String,
        val callId: String,
        val name: String,
        val outputIndex: Int,
        var arguments: String = ""
    )

    private fun aggregateResponsesSseBody(bodyText: String): JsonObject? {
        val events = bodyText.split("\n\n").mapNotNull { parseSseBlock(it) }
        if (events.isEmpty()) return null

        val responseFields = linkedMapOf<String, JsonElement>()
        val outputText = StringBuilder()
        val functionCalls = linkedMapOf<String, PartialFunctionCall>()

        fun functionKey(obj: JsonObject, item: JsonObject? = null): String =
            item?.string("id")
                ?: obj.string("item_id")
                ?: obj.int("output_index")?.let { "output-$it" }
                ?: "output-${functionCalls.size}"

        events.forEach { event ->
            val data = event.data ?: return@forEach
            if (data == "[DONE]") return@forEach
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@forEach
            when (event.event ?: obj.string("type")) {
                "response.created", "response.in_progress", "response.completed" -> {
                    val response = obj.obj("response") ?: obj
                    responseFields.putAll(response)
                }
                "response.output_text.delta" -> outputText.append(obj.string("delta") ?: "")
                "response.refusal.delta" -> outputText.append(obj.string("delta") ?: obj.string("refusal") ?: "")
                "response.output_item.added" -> {
                    val item = obj.obj("item") ?: return@forEach
                    if (item.string("type") != "function_call") return@forEach
                    val key = functionKey(obj, item)
                    functionCalls[key] = PartialFunctionCall(
                        id = item.string("id") ?: key,
                        callId = item.string("call_id") ?: item.string("id") ?: key,
                        name = item.string("name") ?: "function",
                        outputIndex = obj.int("output_index") ?: functionCalls.size,
                        arguments = item.string("arguments") ?: ""
                    )
                }
                "response.function_call_arguments.delta" -> {
                    val key = functionKey(obj)
                    val call = functionCalls.getOrPut(key) {
                        PartialFunctionCall(
                            id = key,
                            callId = obj.string("call_id") ?: obj.string("item_id") ?: key,
                            name = obj.string("name") ?: "function",
                            outputIndex = obj.int("output_index") ?: functionCalls.size,
                        )
                    }
                    call.arguments += obj.string("delta").orEmpty()
                }
                "response.function_call_arguments.done" -> {
                    val key = functionKey(obj)
                    val call = functionCalls.getOrPut(key) {
                        PartialFunctionCall(
                            id = key,
                            callId = obj.string("call_id") ?: obj.string("item_id") ?: key,
                            name = obj.string("name") ?: "function",
                            outputIndex = obj.int("output_index") ?: functionCalls.size,
                        )
                    }
                    obj.string("arguments")?.let { call.arguments = it }
                }
            }
        }

        if (responseFields.isEmpty() && outputText.isEmpty() && functionCalls.isEmpty()) return null
        if ("output" !in responseFields) {
            responseFields["output"] = buildJsonArray {
                if (outputText.isNotEmpty()) {
                    add(buildJsonObject {
                        put("id", JsonPrimitive("msg_0"))
                        put("type", JsonPrimitive("message"))
                        put("role", JsonPrimitive("assistant"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("output_text"))
                                put("text", JsonPrimitive(outputText.toString()))
                            })
                        })
                    })
                }
                functionCalls.values.sortedBy { it.outputIndex }.forEach { call ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(call.id))
                        put("type", JsonPrimitive("function_call"))
                        put("call_id", JsonPrimitive(call.callId))
                        put("name", JsonPrimitive(call.name))
                        put("arguments", JsonPrimitive(call.arguments.ifBlank { "{}" }))
                    })
                }
            }
        }
        responseFields.putIfAbsent("id", JsonPrimitive("resp-sse-aggregate"))
        responseFields.putIfAbsent("object", JsonPrimitive("response"))
        responseFields.putIfAbsent("status", JsonPrimitive("completed"))
        responseFields.putIfAbsent("model", JsonPrimitive("unknown"))
        responseFields.putIfAbsent("output_text", JsonPrimitive(outputText.toString()))
        responseFields.putIfAbsent("usage", buildJsonObject {
            put("input_tokens", JsonPrimitive(0))
            put("output_tokens", JsonPrimitive(0))
        })
        return JsonObject(responseFields)
    }

    override suspend fun openStream(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String>
    ): OpenedUpstreamStream {
        val url = endpointUrl(selection, stream = true)
        val apiKey = resolveApiKey(selection)
        val response = client.post(url) {
            applyChannelTimeout(selection, streaming = true)
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
        if (response.status.value >= 400) {
            val bodyText = response.bodyAsText()
            val errorJson = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
            val errorMessage = errorJson?.obj("error")?.string("message")
                ?: errorJson?.string("message")
                ?: "Upstream error: HTTP ${response.status.value}"
            throw UpstreamHttpException(response.status.value, errorMessage)
        }
        val headers: Map<String, List<String>> = response.headers.entries()
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.flatten() }
        return OpenedUpstreamStream(
            status = response.status.value,
            headers = headers,
            events = flow {
                val decoder = SseChunkDecoder()
                val channel: ByteReadChannel = response.body()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    decoder.appendAll("$line\n").forEach { emit(it) }
                    if (line.isEmpty()) {
                        decoder.appendAll("\n").forEach { emit(it) }
                    }
                }
                decoder.flush()?.let { emit(it) }
            }
        )
    }

    override suspend fun countTokens(
        selection: PoolSelection,
        request: JsonObject,
        extraHeaders: Map<String, String>
    ): UpstreamResponse {
        val apiKey = resolveApiKey(selection)
        val response: HttpResponse = client.post("${selection.provider.baseUrl.trimEnd('/')}/v1/messages/count_tokens") {
            applyChannelTimeout(selection, streaming = false)
            headers {
                selection.provider.defaultHeaders.forEach { (k, v) -> append(k, v) }
                extraHeaders.forEach { (k, v) -> append(k, v) }
                append(HttpHeaders.Accept, "application/json")
                append("x-api-key", apiKey)
                if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) {
                    if ("anthropic-version" !in extraHeaders) append("anthropic-version", "2023-06-01")
                }
            }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), request))
        }
        val bodyText = response.bodyAsText()
        val body = runCatching { json.parseToJsonElement(bodyText).jsonObject }
            .getOrElse {
                throw UpstreamHttpException(
                    response.status.value,
                    "Invalid JSON body from upstream: ${bodyText.take(200)}"
                )
            }
        return UpstreamResponse(status = response.status.value, body = body)
    }

    private fun resolveApiKey(selection: PoolSelection): String {
        val key = selection.keyState.key
        return key.apiKeyEnv
            ?.let { System.getenv(it) ?: throw UpstreamHttpException(500, "Env var ${key.apiKeyEnv} is not set") }
            ?: key.apiKey
    }

    private fun endpointUrl(selection: PoolSelection, stream: Boolean): String {
        val base = selection.provider.baseUrl.trimEnd('/')
        val path = when (selection.provider.protocol) {
            WireProtocol.ANTHROPIC_MESSAGES -> "/v1/messages"
            WireProtocol.OPENAI_CHAT -> "/v1/chat/completions"
            WireProtocol.OPENAI_RESPONSES -> "/v1/responses"
        }
        return if (stream) "$base$path?stream=true" else "$base$path"
    }

    /**
     * Send one minimal request to a provider to verify reachability + auth, returning latency and
     * any error. Used by the admin "Test" action so a user can validate a channel (e.g. pointing at
     * http://127.0.0.1:15721 with their key) before saving it. Does NOT go through the pool — it
     * targets the supplied baseUrl/key directly.
     */
    suspend fun pingChannel(
        baseUrl: String,
        protocol: WireProtocol,
        apiKey: String,
        apiKeyEnv: String?,
        model: String
    ): PingResult {
        val resolvedKey = apiKeyEnv?.let { System.getenv(it) } ?: apiKey
        val base = baseUrl.trimEnd('/')
        val path = when (protocol) {
            WireProtocol.ANTHROPIC_MESSAGES -> "/v1/messages"
            WireProtocol.OPENAI_CHAT -> "/v1/chat/completions"
            WireProtocol.OPENAI_RESPONSES -> "/v1/responses"
        }
        val body = when (protocol) {
            WireProtocol.ANTHROPIC_MESSAGES ->
                """{"model":"$model","max_tokens":16,"messages":[{"role":"user","content":"ping"}]}"""
            WireProtocol.OPENAI_CHAT ->
                """{"model":"$model","max_tokens":16,"messages":[{"role":"user","content":"ping"}]}"""
            WireProtocol.OPENAI_RESPONSES ->
                """{"model":"$model","max_output_tokens":16,"input":"ping","store":false}"""
        }
        val startMark = io.ktor.util.date.getTimeMillis()
        return try {
            val response = client.post("$base$path") {
                headers {
                    append(HttpHeaders.Accept, "application/json")
                    when (protocol) {
                        WireProtocol.ANTHROPIC_MESSAGES -> append("x-api-key", resolvedKey)
                        else -> append(HttpHeaders.Authorization, "Bearer $resolvedKey")
                    }
                    append("anthropic-version", "2023-06-01")
                }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val latency = io.ktor.util.date.getTimeMillis() - startMark
            val text = response.bodyAsText()
            if (response.status.value in 200..299) {
                PingResult(true, latency, null, text.take(160))
            } else {
                PingResult(false, latency, "HTTP ${response.status.value}: ${text.take(160)}", null)
            }
        } catch (e: Exception) {
            PingResult(false, io.ktor.util.date.getTimeMillis() - startMark, e.message ?: e.toString(), null)
        }
    }

    suspend fun discoverModels(
        baseUrl: String,
        protocol: WireProtocol,
        apiKey: String,
        apiKeyEnv: String?,
    ): DiscoverModelsResult {
        val resolvedKey = apiKeyEnv?.let { System.getenv(it) } ?: apiKey
        val base = baseUrl.trimEnd('/')
        val startMark = io.ktor.util.date.getTimeMillis()
        return try {
            val response = client.get("$base/v1/models") {
                headers {
                    append(HttpHeaders.Accept, "application/json")
                    when (protocol) {
                        WireProtocol.ANTHROPIC_MESSAGES -> append("x-api-key", resolvedKey)
                        else -> append(HttpHeaders.Authorization, "Bearer $resolvedKey")
                    }
                    if (protocol == WireProtocol.ANTHROPIC_MESSAGES) append("anthropic-version", "2023-06-01")
                }
            }
            val latency = io.ktor.util.date.getTimeMillis() - startMark
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                return DiscoverModelsResult(emptyList(), latency, "HTTP ${response.status.value}: ${text.take(160)}")
            }
            val jsonBody = json.parseToJsonElement(text).jsonObject
            val modelItems = jsonBody["data"]?.jsonArray?.toList().orEmpty()
            val models = modelItems.mapNotNull { el ->
                runCatching { el.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
            }.distinct().sorted()
            DiscoverModelsResult(models, latency, null)
        } catch (e: Exception) {
            DiscoverModelsResult(emptyList(), io.ktor.util.date.getTimeMillis() - startMark, e.message ?: e.toString())
        }
    }

    override suspend fun proxyRaw(
        selection: PoolSelection,
        request: RawProxyRequest,
        extraHeaders: Map<String, String>
    ): RawProxyResponse {
        val response: HttpResponse = client.request(rawEndpointUrl(selection, request)) {
            applyChannelTimeout(selection, streaming = false)
            applyRawRequest(selection, request, extraHeaders)
        }
        val headers: Map<String, List<String>> = response.headers.entries()
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.flatten() }
        return RawProxyResponse(
            status = response.status.value,
            headers = headers,
            contentType = response.headers[HttpHeaders.ContentType],
            body = response.body()
        )
    }

    override suspend fun openRawStream(
        selection: PoolSelection,
        request: RawProxyRequest,
        extraHeaders: Map<String, String>
    ): OpenedUpstreamStream {
        val response = client.request(rawEndpointUrl(selection, request)) {
            applyChannelTimeout(selection, streaming = true)
            applyRawRequest(selection, request, extraHeaders)
        }
        if (response.status.value >= 400) {
            val bodyText = response.bodyAsText()
            val errorJson = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
            val errorMessage = errorJson?.obj("error")?.string("message")
                ?: errorJson?.string("message")
                ?: "Upstream error: HTTP ${response.status.value}"
            throw UpstreamHttpException(response.status.value, errorMessage)
        }
        val headers: Map<String, List<String>> = response.headers.entries()
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.flatten() }
        return OpenedUpstreamStream(
            status = response.status.value,
            headers = headers,
            events = flow {
                val decoder = SseChunkDecoder()
                val channel: ByteReadChannel = response.body()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    decoder.appendAll("$line\n").forEach { emit(it) }
                    if (line.isEmpty()) {
                        decoder.appendAll("\n").forEach { emit(it) }
                    }
                }
                decoder.flush()?.let { emit(it) }
            }
        )
    }

    private fun rawEndpointUrl(selection: PoolSelection, request: RawProxyRequest): String = buildString {
        append(selection.provider.baseUrl.trimEnd('/'))
        append(request.path)
        if (request.queryString.isNotBlank()) append('?').append(request.queryString)
    }

    private fun HttpRequestBuilder.applyRawRequest(
        selection: PoolSelection,
        request: RawProxyRequest,
        extraHeaders: Map<String, String>
    ) {
        val apiKey = resolveApiKey(selection)
        method = request.method
        headers {
            selection.provider.defaultHeaders.forEach { (k, v) -> append(k, v) }
            request.headers.forEach { (k, values) -> values.forEach { append(k, it) } }
            extraHeaders.forEach { (k, v) -> append(k, v) }
            when (selection.provider.protocol) {
                WireProtocol.ANTHROPIC_MESSAGES -> append("x-api-key", apiKey)
                else -> append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES &&
                "anthropic-version" !in extraHeaders &&
                request.headers.keys.none { it.equals("anthropic-version", ignoreCase = true) }) {
                append("anthropic-version", "2023-06-01")
            }
        }
        request.contentType?.let { contentType(ContentType.parse(it)) }
        if (request.body.isNotEmpty()) setBody(request.body)
    }

    private fun HttpRequestBuilder.applyChannelTimeout(selection: PoolSelection, streaming: Boolean) {
        val timeoutMs = selection.provider.timeoutMs.coerceAtLeast(AI_RELAY_MIN_TIMEOUT_MS)
        timeout {
            requestTimeoutMillis = if (streaming) null else timeoutMs
            socketTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs.coerceAtMost(DEFAULT_CONNECT_TIMEOUT_MS)
        }
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        private const val DEFAULT_REQUEST_TIMEOUT_MS = AI_RELAY_MIN_TIMEOUT_MS
        private const val DEFAULT_SOCKET_TIMEOUT_MS = AI_RELAY_MIN_TIMEOUT_MS

        fun create(): RealUpstreamHttpClient = RealUpstreamHttpClient(
            client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
                }
                expectSuccess = false
            }
        )
    }
}

data class DiscoverModelsResult(
    val models: List<String>,
    val latencyMs: Long,
    val error: String?,
)
