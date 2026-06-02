package com.keel.samples.aigateway.airelay.config

import com.keel.samples.aigateway.airelay.ModelPricing
import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.pool.PoolChainManager
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges persisted channels/models into the live gateway configuration and rebuilds the
 * [PoolChainManager] on every change (hot-apply, no restart). One enabled channel becomes one
 * single-level pool chain; each of its public model names becomes a chain alias. Pricing is
 * derived from the channel's models.
 *
 * The current [PoolChainManager] and [List]<[ModelPricing]> are published atomically so the relay
 * always sees a consistent snapshot.
 */
class ConfigService(
    private val repository: ChannelRepository
) {
    private val managerRef = AtomicReference<PoolChainManager>(PoolChainManager(emptyList()))
    private val pricingRef = AtomicReference<List<ModelPricing>>(emptyList())

    val poolChainManager: PoolChainManager get() = managerRef.get()
    val pricings: List<ModelPricing> get() = pricingRef.get()

    /** Rebuild the live config from the DB. Call after any channel/model mutation. */
    fun reload() {
        val channels = repository.listChannels().filter { it.enabled }
        val chains = channels.map { channel -> channel.toChain() }
        val pricing = channels.flatMap { channel ->
            channel.models.map { m ->
                ModelPricing(
                    model = m.publicModelName,
                    inputCostPerMTok = m.inputCostPerMTok,
                    outputCostPerMTok = m.outputCostPerMTok,
                    cacheCreationCostPerMTok = m.cacheCreationCostPerMTok,
                    cacheReadCostPerMTok = m.cacheReadCostPerMTok,
                    cachedInputDiscount = m.cachedInputDiscount,
                    reasoningOutputCostPerMTok = m.reasoningOutputCostPerMTok
                )
            }
        }.distinctBy { it.model }
        managerRef.set(PoolChainManager(chains))
        pricingRef.set(pricing)
    }

    private fun ChannelView.toChain(): PoolChainConfig {
        val resolvedProtocol = runCatching { WireProtocol.valueOf(protocol) }
            .getOrDefault(WireProtocol.ANTHROPIC_MESSAGES)
        val aliases = models.filter { it.enabled }.map { it.publicModelName }.distinct()
            .ifEmpty { listOf(name) }
        val plainKey = repository.decryptedKey(channelId) ?: ""
        return PoolChainConfig(
            chainId = channelId,
            modelAliases = aliases,
            levels = listOf(
                PoolLevelConfig(
                    levelId = "$channelId-l1",
                    levelIndex = 1,
                    provider = UpstreamProviderConfig(
                        providerId = name,
                        baseUrl = baseUrl,
                        protocol = resolvedProtocol,
                        timeoutMs = timeoutMs
                    ),
                    keys = listOf(
                        PooledKeyConfig(
                            keyId = "$channelId-key",
                            apiKeyEnv = apiKeyEnv,
                            apiKey = plainKey.ifEmpty { "missing-key" },
                            weight = weight,
                            maxConcurrency = maxConcurrency,
                            supportedModels = aliases
                        )
                    )
                )
            )
        )
    }
}
