package com.keel.samples.aigateway.airelay

import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import kotlinx.serialization.Serializable

@Serializable
data class AIRelaySettings(
    val chains: List<PoolChainConfig> = defaultChains(),
    val pricings: List<ModelPricing> = defaultPricing()
) {
    companion object {
        fun load(): AIRelaySettings = AIRelaySettings()
    }
}

@Serializable
data class PoolChainConfig(
    val chainId: String,
    val modelAliases: List<String>,
    val levels: List<PoolLevelConfig>
)

@Serializable
data class PoolLevelConfig(
    val levelId: String,
    val levelIndex: Int,
    val provider: UpstreamProviderConfig,
    val keys: List<PooledKeyConfig>,
    val cooldownMs: Long = 60_000
)

@Serializable
data class UpstreamProviderConfig(
    val providerId: String,
    val baseUrl: String = "mock://local",
    val protocol: WireProtocol,
    val timeoutMs: Long = 60_000,
    val defaultHeaders: Map<String, String> = emptyMap()
)

@Serializable
data class PooledKeyConfig(
    val keyId: String,
    val apiKeyEnv: String? = null,
    val apiKey: String = "mock-key",
    val weight: Int = 100,
    val maxConcurrency: Int = 10,
    val supportedModels: List<String> = emptyList()
)

@Serializable
data class ModelPricing(
    val model: String,
    val inputCostPerMTok: Double,
    val outputCostPerMTok: Double,
    val cacheCreationCostPerMTok: Double? = null,
    val cacheReadCostPerMTok: Double? = null,
    val cachedInputDiscount: Double? = null,
    val reasoningOutputCostPerMTok: Double? = null
)

fun defaultChains(): List<PoolChainConfig> = listOf(
    PoolChainConfig(
        chainId = "openai-chat-chain",
        modelAliases = listOf("gpt-chat-only"),
        levels = listOf(
            PoolLevelConfig(
                levelId = "l1-openai-chat",
                levelIndex = 1,
                provider = UpstreamProviderConfig("mock-openai-chat", protocol = WireProtocol.OPENAI_CHAT),
                keys = listOf(PooledKeyConfig("mock-openai-chat-1"))
            ),
            PoolLevelConfig(
                levelId = "l2-openai-chat",
                levelIndex = 2,
                provider = UpstreamProviderConfig("mock-openai-chat-l2", protocol = WireProtocol.OPENAI_CHAT),
                keys = listOf(PooledKeyConfig("mock-openai-chat-2"))
            )
        )
    ),
    PoolChainConfig(
        chainId = "openai-responses-chain",
        modelAliases = listOf("gpt-responses-only"),
        levels = listOf(
            PoolLevelConfig(
                levelId = "l1-openai-responses",
                levelIndex = 1,
                provider = UpstreamProviderConfig("mock-openai-responses", protocol = WireProtocol.OPENAI_RESPONSES),
                keys = listOf(PooledKeyConfig("mock-openai-responses-1"))
            )
        )
    ),
    PoolChainConfig(
        chainId = "anthropic-chain",
        modelAliases = listOf("claude-only"),
        levels = listOf(
            PoolLevelConfig(
                levelId = "l1-anthropic",
                levelIndex = 1,
                provider = UpstreamProviderConfig("mock-anthropic", protocol = WireProtocol.ANTHROPIC_MESSAGES),
                keys = listOf(PooledKeyConfig("mock-anthropic-1"))
            )
        )
    ),
    PoolChainConfig(
        chainId = "default-chain",
        modelAliases = listOf("gpt-4o-mini", "gpt-4o", "gpt-5"),
        levels = listOf(
            PoolLevelConfig(
                levelId = "l1-default-responses",
                levelIndex = 1,
                provider = UpstreamProviderConfig("mock-openai-default", protocol = WireProtocol.OPENAI_RESPONSES),
                keys = listOf(PooledKeyConfig("mock-default-1"))
            ),
            PoolLevelConfig(
                levelId = "l2-default-chat",
                levelIndex = 2,
                provider = UpstreamProviderConfig("mock-openai-default-chat", protocol = WireProtocol.OPENAI_CHAT),
                keys = listOf(PooledKeyConfig("mock-default-2"))
            )
        )
    ),
    PoolChainConfig(
        chainId = "claude-default-chain",
        modelAliases = listOf("claude-sonnet-4-20250514"),
        levels = listOf(
            PoolLevelConfig(
                levelId = "l1-claude-default",
                levelIndex = 1,
                provider = UpstreamProviderConfig("mock-anthropic-default", protocol = WireProtocol.ANTHROPIC_MESSAGES),
                keys = listOf(PooledKeyConfig("mock-claude-default-1"))
            )
        )
    )
)

fun defaultPricing(): List<ModelPricing> = listOf(
    ModelPricing("gpt-chat-only", inputCostPerMTok = 1.0, outputCostPerMTok = 2.0, cachedInputDiscount = 0.5, reasoningOutputCostPerMTok = 2.0),
    ModelPricing("gpt-responses-only", inputCostPerMTok = 1.0, outputCostPerMTok = 2.0, cachedInputDiscount = 0.5, reasoningOutputCostPerMTok = 2.0),
    ModelPricing("claude-only", inputCostPerMTok = 3.0, outputCostPerMTok = 15.0, cacheCreationCostPerMTok = 3.75, cacheReadCostPerMTok = 0.30),
    ModelPricing("gpt-4o-mini", inputCostPerMTok = 0.15, outputCostPerMTok = 0.60, cachedInputDiscount = 0.5, reasoningOutputCostPerMTok = 0.60),
    ModelPricing("gpt-4o", inputCostPerMTok = 2.5, outputCostPerMTok = 10.0, cachedInputDiscount = 0.5, reasoningOutputCostPerMTok = 10.0),
    ModelPricing("gpt-5", inputCostPerMTok = 1.25, outputCostPerMTok = 10.0, cachedInputDiscount = 0.5, reasoningOutputCostPerMTok = 10.0),
    ModelPricing("claude-sonnet-4-20250514", inputCostPerMTok = 3.0, outputCostPerMTok = 15.0, cacheCreationCostPerMTok = 3.75, cacheReadCostPerMTok = 0.30)
)
