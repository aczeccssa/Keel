package com.keel.samples.aigateway.benchmark

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedWriter

class BenchmarkReports(private val outputDir: Path) : Closeable {
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val requestsWriter: BufferedWriter
    private val timeseriesWriter: BufferedWriter
    private val summaries = mutableListOf<BenchmarkStepSummary>()

    init {
        Files.createDirectories(outputDir)
        requestsWriter = outputDir.resolve("requests.jsonl").bufferedWriter()
        timeseriesWriter = outputDir.resolve("timeseries.jsonl").bufferedWriter()
    }

    fun writeRequest(result: BenchmarkRequestResult) {
        requestsWriter.appendLine(json.encodeToString(result))
        requestsWriter.flush()
    }

    fun writeTimeWindow(window: BenchmarkTimeWindow) {
        timeseriesWriter.appendLine(json.encodeToString(window))
        timeseriesWriter.flush()
    }

    fun writeStepSummary(summary: BenchmarkStepSummary) {
        summaries += summary
        flushSummaries()
    }

    private fun flushSummaries() {
        outputDir.resolve("summary.json").bufferedWriter().use { writer ->
            writer.append(json.encodeToString(summaries))
        }
        outputDir.resolve("summary.csv").bufferedWriter().use { writer ->
            writer.appendLine("caseId,concurrency,requests,successes,errors,status503Count,status503Rate,errorRate,p50Ms,p90Ms,p95Ms,p99Ms,maxMs,stable,instabilityReason")
            summaries.forEach { summary ->
                writer.appendLine(
                    listOf(
                        summary.caseId,
                        summary.concurrency,
                        summary.requests,
                        summary.successes,
                        summary.errors,
                        summary.status503Count,
                        summary.status503Rate,
                        summary.errorRate,
                        summary.p50Ms,
                        summary.p90Ms,
                        summary.p95Ms,
                        summary.p99Ms,
                        summary.maxMs,
                        summary.stable,
                        summary.instabilityReason.orEmpty(),
                    ).joinToString(",")
                )
            }
        }
    }

    override fun close() {
        requestsWriter.close()
        timeseriesWriter.close()
        flushSummaries()
    }
}
