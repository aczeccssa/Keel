package com.keel.samples.aigateway.benchmark

import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
data class BenchmarkStepSummary(
    val caseId: String,
    val concurrency: Int,
    val requests: Int,
    val successes: Int,
    val errors: Int,
    val status503Count: Int,
    val status503Rate: Double,
    val errorRate: Double,
    val p50Ms: Long,
    val p90Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val maxMs: Long,
    val stable: Boolean,
    val instabilityReason: String?,
    val attribution: Map<String, Int>,
)

@Serializable
data class BenchmarkTimeWindow(
    val epochSecond: Long,
    val p99Ms: Long,
    val requestCount: Int,
)

object BenchmarkStatistics {
    fun summarize(
        case: BenchmarkCase,
        concurrency: Int,
        results: List<BenchmarkRequestResult>,
        stability: BenchmarkStability,
        windows: List<BenchmarkTimeWindow> = emptyList(),
    ): BenchmarkStepSummary {
        val requests = results.size
        val errors = results.count { it.status == 0 || it.status >= 400 }
        val status503 = results.count { it.status == 503 }
        val status503Rate = if (requests == 0) 0.0 else status503.toDouble() / requests
        val errorRate = if (requests == 0) 0.0 else errors.toDouble() / requests
        val latencies = results.map { it.latencyMs }.sorted()
        val drift = hasSustainedP99Drift(windows, stability)
        val reason = when {
            status503Rate >= stability.max503Rate -> "503_rate"
            errorRate >= stability.maxErrorRate -> "error_rate"
            drift -> "p99_drift"
            else -> null
        }
        return BenchmarkStepSummary(
            caseId = case.id,
            concurrency = concurrency,
            requests = requests,
            successes = requests - errors,
            errors = errors,
            status503Count = status503,
            status503Rate = status503Rate,
            errorRate = errorRate,
            p50Ms = percentile(latencies, 50.0),
            p90Ms = percentile(latencies, 90.0),
            p95Ms = percentile(latencies, 95.0),
            p99Ms = percentile(latencies, 99.0),
            maxMs = latencies.lastOrNull() ?: 0,
            stable = reason == null,
            instabilityReason = reason,
            attribution = results.filter { it.status == 503 }.groupingBy { attribute503(it) }.eachCount(),
        )
    }

    fun hasSustainedP99Drift(windows: List<BenchmarkTimeWindow>, stability: BenchmarkStability): Boolean {
        if (windows.size < stability.p99DriftWindowCount + 1) return false
        val p99s = windows.map { it.p99Ms }
        val rising = p99s.windowed(stability.p99DriftWindowCount + 1).any { values ->
            values.zipWithNext().all { (left, right) -> right > left }
        }
        val baseline = p99s.first().coerceAtLeast(1)
        val sustainedAboveBaseline = p99s.drop(1)
            .windowed(stability.p99DriftWindowCount)
            .any { values -> values.all { it > baseline * stability.p99DriftRatio } }
        return rising || sustainedAboveBaseline
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        if (sorted.isEmpty()) return 0
        val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun attribute503(result: BenchmarkRequestResult): String {
        val message = result.errorMessage?.lowercase().orEmpty()
        return when {
            message.contains("pool") || message.contains("no upstream") -> "pool_exhausted"
            message.contains("timeout") -> "gateway_timeout"
            message.contains("provider") -> "provider_503"
            message.contains("accounting") || message.contains("usage") -> "local_accounting_or_usage_error"
            message.contains("connection") || message.contains("reset") || message.contains("closed") -> "transport_error"
            else -> "unknown_503"
        }
    }
}
