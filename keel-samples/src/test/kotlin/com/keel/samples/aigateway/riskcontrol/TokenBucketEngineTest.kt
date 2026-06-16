package com.keel.samples.aigateway.riskcontrol

import com.keel.contract.ai.RateLimitContext
import com.keel.contract.ai.RateLimitDecision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenBucketEngineTest {
    @Test
    fun `snapshot includes recent rejected events for monitoring`() = runTest {
        val engine = TokenBucketEngine(
            initialRules = listOf(
                RateLimitRule(
                    ruleId = "ip-tight",
                    name = "Tight IP rule",
                    dimension = RiskRateLimitDimension.IP,
                    pathPattern = "/v1/*",
                    methods = setOf("POST"),
                    capacity = 1,
                    refillRatePerSec = 0.01,
                    priority = 100,
                    enabled = true
                )
            )
        )
        val context = RateLimitContext(
            ip = "127.0.0.1",
            userId = "usr-demo",
            keyId = "key-demo",
            model = "claude-opus-4-6",
            path = "/api/plugins/airelay/v1/messages",
            method = "POST"
        )

        assertTrue(engine.tryAcquire(context) is RateLimitDecision.Allowed)
        assertTrue(engine.tryAcquire(context) is RateLimitDecision.Rejected)

        val snapshot = engine.snapshot()

        assertEquals(1, snapshot.totalRejected)
        assertTrue(snapshot.recentRejections.isNotEmpty())
        assertEquals("ip-tight", snapshot.recentRejections.first().ruleId)
        assertEquals("127.0.0.1", snapshot.recentRejections.first().value)
    }
}
