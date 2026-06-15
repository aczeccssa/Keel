package com.keel.samples.aigateway.benchmark

import com.keel.samples.aigateway.airelay.GroupExposureMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BenchmarkTopologyFactoryTest {
    @Test
    fun directProviderExposesRequestedModelWithoutAliasRoute() {
        val topology = BenchmarkTopologyFactory.create(
            case = BenchmarkCase(
                protocol = BenchmarkProtocol.ANTHROPIC_MESSAGES,
                requestMode = BenchmarkRequestMode.BLOCKING,
                topology = BenchmarkTopology.DIRECT_PROVIDER,
                attachedProviderCount = 1,
                generationDurationMs = 1_000
            ),
            providerBaseUrl = "http://127.0.0.1:19001",
            providerMaxConcurrency = 25
        )

        assertEquals("bench-direct-anthropic_messages", topology.groupId)
        assertEquals("bench-direct-anthropic_messages-model", topology.requestModel)
        assertEquals("bench-direct-anthropic_messages-model", topology.settings.chains.single().modelAliases.single())
        assertTrue(topology.settings.chains.single().aliasRoutes.isEmpty())
    }

    @Test
    fun aliasSpecificPinsAliasToOneChannel() {
        val topology = BenchmarkTopologyFactory.create(
            case = BenchmarkCase(
                protocol = BenchmarkProtocol.OPENAI_RESPONSES,
                requestMode = BenchmarkRequestMode.STREAMING,
                topology = BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER,
                attachedProviderCount = 1,
                generationDurationMs = 3_000
            ),
            providerBaseUrl = "http://127.0.0.1:19001",
            providerMaxConcurrency = 50
        )

        val alias = topology.settings.chains.single().aliasRoutes.single()
        assertEquals(topology.requestModel, alias.aliasName)
        assertEquals("bench-alias-specific-openai_responses-provider-1", alias.targets.single().channelId)
    }

    @Test
    fun aliasAnyCreatesRequestedProviderCountTargets() {
        val topology = BenchmarkTopologyFactory.create(
            case = BenchmarkCase(
                protocol = BenchmarkProtocol.ANTHROPIC_MESSAGES,
                requestMode = BenchmarkRequestMode.BLOCKING,
                topology = BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER,
                attachedProviderCount = 5,
                generationDurationMs = 5_000
            ),
            providerBaseUrl = "http://127.0.0.1:19001",
            providerMaxConcurrency = 100
        )

        val chain = topology.settings.chains.single()
        assertEquals(GroupExposureMode.ALIASES_AND_MODELS, chain.exposureMode)
        assertEquals(5, chain.levels.single().keys.size)
        assertEquals(5, chain.aliasRoutes.single().targets.size)
        assertNotNull(chain.levels.single().keys.first().provider)
    }
}
