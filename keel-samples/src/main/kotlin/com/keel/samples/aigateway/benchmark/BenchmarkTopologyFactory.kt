package com.keel.samples.aigateway.benchmark

import com.keel.samples.aigateway.airelay.AIRelaySettings
import com.keel.samples.aigateway.airelay.AliasRouteConfig
import com.keel.samples.aigateway.airelay.AliasTargetConfig
import com.keel.samples.aigateway.airelay.AI_RELAY_MIN_TIMEOUT_MS
import com.keel.samples.aigateway.airelay.GroupExposureMode
import com.keel.samples.aigateway.airelay.ModelPricing
import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.protocol.WireProtocol

data class BenchmarkTopologySetup(
    val settings: AIRelaySettings,
    val groupId: String,
    val requestModel: String,
)

object BenchmarkTopologyFactory {
    fun create(
        case: BenchmarkCase,
        providerBaseUrl: String,
        providerMaxConcurrency: Int,
    ): BenchmarkTopologySetup {
        val protocolId = case.protocol.id
        val prefix = when (case.topology) {
            BenchmarkTopology.DIRECT_PROVIDER -> "bench-direct-$protocolId"
            BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER -> "bench-alias-specific-$protocolId"
            BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER -> "bench-alias-any-$protocolId-p${case.attachedProviderCount}"
        }
        val targetModels = List(case.attachedProviderCount.coerceAtLeast(1)) { index ->
            "$prefix-upstream-model-${index + 1}"
        }
        val requestModel = when (case.topology) {
            BenchmarkTopology.DIRECT_PROVIDER -> "$prefix-model"
            BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER,
            BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER -> "$prefix-alias"
        }
        val keys = targetModels.mapIndexed { index, targetModel ->
            val channelId = "$prefix-provider-${index + 1}"
            PooledKeyConfig(
                keyId = channelId,
                apiKey = "bench-provider-key-${index + 1}",
                maxConcurrency = providerMaxConcurrency,
                supportedModels = listOf(if (case.topology == BenchmarkTopology.DIRECT_PROVIDER) requestModel else targetModel),
                provider = UpstreamProviderConfig(
                    providerId = channelId,
                    baseUrl = providerBaseUrl,
                    protocol = case.protocol.toWireProtocol(),
                    timeoutMs = maxOf(AI_RELAY_MIN_TIMEOUT_MS, case.generationDurationMs * 3),
                    defaultHeaders = mapOf(
                        "X-Benchmark-Provider-Id" to channelId,
                        "X-Benchmark-Generation-Ms" to case.generationDurationMs.toString(),
                    ),
                ),
            )
        }
        val modelAliases = when (case.topology) {
            BenchmarkTopology.DIRECT_PROVIDER -> listOf(requestModel)
            BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER,
            BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER -> (listOf(requestModel) + targetModels).distinct()
        }
        val aliasRoutes = when (case.topology) {
            BenchmarkTopology.DIRECT_PROVIDER -> emptyList()
            BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER -> listOf(
                AliasRouteConfig(
                    aliasName = requestModel,
                    targets = listOf(AliasTargetConfig(targetModels.first(), keys.first().keyId)),
                ),
            )
            BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER -> listOf(
                AliasRouteConfig(
                    aliasName = requestModel,
                    targets = targetModels.zip(keys).map { (model, key) -> AliasTargetConfig(model, key.keyId) },
                ),
            )
        }
        val chain = PoolChainConfig(
            chainId = prefix,
            modelAliases = modelAliases,
            levels = listOf(
                PoolLevelConfig(
                    levelId = "$prefix-l1",
                    levelIndex = 1,
                    provider = UpstreamProviderConfig(
                        providerId = "$prefix-provider-level",
                        baseUrl = providerBaseUrl,
                        protocol = case.protocol.toWireProtocol(),
                        timeoutMs = maxOf(AI_RELAY_MIN_TIMEOUT_MS, case.generationDurationMs * 3),
                    ),
                    keys = keys,
                    cooldownMs = 5_000,
                ),
            ),
            exposureMode = GroupExposureMode.ALIASES_AND_MODELS,
            aliasRoutes = aliasRoutes,
        )
        val pricing = modelAliases.map {
            ModelPricing(model = it, inputCostPerMTok = 0.0, outputCostPerMTok = 0.0)
        }
        return BenchmarkTopologySetup(
            settings = AIRelaySettings(chains = listOf(chain), pricings = pricing),
            groupId = prefix,
            requestModel = requestModel,
        )
    }
}

fun BenchmarkProtocol.toWireProtocol(): WireProtocol = when (this) {
    BenchmarkProtocol.ANTHROPIC_MESSAGES -> WireProtocol.ANTHROPIC_MESSAGES
    BenchmarkProtocol.OPENAI_RESPONSES -> WireProtocol.OPENAI_RESPONSES
}
