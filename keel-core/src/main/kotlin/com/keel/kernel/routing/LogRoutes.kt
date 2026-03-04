package com.keel.kernel.routing

import com.keel.contract.dto.KeelResponse
import com.keel.kernel.api.KeelApi
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogEntry
import com.keel.kernel.logging.LogLevel
import com.keel.kernel.logging.LogLevelData
import com.keel.kernel.logging.SetLogLevelRequest
import com.lestere.opensource.logger.SoulLogger
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.request.receive
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

object LogRouteInstaller {
    fun install(route: Route) {
        with(route) {
            systemApi {
                typedRoute("/logs") {
                    @KeelApi("Get recent log entries", tags = ["system", "logs"], responseEnvelope = true)
                    typedGet<List<LogEntry>>("/recent") {
                        val limit = call.parameters["limit"]?.toIntOrNull() ?: 100
                        val service = KeelLoggerService.getInstance()
                        val logs = service.getRecentLogs(limit)
                        call.respond(KeelResponse.success(data = logs))
                    }

                    @KeelApi("Get available log levels", tags = ["system", "logs"], responseEnvelope = true)
                    typedGet<LogLevelData>("/levels") {
                        val service = KeelLoggerService.getInstance()
                        val data = LogLevelData(
                            currentLevel = service.currentLevel.value.name,
                            availableLevels = LogLevel.availableLevels()
                        )
                        call.respond(KeelResponse.success(data = data))
                    }

                    @KeelApi(
                        "Set log level",
                        tags = ["system", "logs"],
                        errorStatuses = [400],
                        responseEnvelope = true
                    )
                    typedPost<SetLogLevelRequest, LogLevelData>("/level") {
                        try {
                            val request = call.receive<SetLogLevelRequest>()
                            val level = LogLevel.fromString(request.level)
                            val service = KeelLoggerService.getInstance()
                            service.setLevel(level)
                            val data = LogLevelData(
                                currentLevel = level.name,
                                availableLevels = LogLevel.availableLevels()
                            )
                            call.respond(KeelResponse.success(data = data, message = "Log level set to ${level.name}"))
                        } catch (e: Exception) {
                            call.respond(KeelResponse.failure<Unit>(400, "Invalid request: ${e.message}"))
                        }
                    }

                    @KeelApi("Clear log buffer", tags = ["system", "logs"], responseEnvelope = true)
                    typedPost<String>("/clear") {
                        val service = KeelLoggerService.getInstance()
                        service.clear()
                        call.respond(KeelResponse.success(data = "Log buffer cleared"))
                    }

                    // SSE endpoint — intentionally excluded from OpenAPI.
                    sse("/stream") {
                        val service = KeelLoggerService.getInstance()

                        send(ServerSentEvent(data = """{"type":"connected"}""", event = "system"))

                        val recentLogs = service.getRecentLogs(100)
                        for (entry in recentLogs) {
                            val dt = kotlinx.datetime.Instant.fromEpochMilliseconds(entry.timestamp).toString()
                            var formatted = "$dt [${entry.level}] [${entry.source}] ${entry.message}"
                            if (entry.throwable != null) {
                                formatted += "\n" + entry.throwable
                            }
                            val jsonData = """{"data":${Json.encodeToString(formatted)}}"""
                            send(ServerSentEvent(data = jsonData, event = "log"))
                        }

                        val merged = Channel<ServerSentEvent>(Channel.BUFFERED)

                        val logJob = launch {
                            SoulLogger.logStream.collect { logger ->
                                val jsonData = """{"data":${Json.encodeToString(logger.toString())}}"""
                                merged.send(ServerSentEvent(data = jsonData, event = "log"))
                            }
                        }

                        val heartbeatJob = launch {
                            while (isActive) {
                                delay(15_000)
                                merged.send(ServerSentEvent(comments = "heartbeat"))
                            }
                        }

                        try {
                            for (event in merged) {
                                send(event)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Client disconnected normally
                        } catch (e: io.ktor.util.cio.ChannelWriteException) {
                            // Ignored, client closed connection
                        } catch (e: io.ktor.utils.io.ClosedWriteChannelException) {
                            // Ignored, client closed connection
                        } catch (e: java.io.IOException) {
                            // Ignored, broken pipe or connection reset
                        } finally {
                            logJob.cancel()
                            heartbeatJob.cancel()
                            merged.close()
                        }
                    }

                    @KeelApi("Download logs as JSON file", tags = ["system", "logs"])
                    get("/download") {
                        val service = KeelLoggerService.getInstance()
                        val logs = service.getRecentLogs(1000)
                        val json = Json { prettyPrint = true }
                        val content = json.encodeToString(logs)
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.Attachment.withParameter(
                                ContentDisposition.Parameters.FileName, "keel-logs.json"
                            ).toString()
                        )
                        call.respondText(content, ContentType.Application.Json)
                    }
                }
            }
        }
    }
}

fun Route.logRoutes() {
    LogRouteInstaller.install(this)
}
