package com.keel.samples.aigateway.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkModelsTest {
    @Test
    fun smokeMatrixIsSmallButCoversBothProtocolsAndModes() {
        val cases = BenchmarkConfig.smoke().cases()

        assertTrue(cases.any { it.protocol == BenchmarkProtocol.ANTHROPIC_MESSAGES })
        assertTrue(cases.any { it.protocol == BenchmarkProtocol.OPENAI_RESPONSES })
        assertTrue(cases.any { it.requestMode == BenchmarkRequestMode.BLOCKING })
        assertTrue(cases.any { it.requestMode == BenchmarkRequestMode.STREAMING })
        assertTrue(cases.size <= 8, "smoke matrix should stay cheap")
    }

    @Test
    fun fullMatrixIncludesGenerationDurationLadderAndAliasProviderCounts() {
        val config = BenchmarkConfig.full()

        assertEquals(listOf(1_000L, 3_000L, 5_000L, 10_000L, 20_000L, 40_000L), config.generationDurationsMs)
        assertEquals(listOf(2, 3, 5), config.attachedProviderCounts)
        assertTrue(config.topologies.contains(BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER))
    }

    @Test
    fun caseIdIsStableAndFilesystemSafe() {
        val case = BenchmarkCase(
            protocol = BenchmarkProtocol.OPENAI_RESPONSES,
            requestMode = BenchmarkRequestMode.STREAMING,
            topology = BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER,
            attachedProviderCount = 5,
            generationDurationMs = 40_000L
        )

        assertEquals("openai_responses-streaming-alias_any_attached_provider-p5-40000ms", case.id)
        assertFalse(case.id.contains(" "))
    }
}
