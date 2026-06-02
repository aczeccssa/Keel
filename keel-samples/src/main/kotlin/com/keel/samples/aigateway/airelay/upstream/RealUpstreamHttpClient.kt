package com.keel.samples.aigateway.airelay.upstream

import com.keel.samples.aigateway.airelay.pool.PoolSelection
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Real HTTP upstream client. Hits the actual provider at [selection.level.provider.baseUrl]
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

    override suspend fun send(selection: PoolSelection, request: JsonObject): UpstreamResponse {
        val url = endpointUrl(selection, stream = false)
        val apiKey = resolveApiKey(selection)
        val response: HttpResponse = client.post(url) {
            headers {
                selection.level.provider.defaultHeaders.forEach { (k, v) -> append(k, v) }
                append(HttpHeaders.Accept, "application/json")
                when (selection.level.provider.protocol) {
                    WireProtocol.ANTHROPIC_MESSAGES -> append("x-api-key", apiKey)
                    else -> append(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                append("anthropic-version", "2023-06-01")
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
        val headers: Map<String, List<String>> = response.headers.entries()
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.flatten() }
        return UpstreamResponse(status = response.status.value, body = body, headers = headers)
    }

    override fun stream(selection: PoolSelection, request: JsonObject): Flow<ServerSentEvent> = flow {
        val url = endpointUrl(selection, stream = true)
        val apiKey = resolveApiKey(selection)
        val bodyText: String = client.post(url) {
            headers {
                selection.level.provider.defaultHeaders.forEach { (k, v) -> append(k, v) }
                append(HttpHeaders.Accept, "text/event-stream")
                when (selection.level.provider.protocol) {
                    WireProtocol.ANTHROPIC_MESSAGES -> append("x-api-key", apiKey)
                    else -> append(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                append("anthropic-version", "2023-06-01")
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

    private fun parseSseBlock(block: String): ServerSentEvent? {
        if (block.isBlank()) return null
        var eventName: String? = null
        var data: String? = null
        var id: String? = null
        var retry: Long? = null
        for (raw in block.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            val (key, value) = if (idx == -1) line to "" else line.substring(0, idx) to line.substring(idx + 1).trimStart()
            when (key) {
                "event" -> eventName = value
                "data" -> data = if (data == null) value else data + "\n" + value
                "id" -> id = value
                "retry" -> retry = value.toLongOrNull()
            }
        }
        if (data == null && eventName == null && id == null && retry == null) return null
        return ServerSentEvent(data = data ?: "", event = eventName, id = id, retry = retry)
    }

    private fun resolveApiKey(selection: PoolSelection): String {
        val key = selection.keyState.key
        return key.apiKeyEnv
            ?.let { System.getenv(it) ?: throw UpstreamHttpException(500, "Env var ${key.apiKeyEnv} is not set") }
            ?: key.apiKey
    }

    private fun endpointUrl(selection: PoolSelection, stream: Boolean): String {
        val base = selection.level.provider.baseUrl.trimEnd('/')
        val path = when (selection.level.provider.protocol) {
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

    companion object {
        fun create(): RealUpstreamHttpClient = RealUpstreamHttpClient(
            client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 120_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 120_000
                }
                expectSuccess = false
            }
        )
    }
}
