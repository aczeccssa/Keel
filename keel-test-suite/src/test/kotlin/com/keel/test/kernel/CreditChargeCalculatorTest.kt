package com.keel.test.kernel

import com.keel.contract.ai.TokenUsage
import com.keel.samples.aigateway.airelay.usage.CreditChargeCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreditChargeCalculatorTest {
    private val calculator = CreditChargeCalculator()

    @Test
    fun largerRequestsConsumeMoreBaseCredits() {
        val small = calculator.calculate(
            usage = TokenUsage(promptTokens = 1_000, completionTokens = 500),
            modelCreditMultiplier = 1.0,
            aliasCreditMultiplier = null,
        )
        val large = calculator.calculate(
            usage = TokenUsage(promptTokens = 100_000, completionTokens = 20_000),
            modelCreditMultiplier = 1.0,
            aliasCreditMultiplier = null,
        )

        assertEquals(1L, small)
        assertTrue(large > small, "100k-token request should cost more credits than a 1k-token request")
    }

    @Test
    fun aliasMultiplierOverridesModelMultiplier() {
        val credits = calculator.calculate(
            usage = TokenUsage(promptTokens = 2_000, completionTokens = 200),
            modelCreditMultiplier = 2.0,
            aliasCreditMultiplier = 5.0,
        )

        assertEquals(5L, credits)
    }
}
