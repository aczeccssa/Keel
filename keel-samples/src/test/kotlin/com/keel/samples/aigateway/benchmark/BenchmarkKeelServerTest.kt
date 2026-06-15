package com.keel.samples.aigateway.benchmark

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkKeelServerTest {
    @Test
    fun startsKeelAndCreatesApiKeyForBenchmarkGroup() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { provider ->
            val case = BenchmarkConfig.smoke().cases().first { it.protocol == BenchmarkProtocol.ANTHROPIC_MESSAGES }
            val topology = BenchmarkTopologyFactory.create(case, provider.baseUrl, providerMaxConcurrency = 10)
            BenchmarkKeelServer.start(topology).use { keel ->
                val client = HttpClient(CIO)
                val response = client.post("${keel.baseUrl}${BenchmarkProtocol.ANTHROPIC_MESSAGES.path}") {
                    header("x-api-key", keel.apiKey)
                    header("anthropic-version", "2023-06-01")
                    header("X-Benchmark-Request-Id", "test-request")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"${topology.requestModel}","max_tokens":32,"messages":[{"role":"user","content":"hello"}]}""")
                }

                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertTrue(provider.snapshot().totalRequests >= 1)
                client.close()
            }
        }
    }
}
