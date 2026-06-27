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
    fun transientServerFailuresOnlyCooldownAfterThresholdAndLateFailuresDoNotExtend() {
        val manager = PoolChainManager(listOf(testChain()))
        val selection = manager.selectCandidates("default", "gpt-5.5").single()

        manager.markFailure(selection, 502, "upstream failed")
        assertHealthy(manager)

        manager.markFailure(selection, 502, "upstream failed again")
        assertHealthy(manager)

        val beforeThird = System.currentTimeMillis()
        manager.markFailure(selection, 502, "upstream failed third time")
        val firstCooldown = cooldownRemainingMs(manager, beforeThird)
        val firstCooldownUntil = routeState(manager).cooldownUntilEpochMs

        manager.markFailure(selection, 502, "late in-flight upstream failure")
        val secondCooldownUntil = routeState(manager).cooldownUntilEpochMs

        assertTrue(firstCooldown in 1..10_000, "third 502 cooldown should stay short, was ${firstCooldown}ms")
        assertEquals(firstCooldownUntil, secondCooldownUntil, "late in-flight 502 should not extend cooldown")
    }

    @Test
    fun retryAfterCanExtendAnAlreadyOpenRouteBreaker() {
        val manager = PoolChainManager(listOf(testChain()))
        val selection = manager.selectCandidates("default", "gpt-5.5").single()

        manager.markFailure(selection, 502, "upstream failed")
        manager.markFailure(selection, 502, "upstream failed again")
        manager.markFailure(selection, 502, "upstream failed third time")
        val firstCooldownUntil = routeState(manager).cooldownUntilEpochMs

        manager.markFailure(selection, 429, "upstream rate limited", retryAfterSeconds = 30)
        val retryAfterCooldownUntil = routeState(manager).cooldownUntilEpochMs

        assertTrue(
            retryAfterCooldownUntil > firstCooldownUntil,
            "explicit Retry-After should be allowed to extend an already-open route breaker"
        )
    }

    @Test
    fun shorterRetryAfterDoesNotShortenAnAlreadyOpenRouteBreaker() {
        val manager = PoolChainManager(listOf(testChain()))
        val selection = manager.selectCandidates("default", "gpt-5.5").single()

        manager.markFailure(selection, 429, "upstream rate limited", retryAfterSeconds = 30)
        val firstCooldownUntil = routeState(manager).cooldownUntilEpochMs

        manager.markFailure(selection, 429, "shorter retry after", retryAfterSeconds = 1)
        val secondCooldownUntil = routeState(manager).cooldownUntilEpochMs

        assertEquals(firstCooldownUntil, secondCooldownUntil, "shorter Retry-After should not shorten an already-open route breaker")
    }

    @Test
    fun successResetsTransientFailureThreshold() {
        val manager = PoolChainManager(listOf(testChain()))
        val selection = manager.selectCandidates("default", "gpt-5.5").single()

        manager.markFailure(selection, 502, "upstream failed")
        manager.markFailure(selection, 502, "upstream failed again")
        manager.markSuccess(selection)

        manager.markFailure(selection, 502, "upstream failed after recovery")
        manager.markFailure(selection, 502, "upstream failed after recovery again")
        assertHealthy(manager)
    }

    @Test
    fun routeBreakerOnlyOpensAfterTransientThreshold() {
        val manager = PoolChainManager(listOf(testChain()))
        val selection = manager.selectCandidates("default", "gpt-5.5").single()

        manager.markFailure(selection, 502, "upstream failed")
        assertEquals("HEALTHY", manager.snapshot().chains.single().levels.single().keys.single().status)

        manager.markFailure(selection, 502, "upstream failed again")
        assertEquals("HEALTHY", manager.snapshot().chains.single().levels.single().keys.single().status)

        val beforeThird = System.currentTimeMillis()
        manager.markFailure(selection, 502, "upstream failed third time")
        val cooldown = cooldownRemainingMs(manager, beforeThird)

        assertTrue(cooldown in 1..10_000)
    }

    @Test
    fun explainSelectionIncludesBreakerAndFailureMetadata() {
        val manager = PoolChainManager(listOf(testChain()))
        val selection = manager.selectCandidates("default", "gpt-5.5").single()
        manager.markFailure(selection, 502, "upstream failed")
        manager.markFailure(selection, 502, "upstream failed again")
        manager.markFailure(selection, 502, "upstream failed third time")

        val explain = manager.explainSelection("default", "gpt-5.5")
        val key = explain.priorityTiers.single().ineligible.single()

        assertEquals("COOLDOWN", key.effectiveStatus)
        assertEquals("OPEN", key.breakerState)
        assertEquals("SERVER_5XX", key.failureKind)
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
    fun cooldownWrittenByAnInFlightOldManagerSurvivesReloadAfterThreshold() {
        val registry = PoolRuntimeRegistry()
        val oldManager = PoolChainManager(listOf(testChain()), registry)
        val selection = oldManager.selectCandidates("default", "gpt-5.5").single()
        val reloadedManager = PoolChainManager(listOf(testChain()), registry)

        oldManager.markFailure(selection, 502, "late upstream failure")
        oldManager.markFailure(selection, 502, "late upstream failure again")
        oldManager.markFailure(selection, 502, "late upstream failure third time")

        val explain = reloadedManager.explainSelection("default", "gpt-5.5")
        val key = explain.priorityTiers.single().ineligible.single()
        assertEquals("OPEN", key.breakerState)
        assertEquals("COOLDOWN", key.effectiveStatus)
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

    private fun assertHealthy(manager: PoolChainManager) {
        val key = manager.snapshot().chains.single().levels.single().keys.single()
        assertEquals("HEALTHY", key.status)
        assertNull(key.cooldownUntilEpochMs)
    }

    private fun routeState(manager: PoolChainManager): RouteStateSnapshot =
        manager.runtimeRegistry.routeSnapshot("ch-test", "gpt-5.5")

    private fun assertRouteOpen(manager: PoolChainManager) {
        assertEquals(BreakerState.OPEN, routeState(manager).breakerState)
        assertTrue(assertNotNull(routeState(manager).cooldownUntilEpochMs) > System.currentTimeMillis())
    }

    private fun cooldownRemainingMs(manager: PoolChainManager, baselineMs: Long): Long {
        val routeState = routeState(manager)
        assertEquals(BreakerState.OPEN, routeState.breakerState)
        val until = assertNotNull(routeState.cooldownUntilEpochMs)
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
