package com.keel.samples.aigateway.benchmark

import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class BenchmarkReportsTest {
    @Test
    fun writesSummaryCsvJsonAndRequestJsonl() {
        val dir = Files.createTempDirectory("bench-report-test-")
        val reports = BenchmarkReports(dir)
        val case = BenchmarkCase(BenchmarkProtocol.OPENAI_RESPONSES, BenchmarkRequestMode.BLOCKING, BenchmarkTopology.DIRECT_PROVIDER, 1, 100)
        val result = BenchmarkRequestResult("req-1", case.id, case.protocol, case.requestMode, case.topology, 1, 100, 200, 12)
        val summary = BenchmarkStatistics.summarize(case, concurrency = 1, results = listOf(result), stability = BenchmarkStability())

        reports.writeRequest(result)
        reports.writeStepSummary(summary)
        reports.close()

        assertTrue(dir.resolve("requests.jsonl").readText().contains("req-1"))
        assertTrue(dir.resolve("summary.csv").readText().contains("caseId,concurrency"))
        assertTrue(dir.resolve("summary.json").readText().contains(case.id))
        assertTrue(dir.resolve("timeseries.jsonl").readLines().isEmpty())
    }
}
