import com.keel.samples.aigateway.benchmark.BenchmarkConfig
import com.keel.samples.aigateway.benchmark.BenchmarkPhase
import com.keel.samples.aigateway.benchmark.BenchmarkRunner
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

fun main(args: Array<String>) {
    val parsed = args.mapNotNull { arg ->
        val index = arg.indexOf('=')
        if (arg.startsWith("--") && index > 2) arg.substring(2, index) to arg.substring(index + 1) else null
    }.toMap()
    val phase = BenchmarkPhase.parse(parsed["phase"])
    val outputDir = parsed["output"]?.let(Path::of)
        ?: Path.of("build", "reports", "ai-relay-benchmark", System.currentTimeMillis().toString())
    val config = parsed["config"]?.let { path ->
        Json { ignoreUnknownKeys = true }.decodeFromString<BenchmarkConfig>(Path.of(path).readText())
    } ?: if (phase == BenchmarkPhase.SMOKE) BenchmarkConfig.smoke() else BenchmarkConfig.full()

    Files.createDirectories(outputDir)
    println("AI relay benchmark phase=$phase output=$outputDir cases=${config.cases().size}")
    BenchmarkRunner(config, outputDir).run(phase)
    println("AI relay benchmark complete output=$outputDir")
}
