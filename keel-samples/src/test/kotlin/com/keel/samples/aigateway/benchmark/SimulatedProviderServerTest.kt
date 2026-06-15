package com.keel.samples.aigateway.benchmark

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedProviderServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun anthropicBlockingResponseIsProtocolValidAndDelayed() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { server ->
            val client = HttpClient(CIO)
            val started = System.nanoTime()
            val response = client.post("${server.baseUrl}/v1/messages") {
                header("x-api-key", "provider-key")
                header("X-Benchmark-Provider-Id", "provider-a")
                header("X-Benchmark-Generation-Ms", "75")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-bench","max_tokens":32,"messages":[{"role":"user","content":"hello"}]}""")
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject

            assertTrue(elapsedMs >= 60, "provider should honor generation delay")
            assertEquals("message", body["type"]!!.jsonPrimitive.content)
            assertEquals("claude-bench", body["model"]!!.jsonPrimitive.content)
            assertEquals(1, server.snapshot().totalRequests)
            client.close()
        }
    }

    @Test
    fun responsesStreamingEmitsSseChunksOverConfiguredDuration() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { server ->
            val client = HttpClient(CIO)
            val response = client.post("${server.baseUrl}/v1/responses?stream=true") {
                header(HttpHeaders.Authorization, "Bearer provider-key")
                header("X-Benchmark-Provider-Id", "provider-r")
                header("X-Benchmark-Generation-Ms", "75")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"resp-bench","input":"hello","stream":true}""")
            }
            val body = response.bodyAsText()

            assertTrue(body.contains("event: response.output_text.delta"))
            assertTrue(body.contains("event: response.completed"))
            assertEquals(1, server.snapshot().totalRequests)
            client.close()
        }
    }
}
