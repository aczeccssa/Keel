package com.keel.kernel.observability

data class ObservabilityConfig(
    val traceBufferSize: Int = 500,
    val flowBufferSize: Int = 500,
    val statusPollIntervalMs: Long = 2000
) {
    companion object {
        fun fromSystem(): ObservabilityConfig {
            return ObservabilityConfig(
                traceBufferSize = System.getProperty("keel.observability.traceBufferSize")?.toIntOrNull() ?: 500,
                flowBufferSize = System.getProperty("keel.observability.flowBufferSize")?.toIntOrNull() ?: 500,
                statusPollIntervalMs = System.getProperty("keel.observability.statusPollIntervalMs")?.toLongOrNull() ?: 2000
            )
        }
    }
}
