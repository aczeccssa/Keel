package com.keel.samples.aigateway.benchmark

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BenchmarkHttpClientTest {
    @Test
    fun blockingClientRecordsSuccessfulAnthropicMetrics() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { provider ->
            val case = BenchmarkCase(BenchmarkProtocol.ANTHROPIC_MESSAGES, BenchmarkRequestMode.BLOCKING, BenchmarkTopology.DIRECT_PROVIDER, 1, 50)
            val topology = BenchmarkTopologyFactory.create(case, provider.baseUrl, 10)
            BenchmarkKeelServer.start(topology).use { keel ->
                BenchmarkHttpClient().use { client ->
                    val result = client.execute(keel.baseUrl, keel.apiKey, topology.requestModel, case, "req-1")

                    assertEquals(200, result.status)
                    assertEquals(case.id, result.caseId)
                    assertTrue(result.latencyMs >= 1)
                    assertNotNull(result.providerId)
                    Unit
                }
            }
        }
    }

    @Test
    fun streamingClientRecordsTtfbAndChunkCount() = runBlocking {
        SimulatedProviderServer.start(port = 0).use { provider ->
            val case = BenchmarkCase(BenchmarkProtocol.OPENAI_RESPONSES, BenchmarkRequestMode.STREAMING, BenchmarkTopology.DIRECT_PROVIDER, 1, 50)
            val topology = BenchmarkTopologyFactory.create(case, provider.baseUrl, 10)
            BenchmarkKeelServer.start(topology).use { keel ->
                BenchmarkHttpClient().use { client ->
                    val result = client.execute(keel.baseUrl, keel.apiKey, topology.requestModel, case, "req-2")

                    assertEquals(200, result.status)
                    assertTrue(result.timeToFirstByteMs != null && result.timeToFirstByteMs >= 0)
                    assertTrue(result.chunkCount > 0)
                }
            }
        }
    }
}
