package com.keel.samples.aigateway.token

import kotlin.test.Test
import kotlin.test.assertEquals

class TokenUsageEnrichmentTest {

    @Test
    fun enrichesOperatorAndCustomerIdentityAlongsideRoutingLabels() {
        val records = listOf(
            TokenUsageRecordView(
                recordId = "rec-1",
                requestId = "req-1",
                keyId = "key-1",
                userId = "usr-1",
                userGroupId = "default",
                model = "gpt-4o",
                provider = "openai",
                status = 200,
                transportStatus = 200,
                outcome = "SUCCESS",
                errorCode = null,
                errorDetail = null,
                usageSource = "PROVIDER",
                upstreamKeyId = "ch-1",
                poolLevelId = "premium-p0",
                routingGroupId = "premium",
                usage = com.keel.contract.ai.TokenUsage(),
                cost = com.keel.contract.ai.CostBreakdown(),
                latencyMs = 42,
                createdAt = "2026-06-20T00:00:00Z",
            ),
            TokenUsageRecordView(
                recordId = "rec-2",
                requestId = "req-2",
                keyId = "ckey-1",
                userId = "cust-1",
                userGroupId = "customer",
                model = "claude-sonnet",
                provider = "anthropic",
                status = 200,
                transportStatus = 200,
                outcome = "SUCCESS",
                errorCode = null,
                errorDetail = null,
                usageSource = "PROVIDER",
                upstreamKeyId = "ch-2",
                poolLevelId = "default-p0",
                routingGroupId = "default",
                usage = com.keel.contract.ai.TokenUsage(),
                cost = com.keel.contract.ai.CostBreakdown(),
                latencyMs = 15,
                createdAt = "2026-06-20T00:01:00Z",
            ),
        )

        val enriched = enrichUsageRecords(
            records = records,
            userEmailsById = mapOf("usr-1" to "operator@example.com"),
            customerSummariesById = mapOf("cust-1" to CustomerSummarySnapshot("cust-1", "customer@example.com")),
            channelNamesById = mapOf("ch-1" to "OpenAI Main", "ch-2" to "Anthropic Main"),
            groupNamesById = mapOf("premium" to "Premium", "default" to "Default"),
        )

        assertEquals("operator@example.com", enriched[0].userEmail)
        assertEquals("Premium", enriched[0].routingGroupName)
        assertEquals("OpenAI Main", enriched[0].channelName)

        assertEquals("cust-1", enriched[1].customerId)
        assertEquals("customer@example.com", enriched[1].customerEmail)
        assertEquals("customer@example.com", enriched[1].userEmail)
        assertEquals("Default", enriched[1].routingGroupName)
        assertEquals("Anthropic Main", enriched[1].channelName)
    }

    @Test
    fun preservesStructuredErrorFieldsWhenEnriching() {
        val records = listOf(
            TokenUsageRecordView(
                recordId = "rec-3",
                requestId = "req-3",
                keyId = "key-2",
                userId = "usr-2",
                userGroupId = "default",
                model = "claude-haiku-4-5-20251001",
                provider = "anthropic",
                status = 503,
                transportStatus = 503,
                outcome = "ERROR",
                errorCode = "pool_exhausted",
                errorDetail = "flat-detail",
                errorDetailJson = "{\"result\":\"EXHAUSTED\"}",
                routeTraceJson = "{\"attempts\":[]}",
                failureScope = "CHANNEL_MODEL",
                failureKind = "POOL_EXHAUSTED",
                selectedChannelId = "ch-9",
                usageSource = "NONE",
                upstreamKeyId = "ch-9",
                poolLevelId = "anthropic-p0",
                routingGroupId = "anthropic",
                usage = com.keel.contract.ai.TokenUsage(),
                cost = com.keel.contract.ai.CostBreakdown(),
                latencyMs = 7,
                createdAt = "2026-06-20T00:02:00Z",
            )
        )

        val enriched = enrichUsageRecords(
            records = records,
            userEmailsById = emptyMap(),
            customerSummariesById = emptyMap(),
            channelNamesById = mapOf("ch-9" to "Anthropic Pool"),
            groupNamesById = mapOf("anthropic" to "Anthropic"),
        )

        assertEquals("{\"result\":\"EXHAUSTED\"}", enriched.single().errorDetailJson)
        assertEquals("{\"attempts\":[]}", enriched.single().routeTraceJson)
        assertEquals("CHANNEL_MODEL", enriched.single().failureScope)
        assertEquals("POOL_EXHAUSTED", enriched.single().failureKind)
        assertEquals("Anthropic Pool", enriched.single().channelName)
    }
}
