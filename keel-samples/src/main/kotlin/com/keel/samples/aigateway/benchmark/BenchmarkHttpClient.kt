package com.keel.samples.aigateway.benchmark

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.Serializable
import java.io.Closeable

@Serializable
data class BenchmarkRequestResult(
    val requestId: String,
    val caseId: String,
    val protocol: BenchmarkProtocol,
    val requestMode: BenchmarkRequestMode,
    val topology: BenchmarkTopology,
    val attachedProviderCount: Int,
    val generationDurationMs: Long,
    val status: Int,
    val latencyMs: Long,
    val timeToFirstByteMs: Long? = null,
    val streamDurationMs: Long? = null,
    val chunkCount: Int = 0,
    val providerId: String? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
)

class BenchmarkHttpClient : Closeable {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 180_000
        }
        expectSuccess = false
    }

    suspend fun execute(
        keelBaseUrl: String,
        apiKey: String,
        model: String,
        case: BenchmarkCase,
        requestId: String,
    ): BenchmarkRequestResult {
        val started = System.nanoTime()
        return try {
            val response = client.post("$keelBaseUrl${case.protocol.path}") {
                header("X-Benchmark-Request-Id", requestId)
                when (case.protocol) {
                    BenchmarkProtocol.ANTHROPIC_MESSAGES -> {
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    BenchmarkProtocol.OPENAI_RESPONSES -> header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                if (case.requestMode.isStreaming) header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(requestBody(model, case))
            }
            val providerHeader = response.headers["X-Benchmark-Provider-Id"]
            if (!case.requestMode.isStreaming) {
                val body = response.bodyAsText()
                return result(
                    case = case,
                    requestId = requestId,
                    status = response.status.value,
                    started = started,
                    providerId = providerHeader ?: extractProviderId(body),
                    errorMessage = body.takeIf { response.status.value >= 400 },
                )
            }

            val channel = response.bodyAsChannel()
            val body = StringBuilder()
            var firstByteMs: Long? = null
            var chunks = 0
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (firstByteMs == null) firstByteMs = elapsedMs(started)
                body.appendLine(line)
                if (line.startsWith("data:") || line.startsWith("event:")) chunks += 1
            }
            result(
                case = case,
                requestId = requestId,
                status = response.status.value,
                started = started,
                ttfb = firstByteMs,
                streamDuration = elapsedMs(started),
                chunkCount = chunks,
                providerId = providerHeader ?: extractProviderId(body.toString()),
                errorMessage = body.toString().takeIf { response.status.value >= 400 },
            )
        } catch (error: Throwable) {
            result(
                case = case,
                requestId = requestId,
                status = 0,
                started = started,
                errorType = error::class.simpleName ?: "Throwable",
                errorMessage = error.message,
            )
        }
    }

    override fun close() {
        client.close()
    }

    private fun requestBody(model: String, case: BenchmarkCase): String = when (case.protocol) {
        BenchmarkProtocol.ANTHROPIC_MESSAGES ->
            """{"model":"$model","max_tokens":512,"stream":${case.requestMode.isStreaming},"messages":[{"role":"user","content":"benchmark payload"}]}"""
        BenchmarkProtocol.OPENAI_RESPONSES ->
            """{"model":"$model","max_output_tokens":512,"stream":${case.requestMode.isStreaming},"store":false,"input":"benchmark payload"}"""
    }

    private fun result(
        case: BenchmarkCase,
        requestId: String,
        status: Int,
        started: Long,
        ttfb: Long? = null,
        streamDuration: Long? = null,
        chunkCount: Int = 0,
        providerId: String? = null,
        errorType: String? = null,
        errorMessage: String? = null,
    ) = BenchmarkRequestResult(
        requestId = requestId,
        caseId = case.id,
        protocol = case.protocol,
        requestMode = case.requestMode,
        topology = case.topology,
        attachedProviderCount = case.attachedProviderCount,
        generationDurationMs = case.generationDurationMs,
        status = status,
        latencyMs = elapsedMs(started),
        timeToFirstByteMs = ttfb,
        streamDurationMs = streamDuration,
        chunkCount = chunkCount,
        providerId = providerId,
        errorType = errorType,
        errorMessage = errorMessage,
    )

    private fun extractProviderId(text: String): String? =
        providerTextPattern.find(text)?.groupValues?.getOrNull(1)
            ?: providerIdPattern.find(text)?.groupValues?.getOrNull(1)

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private companion object {
        val providerTextPattern = Regex("""simulated response from ([A-Za-z0-9_.:-]+)""")
        val providerIdPattern = Regex(""""(?:msg|resp)_([A-Za-z0-9_.:-]+)"""")
    }
}
