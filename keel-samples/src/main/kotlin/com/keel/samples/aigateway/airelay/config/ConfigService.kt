package com.keel.samples.aigateway.airelay.config

import com.keel.samples.aigateway.airelay.AliasRouteConfig
import com.keel.samples.aigateway.airelay.AliasTargetConfig
import com.keel.samples.aigateway.airelay.GroupExposureMode
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
 * [PoolChainManager] on every change (hot-apply, no restart). One enabled group becomes one
 * runtime pool chain; enabled channels inside the group are arranged into priority levels.
 * Channels with the same priority share a level and are weighted by their channel weight.
 */
class ConfigService(
    private val repository: ChannelRepository
) {
    private val managerRef = AtomicReference<PoolChainManager>(PoolChainManager(emptyList()))
    private val chainsRef = AtomicReference<List<PoolChainConfig>>(emptyList())
    private val pricingRef = AtomicReference<List<ModelPricing>>(emptyList())

    val poolChainManager: PoolChainManager get() = managerRef.get()
    val chains: List<PoolChainConfig> get() = chainsRef.get()
    val pricings: List<ModelPricing> get() = pricingRef.get()

    /** Rebuild the live config from the DB. Call after any channel/model mutation. */
    fun reload() {
        // Drop pricing rows for models that no longer exist in any enabled channel.
        repository.pruneUnconfiguredPricing()
        // Absorb any newly added channel models into the standalone pricing table so
        // the Pricing tab stays in sync without the operator having to re-enter rates.
        repository.absorbChannelPricingIntoStandalone()
        val channels = repository.listChannels().filter { it.enabled }
        val enabledGroups = repository.listGroups().filter { it.enabled }
        val chains = enabledGroups.mapNotNull { group ->
            val groupChannels = channels.mapNotNull { channel ->
                channel.memberships.firstOrNull { membership ->
                    membership.groupId == group.groupId && membership.enabled
                }?.let { membership -> channel to membership }
            }
            if (groupChannels.isEmpty() && group.aliasRoutes.isEmpty()) return@mapNotNull null
            groupChannels.toGroupChain(group)
        }
        // Pricing: standalone ModelPricingTable rows take precedence; channel-embedded rates
        // are the fallback for any model not in the standalone table.
        val standalone = repository.listPricings().map { p ->
            ModelPricing(
                model = p.model,
                variantKey = p.variantKey,
                label = p.label,
                billingUnitTokens = p.billingUnitTokens,
                tiers = p.tiers.map { tier ->
                    com.keel.samples.aigateway.airelay.ModelPricingTier(
                        startTokensInclusive = tier.startTokensInclusive,
                        endTokensExclusive = tier.endTokensExclusive,
                        billingUnitTokens = tier.billingUnitTokens,
                        inputCostPerUnit = tier.inputCostPerUnit,
                        outputCostPerUnit = tier.outputCostPerUnit,
                        cacheCreationCostPerUnit = tier.cacheCreationCostPerUnit,
                        cacheReadCostPerUnit = tier.cacheReadCostPerUnit,
                        reasoningOutputCostPerUnit = tier.reasoningOutputCostPerUnit,
                    )
                },
                inputCostPerMTok = p.inputCostPerMTok,
                outputCostPerMTok = p.outputCostPerMTok,
                cacheCreationCostPerMTok = p.cacheCreationCostPerMTok,
                cacheReadCostPerMTok = p.cacheReadCostPerMTok,
                cachedInputDiscount = p.cachedInputDiscount,
                reasoningOutputCostPerMTok = p.reasoningOutputCostPerMTok,
                creditMultiplier = p.creditMultiplier
            )
        }
        val standaloneModels = standalone.map { it.model }.toSet()
        val fromChannels = channels.flatMap { channel ->
            channel.models.filter { it.enabled }.map { m ->
                ModelPricing(
                    model = m.publicModelName,
                    inputCostPerMTok = m.inputCostPerMTok,
                    outputCostPerMTok = m.outputCostPerMTok,
                    cacheCreationCostPerMTok = m.cacheCreationCostPerMTok,
                    cacheReadCostPerMTok = m.cacheReadCostPerMTok,
                    cachedInputDiscount = m.cachedInputDiscount,
                    reasoningOutputCostPerMTok = m.reasoningOutputCostPerMTok,
                    creditMultiplier = m.creditMultiplier
                )
            }
        }.filter { it.model !in standaloneModels }.distinctBy { it.model }
        val pricing = standalone + fromChannels
        managerRef.set(PoolChainManager(chains))
        chainsRef.set(chains)
        pricingRef.set(pricing)
    }

    private fun List<Pair<ChannelView, ChannelMembershipView>>.toGroupChain(group: GroupView): PoolChainConfig {
        val groupId = group.groupId
        val directModels = flatMap { (channel, _) -> channel.models.filter { it.enabled }.map { it.publicModelName } }
            .distinct()
            .sorted()
        val aliasNames = group.aliasRoutes.filter { it.enabled }.map { it.aliasName }.distinct().sorted()
        val exposureMode = GroupExposureMode.from(group.exposureMode)
        val exposedModels = when (exposureMode) {
            GroupExposureMode.ALIASES_ONLY -> aliasNames
            GroupExposureMode.ALIASES_AND_MODELS -> (directModels + aliasNames).distinct().sorted()
            GroupExposureMode.ALL_MODELS -> directModels
            GroupExposureMode.ALIAS_ONLY,
            GroupExposureMode.ALIAS_AND_MODEL_NAMES,
            GroupExposureMode.MODEL_NAMES_ONLY -> error("legacy exposure mode should be normalized")
        }
        val levels = groupBy { it.second.priority }
            .toSortedMap(compareByDescending { it })
            .entries
            .mapIndexed { index, entry ->
                val priority = entry.key
                val levelChannels = entry.value.sortedBy { it.first.name }
                PoolLevelConfig(
                    levelId = "$groupId-p$priority",
                    levelIndex = index + 1,
                    provider = UpstreamProviderConfig(
                        providerId = "$groupId-priority-$priority",
                        protocol = levelChannels.firstOrNull()?.first?.protocol?.let { runCatching { WireProtocol.valueOf(it) }.getOrNull() }
                            ?: WireProtocol.ANTHROPIC_MESSAGES
                    ),
                    keys = levelChannels.map { (channel, membership) -> channel.toPooledKey(membership, directModels) }
                )
            }
        return PoolChainConfig(
            chainId = groupId,
            modelAliases = exposedModels,
            levels = levels,
            exposureMode = exposureMode,
            aliasRoutes = group.aliasRoutes.map { alias ->
                AliasRouteConfig(
                    aliasName = alias.aliasName,
                    targetModels = alias.targetModels,
                    enabled = alias.enabled,
                    creditMultiplier = alias.creditMultiplier,
                    targets = alias.targets.map { target -> AliasTargetConfig(target.model, target.channelId) }
                )
            }
        )
    }

    private fun ChannelView.toPooledKey(membership: ChannelMembershipView, groupDirectModels: List<String>): PooledKeyConfig {
        val channelModels = models.filter { it.enabled }.map { it.publicModelName }.ifEmpty { groupDirectModels }
        val protocol = runCatching { WireProtocol.valueOf(protocol) }.getOrDefault(WireProtocol.ANTHROPIC_MESSAGES)
        return PooledKeyConfig(
            keyId = channelId,
            apiKeyEnv = apiKeyEnv,
            apiKey = repository.decryptedKey(channelId)?.ifEmpty { "missing-key" } ?: "missing-key",
            weight = membership.weight,
            maxConcurrency = maxConcurrency,
            supportedModels = channelModels,
            modelMap = models.filter { it.enabled }.associate { model ->
                model.publicModelName to model.upstreamModelName.ifBlank { model.publicModelName }
            },
            provider = UpstreamProviderConfig(
                providerId = name,
                baseUrl = baseUrl,
                protocol = protocol,
                timeoutMs = timeoutMs
            )
        )
    }
}
