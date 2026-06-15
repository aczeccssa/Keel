package com.keel.samples.aigateway.benchmark

import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkProtocol {
    ANTHROPIC_MESSAGES,
    OPENAI_RESPONSES;

    val path: String get() = when (this) {
        ANTHROPIC_MESSAGES -> "/api/plugins/airelay/v1/messages"
        OPENAI_RESPONSES -> "/api/plugins/airelay/v1/responses"
    }

    val id: String get() = name.lowercase()
}

@Serializable
enum class BenchmarkRequestMode {
    BLOCKING,
    STREAMING;

    val isStreaming: Boolean get() = this == STREAMING
    val id: String get() = name.lowercase()
}

@Serializable
enum class BenchmarkTopology {
    DIRECT_PROVIDER,
    ALIAS_SPECIFIC_PROVIDER,
    ALIAS_ANY_ATTACHED_PROVIDER;

    val id: String get() = name.lowercase()
}

@Serializable
data class BenchmarkConfig(
    val protocols: List<BenchmarkProtocol>,
    val requestModes: List<BenchmarkRequestMode>,
    val topologies: List<BenchmarkTopology>,
    val attachedProviderCounts: List<Int>,
    val generationDurationsMs: List<Long>,
    val providerMaxConcurrency: Int = 1_000_000,
    val concurrencyLadder: List<Int>,
    val warmupSeconds: Int = 10,
    val measuredSecondsShort: Int = 90,
    val measuredSecondsLong: Int = 180,
    val soakMinutes: Int = 10,
    val stability: BenchmarkStability = BenchmarkStability(),
) {
    init {
        require(providerMaxConcurrency > 0) { "providerMaxConcurrency must be positive" }
        require(concurrencyLadder.isNotEmpty()) { "concurrencyLadder must not be empty" }
    }

    fun cases(): List<BenchmarkCase> = protocols.flatMap { protocol ->
        requestModes.flatMap { mode ->
            topologies.flatMap { topology ->
                val providerCounts = if (topology == BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER) {
                    attachedProviderCounts
                } else {
                    listOf(1)
                }
                providerCounts.flatMap { count ->
                    generationDurationsMs.map { duration ->
                        BenchmarkCase(protocol, mode, topology, count, duration)
                    }
                }
            }
        }
    }

    companion object {
        fun smoke(): BenchmarkConfig = BenchmarkConfig(
            protocols = listOf(BenchmarkProtocol.ANTHROPIC_MESSAGES, BenchmarkProtocol.OPENAI_RESPONSES),
            requestModes = listOf(BenchmarkRequestMode.BLOCKING, BenchmarkRequestMode.STREAMING),
            topologies = listOf(BenchmarkTopology.DIRECT_PROVIDER),
            attachedProviderCounts = listOf(1),
            generationDurationsMs = listOf(100L),
            concurrencyLadder = listOf(1, 2),
            warmupSeconds = 0,
            measuredSecondsShort = 2,
            measuredSecondsLong = 2,
            soakMinutes = 1,
        )

        fun full(): BenchmarkConfig = BenchmarkConfig(
            protocols = listOf(BenchmarkProtocol.ANTHROPIC_MESSAGES, BenchmarkProtocol.OPENAI_RESPONSES),
            requestModes = listOf(BenchmarkRequestMode.BLOCKING, BenchmarkRequestMode.STREAMING),
            topologies = listOf(
                BenchmarkTopology.DIRECT_PROVIDER,
                BenchmarkTopology.ALIAS_SPECIFIC_PROVIDER,
                BenchmarkTopology.ALIAS_ANY_ATTACHED_PROVIDER,
            ),
            attachedProviderCounts = listOf(2, 3, 5),
            generationDurationsMs = listOf(1_000L, 3_000L, 5_000L, 10_000L, 20_000L, 40_000L),
            concurrencyLadder = listOf(10, 25, 50, 100, 200, 400, 800, 1_200, 1_600, 2_400),
        )
    }
}

@Serializable
data class BenchmarkStability(
    val max503Rate: Double = 0.001,
    val maxErrorRate: Double = 0.005,
    val p99DriftWindowCount: Int = 3,
    val p99DriftRatio: Double = 1.25,
)

@Serializable
data class BenchmarkCase(
    val protocol: BenchmarkProtocol,
    val requestMode: BenchmarkRequestMode,
    val topology: BenchmarkTopology,
    val attachedProviderCount: Int,
    val generationDurationMs: Long,
) {
    val id: String
        get() = "${protocol.id}-${requestMode.id}-${topology.id}-p$attachedProviderCount-${generationDurationMs}ms"
}

enum class BenchmarkPhase {
    SMOKE,
    CALIBRATION,
    STEP,
    SOAK,
    FULL;

    companion object {
        fun parse(value: String?): BenchmarkPhase = when (value?.lowercase()) {
            null, "", "smoke" -> SMOKE
            "calibration" -> CALIBRATION
            "step" -> STEP
            "soak" -> SOAK
            "full" -> FULL
            else -> error("Unknown benchmark phase: $value")
        }
    }
}
