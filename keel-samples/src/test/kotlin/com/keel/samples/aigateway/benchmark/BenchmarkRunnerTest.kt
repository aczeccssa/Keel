package com.keel.samples.aigateway.benchmark

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class BenchmarkRunnerTest {
    @Test
    fun smokeRunProducesReports() {
        val outputDir = Files.createTempDirectory("ai-relay-runner-smoke-")

        BenchmarkRunner(BenchmarkConfig.smoke(), outputDir).run(BenchmarkPhase.SMOKE)

        assertTrue(outputDir.resolve("summary.csv").exists())
        assertTrue(outputDir.resolve("requests.jsonl").readText().contains("anthropic_messages"))
        assertTrue(outputDir.resolve("requests.jsonl").readText().contains("openai_responses"))
    }
}
