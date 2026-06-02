package com.keel.samples.aigateway.airelay

import com.keel.samples.aigateway.airelay.config.ChannelRepository
import com.keel.samples.aigateway.airelay.config.UpsertChannelRequest
import com.keel.samples.aigateway.airelay.config.UpsertModelRequest
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AIRelaySettings(
    val chains: List<PoolChainConfig> = defaultChains(),
    val pricings: List<ModelPricing> = defaultPricing()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Load static settings. If a JSON config file is present (path from `keel.airelay.config`
         * system property, `KEEL_AIRELAY_CONFIG` env, or `airelay/pools.json` in the working dir),
         * it overrides the built-in defaults. The defaults remain the ultimate fallback.
         */
        fun load(): AIRelaySettings {
            val path = System.getProperty("keel.airelay.config")
                ?: System.getenv("KEEL_AIRELAY_CONFIG")
                ?: "airelay/pools.json"
            val file = File(path)
            if (file.isFile) {
                runCatching { return json.decodeFromString<AIRelaySettings>(file.readText()) }
            }
            return AIRelaySettings()
        }

        /**
         * Seed the runtime channel store from the file/default chains on first boot, so the
         * DB-backed config (managed via the UI) starts from a sensible baseline. Only real
         * (non-`mock://`) chains are seeded — mock chains are test scaffolding, not providers a
         * user would manage. Models are seeded from the pricing table joined to the chain aliases.
         */
        fun seedChannelsIfPresent(repo: ChannelRepository) {
            val settings = load()
            val pricingByModel = settings.pricings.associateBy { it.model }
            settings.chains.forEach { chain ->
                val level = chain.levels.firstOrNull() ?: return@forEach
                if (level.provider.baseUrl.startsWith("mock://")) return@forEach
                val key = level.keys.firstOrNull()
                repo.createChannel(
                    UpsertChannelRequest(
                        name = level.provider.providerId,
                        protocol = level.provider.protocol.name,
                        baseUrl = level.provider.baseUrl,
                        apiKey = key?.apiKey?.takeIf { it != "mock-key" } ?: "",
                        apiKeyEnv = key?.apiKeyEnv,
                        enabled = true,
                        priority = 0,
                        weight = key?.weight ?: 100,
                        maxConcurrency = key?.maxConcurrency ?: 10,
                        timeoutMs = level.provider.timeoutMs,
                        models = chain.modelAliases.map { alias ->
                            val p = pricingByModel[alias]
                            UpsertModelRequest(
                                publicModelName = alias,
                                upstreamModelName = "",
                                inputCostPerMTok = p?.inputCostPerMTok ?: 0.0,
                                outputCostPerMTok = p?.outputCostPerMTok ?: 0.0,
                                cacheCreationCostPerMTok = p?.cacheCreationCostPerMTok,
                                cacheReadCostPerMTok = p?.cacheReadCostPerMTok,
                                cachedInputDiscount = p?.cachedInputDiscount,
                                reasoningOutputCostPerMTok = p?.reasoningOutputCostPerMTok,
                                enabled = true
                            )
                        }
                    )
                )
            }
        }
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
