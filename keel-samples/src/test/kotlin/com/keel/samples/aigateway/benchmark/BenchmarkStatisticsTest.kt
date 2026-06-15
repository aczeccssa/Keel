package com.keel.samples.aigateway.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkStatisticsTest {
    @Test
    fun percentilesAreComputedFromSortedLatency() {
        val stats = BenchmarkStatistics.summarize(
            case = sampleCase(),
            concurrency = 10,
            results = (1..100).map { sampleResult(status = 200, latencyMs = it.toLong()) },
            stability = BenchmarkStability(),
        )

        assertEquals(50, stats.p50Ms)
        assertEquals(90, stats.p90Ms)
        assertEquals(95, stats.p95Ms)
        assertEquals(99, stats.p99Ms)
    }

    @Test
    fun status503AboveThresholdIsUnstableAndAttributedPoolExhausted() {
        val results = (1..1000).map { index ->
            if (index <= 2) sampleResult(status = 503, latencyMs = 10, errorMessage = "pool exhausted")
            else sampleResult(status = 200, latencyMs = 10)
        }
        val stats = BenchmarkStatistics.summarize(sampleCase(), 100, results, BenchmarkStability())

        assertFalse(stats.stable)
        assertEquals(0.002, stats.status503Rate)
        assertEquals(2, stats.attribution["pool_exhausted"])
    }

    @Test
    fun p99DriftAcrossThreeWindowsIsUnstable() {
        val windows = listOf(
            BenchmarkTimeWindow(epochSecond = 1, p99Ms = 100, requestCount = 10),
            BenchmarkTimeWindow(epochSecond = 2, p99Ms = 130, requestCount = 10),
            BenchmarkTimeWindow(epochSecond = 3, p99Ms = 170, requestCount = 10),
            BenchmarkTimeWindow(epochSecond = 4, p99Ms = 220, requestCount = 10),
        )

        assertTrue(BenchmarkStatistics.hasSustainedP99Drift(windows, BenchmarkStability()))
    }

    private fun sampleCase() = BenchmarkCase(
        BenchmarkProtocol.OPENAI_RESPONSES,
        BenchmarkRequestMode.BLOCKING,
        BenchmarkTopology.DIRECT_PROVIDER,
        1,
        1_000
    )

    private fun sampleResult(status: Int, latencyMs: Long, errorMessage: String? = null) = BenchmarkRequestResult(
        requestId = "r-$latencyMs-$status",
        caseId = sampleCase().id,
        protocol = BenchmarkProtocol.OPENAI_RESPONSES,
        requestMode = BenchmarkRequestMode.BLOCKING,
        topology = BenchmarkTopology.DIRECT_PROVIDER,
        attachedProviderCount = 1,
        generationDurationMs = 1_000,
        status = status,
        latencyMs = latencyMs,
        errorMessage = errorMessage,
    )
}
