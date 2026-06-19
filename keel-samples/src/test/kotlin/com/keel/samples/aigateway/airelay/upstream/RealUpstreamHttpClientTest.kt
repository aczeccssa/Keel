package com.keel.samples.aigateway.airelay.upstream

import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.benchmark.SimulatedProviderServer
import com.keel.samples.aigateway.airelay.pool.PoolChainManager
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RealUpstreamHttpClientTest {
    @Test
    fun blockingRequestsClampChannelTimeoutToTenMinutes() {
        runBlocking {
            SimulatedProviderServer.start(port = 0).use { server ->
                val selection = PoolChainManager(
                    listOf(
                        PoolChainConfig(
                            chainId = "default",
                            modelAliases = listOf("gpt-5.5"),
                            levels = listOf(
                                PoolLevelConfig(
                                    levelId = "default-p0",
                                    levelIndex = 0,
                                    provider = UpstreamProviderConfig("default", protocol = WireProtocol.OPENAI_RESPONSES),
                                    keys = listOf(
                                        PooledKeyConfig(
                                            keyId = "ch-slow",
                                            apiKey = "provider-key",
                                            supportedModels = listOf("gpt-5.5"),
                                            provider = UpstreamProviderConfig(
                                                providerId = "slow-provider",
                                                baseUrl = server.baseUrl,
                                                protocol = WireProtocol.OPENAI_RESPONSES,
                                                timeoutMs = 50,
                                                defaultHeaders = mapOf("X-Benchmark-Generation-Ms" to "250"),
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ).selectCandidates("default", "gpt-5.5").single()

                val response = RealUpstreamHttpClient.create().send(
                    selection = selection,
                    request = buildJsonObject {
                        put("model", JsonPrimitive("gpt-5.5"))
                        put("input", JsonPrimitive("slow please"))
                    },
                    extraHeaders = emptyMap(),
                )

                assertEquals(200, response.status)
            }
        }
    }
}
