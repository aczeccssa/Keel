package com.keel.samples.aigateway.airelay.pool

import com.keel.samples.aigateway.airelay.PoolChainConfig
import com.keel.samples.aigateway.airelay.PoolLevelConfig
import com.keel.samples.aigateway.airelay.PooledKeyConfig
import com.keel.samples.aigateway.airelay.UpstreamProviderConfig
import com.keel.samples.aigateway.airelay.AliasRouteConfig
import com.keel.samples.aigateway.airelay.AliasRoutingPolicy
import com.keel.samples.aigateway.airelay.AliasTargetConfig
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

    @Test
    fun explainScoresUseTheSameSchedulerStateAsSelection() {
        val manager = PoolChainManager(listOf(weightedChain(100, 50)))
        val first = assertNotNull(manager.acquire("default", "gpt-5.5"))
        assertEquals("ch-a", first.selection.keyState.key.keyId)
        first.close()

        val explain = manager.explainSelection("default", "gpt-5.5")
        val scores = explain.priorityTiers.single().eligible.associate { it.channelId to assertNotNull(it.score) }

        assertEquals("ch-b", explain.selectedChannelId)
        assertTrue(scores.getValue("ch-b") > scores.getValue("ch-a"), "selected channel must have the highest reported score")
    }

    @Test
    fun cooldownWrittenByAnInFlightOldManagerSurvivesReload() {
        val registry = PoolRuntimeRegistry()
        val oldManager = PoolChainManager(listOf(testChain()), registry)
        val selection = oldManager.selectCandidates("default", "gpt-5.5").single()
        val reloadedManager = PoolChainManager(listOf(testChain()), registry)

        oldManager.markFailure(selection, 502, "late upstream failure")

        val key = reloadedManager.snapshot().chains.single().levels.single().keys.single()
        assertEquals("COOLDOWN", key.status)
        assertTrue(assertNotNull(key.cooldownUntilEpochMs) > System.currentTimeMillis())
    }

    @Test
    fun selectionCarriesTheResolvedAliasRoutingPolicy() {
        val chain = weightedChain(100, 100).copy(
            modelAliases = listOf("legacy"),
            aliasRoutes = listOf(
                AliasRouteConfig(
                    aliasName = "legacy",
                    targets = listOf(AliasTargetConfig("gpt-5.5")),
                    routingPolicy = AliasRoutingPolicy.ORDERED_FAILOVER,
                )
            ),
        )
        val manager = PoolChainManager(listOf(chain))

        val lease = assertNotNull(manager.acquire("default", "legacy"))

        assertEquals(AliasRoutingPolicy.ORDERED_FAILOVER, lease.selection.routingPolicy)
        lease.close()
    }

    @Test
    fun explainReturnsTheActualRequestFailoverTrace() {
        val manager = PoolChainManager(listOf(weightedChain(100, 100)))
        val first = assertNotNull(manager.acquire("default", "gpt-5.5", traceId = "req-1"))
        manager.markFailure(first.selection, 502, "first failed", latencyMs = 120)
        manager.recordFailover(first.selection)
        first.close()
        val second = assertNotNull(
            manager.acquire(
                "default",
                "gpt-5.5",
                excludedKeyIds = setOf(first.selection.keyState.key.keyId),
                traceId = "req-1",
            )
        )
        manager.markSuccess(second.selection, latencyMs = 80)
        second.close()

        val explain = manager.explainSelection("default", "gpt-5.5", traceId = "req-1")
        val trace = assertNotNull(explain.requestTrace)

        assertEquals(listOf("ch-a", "ch-b"), trace.attempts.map { it.channelId })
        assertEquals(listOf("FAILED", "SUCCESS"), trace.attempts.map { it.outcome })
        assertEquals(1, trace.failoverCount)
        assertEquals("ch-b", trace.selectedChannelId)
    }

    @Test
    fun poolViewsExposeRollingTrafficLatencyAndShareMetrics() {
        val manager = PoolChainManager(listOf(weightedChain(100, 50)))
        val success = assertNotNull(manager.acquire("default", "gpt-5.5", traceId = "metric-success"))
        manager.markSuccess(success.selection, latencyMs = 100)
        success.close()
        val failure = assertNotNull(manager.acquire("default", "gpt-5.5", traceId = "metric-failure"))
        manager.markFailure(failure.selection, 502, "failed", latencyMs = 300)
        failure.close()

        val pool = manager.listPools("default").single()
        val channelRequests = pool.channels.sumOf { it.metrics1m.selectedRequests }

        assertEquals(2L, channelRequests)
        assertEquals(2L, pool.metrics1m.selectedRequests)
        assertEquals(1L, pool.metrics1m.successRequests)
        assertEquals(1L, pool.metrics1m.failedRequests)
        assertEquals(0.5, pool.metrics1m.errorRate)
        assertEquals(300, pool.metrics1m.p95LatencyMs)
        assertEquals(2L, pool.totalRequests1m)
        assertEquals(0.5, pool.errorRate1m)
        assertEquals(300, pool.p95Latency1m)
        assertEquals(1L, pool.channels.sumOf { it.failedRequests1m })
        assertEquals(pool.metrics1m.errorRate, pool.errorRate1m)
        assertEquals(1.0, pool.channels.sumOf { it.expectedShare }, 0.0001)
        assertEquals(1.0, pool.channels.sumOf { it.trafficShare1m }, 0.0001)
        assertEquals(pool.metrics1m.selectedRequests, pool.metrics5m.selectedRequests)
        assertEquals(pool.metrics1m.selectedRequests, pool.metrics15m.selectedRequests)
    }

    @Test
    fun poolViewUsesLevelIndexWhenLegacyLevelIdDoesNotEncodePriority() {
        val level = testChain().levels.single().copy(levelId = "legacy-primary", levelIndex = 7)
        val manager = PoolChainManager(listOf(testChain().copy(levels = listOf(level))))

        assertEquals(7, manager.listPools("default").single().priority)
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
