package com.keel.kernel.isolation

import com.keel.ipc.runtime.HealthRequest
import com.keel.ipc.runtime.HealthResponse
import com.keel.ipc.runtime.InvokeRequest
import com.keel.ipc.runtime.InvokeResponse
import com.keel.ipc.runtime.PluginFrameCodec
import com.keel.ipc.runtime.PluginIpcJson
import com.keel.ipc.runtime.ShutdownRequest
import com.keel.ipc.runtime.ShutdownResponse
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointDefinition
import com.keel.kernel.plugin.PluginExecutionMode
import com.keel.kernel.plugin.PluginProcessState
import com.keel.kernel.plugin.PluginRuntimeConfig
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PluginProcessSupervisor(
    private val descriptor: PluginDescriptor,
    private val pluginClassName: String,
    private val config: PluginRuntimeConfig,
    private val classpath: String,
    private val socketPath: Path,
    private val runtimeDir: Path,
    private val onStateChange: (PluginProcessState) -> Unit
) {
    private val logger = KeelLoggerService.getLogger("PluginProcessSupervisor")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalUuidApi::class)
    private val authToken: String = Uuid.random().toString()

    private var process: Process? = null
    private var healthJob: Job? = null

    suspend fun start() {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                launchProcess()
                waitUntilReady()
                onStateChange(PluginProcessState.RUNNING)
                startHealthChecks()
                return
            } catch (error: Throwable) {
                lastError = error
                logger.warn("Failed to start isolated plugin ${descriptor.pluginId} attempt=${attempt + 1}: ${error.message}")
                stopProcess(force = true)
                onStateChange(PluginProcessState.FAILED)
            }
        }
        throw IllegalStateException("Failed to start isolated plugin ${descriptor.pluginId}", lastError)
    }

    suspend fun stop() {
        onStateChange(PluginProcessState.STOPPING)
        healthJob?.cancel()
        runCatching { sendMessage(ShutdownRequest(authToken = authToken), ShutdownResponse.serializer()) }
        stopProcess(force = false)
        onStateChange(PluginProcessState.STOPPED)
    }

    fun invoke(
        endpoint: PluginEndpointDefinition<*, *>,
        call: ApplicationCall,
        bodyJson: String?
    ): InvokeResponse {
        if (process?.isAlive != true) {
            onStateChange(PluginProcessState.FAILED)
            return InvokeResponse(
                requestId = call.request.headers["X-Request-Id"] ?: Uuid.random().toString(),
                status = 503,
                errorMessage = "Plugin '${descriptor.pluginId}' process is unavailable"
            )
        }
        val request = InvokeRequest(
            authToken = authToken,
            requestId = call.request.headers["X-Request-Id"] ?: Uuid.random().toString(),
            endpointId = endpoint.endpointId,
            method = endpoint.method.value,
            rawPath = call.request.path(),
            pathParameters = call.parameters.entries().associate { it.key to it.value.first() },
            queryParameters = call.request.queryParameters.entries().associate { it.key to it.value },
            headers = call.request.headers.entries().associate { it.key to it.value },
            bodyJson = bodyJson
        )
        return sendMessage(request, InvokeResponse.serializer())
    }

    private fun launchProcess() {
        onStateChange(PluginProcessState.STARTING)
        runtimeDir.toFile().mkdirs()
        socketPath.deleteIfExists()
        val javaBinary = File(System.getProperty("java.home"), "bin/java").absolutePath
        val processBuilder = ProcessBuilder(
            javaBinary,
            "-cp",
            classpath,
            "com.keel.kernel.isolation.IsolatedPluginMain",
            "--plugin-id=${descriptor.pluginId}",
            "--plugin-class=$pluginClassName",
            "--socket-path=${socketPath.pathString}",
            "--auth-token=$authToken",
            "--config-path=${File("config/plugins/${descriptor.pluginId}.json").absolutePath}",
            "--execution-mode=${PluginExecutionMode.ISOLATED_JVM.name.lowercase()}"
        )
        process = processBuilder.start()
        captureOutput(process!!.inputStream, false)
        captureOutput(process!!.errorStream, true)
    }

    private fun waitUntilReady() {
        val deadline = System.nanoTime() + Duration.ofMillis(config.startupTimeoutMs).toNanos()
        while (System.nanoTime() < deadline) {
            if (process?.isAlive != true) {
                throw IllegalStateException("Isolated plugin process exited before socket became ready")
            }
            if (socketPath.exists()) {
                val healthy = runCatching {
                    sendMessage(HealthRequest(authToken = authToken), HealthResponse.serializer())
                }.getOrNull()
                if (healthy?.state == PluginProcessState.RUNNING.name) {
                    return
                }
            }
            Thread.sleep(100)
        }
        throw IllegalStateException("Timed out waiting for isolated plugin socket ${socketPath.pathString}")
    }

    private fun startHealthChecks() {
        healthJob = scope.launch {
            while (isActive) {
                delay(config.healthCheckIntervalMs)
                if (process?.isAlive != true) {
                    onStateChange(PluginProcessState.FAILED)
                    break
                }
                val healthy = runCatching {
                    sendMessage(HealthRequest(authToken = authToken), HealthResponse.serializer())
                }.getOrNull()
                if (healthy == null || healthy.state != PluginProcessState.RUNNING.name) {
                    onStateChange(PluginProcessState.FAILED)
                }
            }
        }
    }

    private fun stopProcess(force: Boolean) {
        healthJob?.cancel()
        val current = process ?: return
        if (force) {
            current.destroyForcibly()
        } else {
            current.destroy()
            if (!current.waitFor(2, TimeUnit.SECONDS)) {
                current.destroyForcibly()
            }
        }
        socketPath.deleteIfExists()
        process = null
    }

    private fun captureOutput(stream: java.io.InputStream, error: Boolean) {
        scope.launch {
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                lines.forEach { line ->
                    if (error) {
                        logger.error("[isolated:${descriptor.pluginId}] $line")
                    } else {
                        logger.info("[isolated:${descriptor.pluginId}] $line")
                    }
                }
            }
        }
    }

    private fun <T> sendMessage(message: Any, serializer: kotlinx.serialization.KSerializer<T>): T {
        val socketAddress = UnixDomainSocketAddress.of(socketPath)
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(socketAddress)
            val payload = when (message) {
                is InvokeRequest -> PluginIpcJson.instance.encodeToString(InvokeRequest.serializer(), message)
                is HealthRequest -> PluginIpcJson.instance.encodeToString(HealthRequest.serializer(), message)
                is ShutdownRequest -> PluginIpcJson.instance.encodeToString(ShutdownRequest.serializer(), message)
                else -> error("Unsupported IPC message type: ${message::class.qualifiedName}")
            }
            PluginFrameCodec.write(channel, payload)
            val responsePayload = PluginFrameCodec.read(channel)
            return PluginIpcJson.instance.decodeFromString(serializer, responsePayload)
        }
    }
}
