package com.keel.samples.aigateway.airelay.pool

import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun equalWeightPoolAlternatesSelectionsWhenIdle() {
        val manager = PoolChainManager(listOf(weightedChain(100, 100)))

        val picks = buildList {
            repeat(6) {
                val lease = assertNotNull(manager.acquire("default", "gpt-5.5"))
                add(lease.selection.keyState.key.keyId)
                lease.close()
                manager.markSuccess(lease.selection)
            }
        }

        assertEquals(listOf("ch-a", "ch-b", "ch-a", "ch-b", "ch-a", "ch-b"), picks)
    }

    @Test
    fun weightedPoolProducesTwoToOneShareWhenIdle() {
        val manager = PoolChainManager(listOf(weightedChain(100, 50)))

        val picks = buildList {
            repeat(9) {
                val lease = assertNotNull(manager.acquire("default", "gpt-5.5"))
                add(lease.selection.keyState.key.keyId)
                lease.close()
                manager.markSuccess(lease.selection)
            }
        }

        assertEquals(6, picks.count { it == "ch-a" })
        assertEquals(3, picks.count { it == "ch-b" })
    }

    @Test
    fun acquireNeverExceedsMaxConcurrency() {
        val manager = PoolChainManager(listOf(singleKeyChain(maxConcurrency = 1)))

        val first = manager.acquire("default", "gpt-5.5")
        val second = manager.acquire("default", "gpt-5.5")

        assertNotNull(first)
        assertNull(second)
        first.close()
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

    private fun weightedChain(weightA: Int, weightB: Int): PoolChainConfig = PoolChainConfig(
        chainId = "default",
        modelAliases = listOf("gpt-5.5"),
        levels = listOf(
            PoolLevelConfig(
                levelId = "default-p0",
                levelIndex = 0,
                provider = UpstreamProviderConfig("test", protocol = WireProtocol.OPENAI_RESPONSES),
                keys = listOf(
                    PooledKeyConfig("ch-a", weight = weightA, supportedModels = listOf("gpt-5.5")),
                    PooledKeyConfig("ch-b", weight = weightB, supportedModels = listOf("gpt-5.5")),
                ),
                cooldownMs = 60_000,
            )
        )
    )

    private fun singleKeyChain(maxConcurrency: Int): PoolChainConfig = PoolChainConfig(
        chainId = "default",
        modelAliases = listOf("gpt-5.5"),
        levels = listOf(
            PoolLevelConfig(
                levelId = "default-p0",
                levelIndex = 0,
                provider = UpstreamProviderConfig("test", protocol = WireProtocol.OPENAI_RESPONSES),
                keys = listOf(
                    PooledKeyConfig(
                        "ch-only",
                        weight = 100,
                        maxConcurrency = maxConcurrency,
                        supportedModels = listOf("gpt-5.5")
                    )
                ),
                cooldownMs = 60_000,
            )
        )
    )
}
