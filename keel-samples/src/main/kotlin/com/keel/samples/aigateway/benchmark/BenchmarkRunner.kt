package com.keel.samples.aigateway.benchmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class BenchmarkRunner(
    private val config: BenchmarkConfig,
    private val outputDir: Path,
) {
    fun run(phase: BenchmarkPhase) = runBlocking {
        BenchmarkReports(outputDir).use { reports ->
            val cases = if (phase == BenchmarkPhase.SMOKE) BenchmarkConfig.smoke().cases() else config.cases()
            for (case in cases) {
                SimulatedProviderServer.start(port = 0).use { provider ->
                    val topology = BenchmarkTopologyFactory.create(case, provider.baseUrl, config.providerMaxConcurrency)
                    BenchmarkKeelServer.start(topology).use { keel ->
                        when (phase) {
                            BenchmarkPhase.SMOKE -> runSmokeCase(case, topology, keel, reports)
                            BenchmarkPhase.CALIBRATION -> runStep(case, topology, keel, reports, concurrency = 1, measuredSeconds = measuredSeconds(case))
                            BenchmarkPhase.STEP -> runStepSearch(case, topology, keel, reports)
                            BenchmarkPhase.SOAK -> runStep(case, topology, keel, reports, concurrency = config.concurrencyLadder.first(), measuredSeconds = config.soakMinutes * 60)
                            BenchmarkPhase.FULL -> {
                                runStepSearch(case, topology, keel, reports)
                                runStep(case, topology, keel, reports, concurrency = config.concurrencyLadder.first(), measuredSeconds = config.soakMinutes * 60)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun runSmokeCase(
        case: BenchmarkCase,
        topology: BenchmarkTopologySetup,
        keel: BenchmarkKeelServer,
        reports: BenchmarkReports,
    ): BenchmarkStepSummary {
        val result = BenchmarkHttpClient().use { client ->
            client.execute(
                keelBaseUrl = keel.baseUrl,
                apiKey = keel.apiKey,
                model = topology.requestModel,
                case = case,
                requestId = "${case.id}-${UUID.randomUUID()}",
            )
        }
        reports.writeRequest(result)
        val summary = BenchmarkStatistics.summarize(case, concurrency = 1, results = listOf(result), stability = config.stability)
        reports.writeStepSummary(summary)
        return summary
    }

    private suspend fun runStepSearch(
        case: BenchmarkCase,
        topology: BenchmarkTopologySetup,
        keel: BenchmarkKeelServer,
        reports: BenchmarkReports,
    ) {
        var sawUnstable = false
        for (concurrency in config.concurrencyLadder) {
            val summary = runStep(case, topology, keel, reports, concurrency, measuredSeconds(case))
            if (!summary.stable) {
                if (sawUnstable) break
                sawUnstable = true
            }
        }
    }

    private suspend fun runStep(
        case: BenchmarkCase,
        topology: BenchmarkTopologySetup,
        keel: BenchmarkKeelServer,
        reports: BenchmarkReports,
        concurrency: Int,
        measuredSeconds: Int,
    ): BenchmarkStepSummary {
        val deadline = System.nanoTime() + measuredSeconds.seconds.inWholeNanoseconds
        val client = BenchmarkHttpClient()
        val results = try {
            coroutineScope {
                (1..concurrency).map { worker ->
                    async(Dispatchers.IO) {
                        val workerResults = mutableListOf<BenchmarkRequestResult>()
                        do {
                            workerResults += client.execute(
                                keelBaseUrl = keel.baseUrl,
                                apiKey = keel.apiKey,
                                model = topology.requestModel,
                                case = case,
                                requestId = "${case.id}-w$worker-${UUID.randomUUID()}",
                            )
                        } while (System.nanoTime() < deadline)
                        workerResults
                    }
                }.awaitAll().flatten()
            }
        } finally {
            client.close()
        }
        results.forEach(reports::writeRequest)
        val summary = BenchmarkStatistics.summarize(case, concurrency, results, config.stability)
        reports.writeStepSummary(summary)
        return summary
    }

    private fun measuredSeconds(case: BenchmarkCase): Int =
        if (case.generationDurationMs <= 5_000L) config.measuredSecondsShort else config.measuredSecondsLong
}
