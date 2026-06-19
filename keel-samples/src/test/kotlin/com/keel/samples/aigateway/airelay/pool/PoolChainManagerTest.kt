package com.keel.samples.aigateway.airelay.pool

import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PoolChainManagerTest {
    @Test
    fun transientServerFailuresUseShortCooldownThenBackOff() {
        val manager = PoolChainManager(listOf(testChain()))
        val selection = manager.selectCandidates("default", "gpt-5.5").single()

        val beforeFirst = System.currentTimeMillis()
        manager.markFailure(selection, 502, "upstream failed")
        val firstCooldown = cooldownRemainingMs(manager, beforeFirst)

        manager.markFailure(selection, 502, "upstream failed again")
        val secondCooldown = cooldownRemainingMs(manager, System.currentTimeMillis())

        assertTrue(firstCooldown in 1..15_000, "first 502 cooldown should be short, was ${firstCooldown}ms")
        assertTrue(secondCooldown > firstCooldown, "repeated 502 should back off")
        assertTrue(secondCooldown <= 60_000, "cooldown should cap at the level max")
    }

    @Test
    fun successResetsConsecutiveFailureBackoff() {
        val manager = PoolChainManager(listOf(testChain()))
        val selection = manager.selectCandidates("default", "gpt-5.5").single()

        manager.markFailure(selection, 502, "upstream failed")
        manager.markFailure(selection, 502, "upstream failed again")
        manager.markSuccess(selection)

        val beforeNextFailure = System.currentTimeMillis()
        manager.markFailure(selection, 502, "upstream failed after recovery")
        val cooldown = cooldownRemainingMs(manager, beforeNextFailure)

        assertTrue(cooldown in 1..15_000, "success should reset 5xx backoff, was ${cooldown}ms")
    }

    private fun cooldownRemainingMs(manager: PoolChainManager, baselineMs: Long): Long {
        val key = manager.snapshot().chains.single().levels.single().keys.single()
        assertEquals("COOLDOWN", key.status)
        val until = assertNotNull(key.cooldownUntilEpochMs)
        return until - baselineMs
    }

    private fun testChain(): PoolChainConfig = PoolChainConfig(
        chainId = "default",
        modelAliases = listOf("gpt-5.5"),
        levels = listOf(
            PoolLevelConfig(
                levelId = "default-p0",
                levelIndex = 0,
                provider = UpstreamProviderConfig("test", protocol = WireProtocol.OPENAI_RESPONSES),
                keys = listOf(PooledKeyConfig("ch-test", supportedModels = listOf("gpt-5.5"))),
                cooldownMs = 60_000,
            )
        )
    )
}
