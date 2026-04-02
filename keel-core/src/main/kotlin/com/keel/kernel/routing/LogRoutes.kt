package com.keel.kernel.routing

import com.keel.contract.dto.KeelResponse
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogEntry
import com.keel.kernel.logging.LogLevel
import com.keel.kernel.logging.LogLevelData
import com.keel.kernel.logging.SetLogLevelRequest
import com.keel.openapi.runtime.OpenApiDoc
import com.lestere.opensource.logger.SoulLogger
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

object LogRouteInstaller {
    @Suppress("TooGenericExceptionCaught")
    fun install(route: Route) {
        with(route) {
            systemApi {
                typedRoute("/logs") {
                    typedGet<List<LogEntry>>("/recent", doc = OpenApiDoc(summary = "Get recent log entries", tags = listOf("system", "logs"), responseEnvelope = true)) {
                        val limit = call.parameters["limit"]?.toIntOrNull() ?: 100
                        val service = KeelLoggerService.getInstance()
                        val logs = service.getRecentLogs(limit)
                        call.respond(KeelResponse.success(data = logs))
                    }

                    typedGet<LogLevelData>("/levels", doc = OpenApiDoc(summary = "Get available log levels", tags = listOf("system", "logs"), responseEnvelope = true)) {
                        val service = KeelLoggerService.getInstance()
                        val data = LogLevelData(
                            currentLevel = service.currentLevel.value.name,
                            availableLevels = LogLevel.availableLevels()
                        )
                        call.respond(KeelResponse.success(data = data))
                    }

                    typedPost<SetLogLevelRequest, LogLevelData>(
                        "/level",
                        doc = OpenApiDoc(
                            summary = "Set log level",
                            tags = listOf("system", "logs"),
                            errorStatuses = setOf(400),
                            responseEnvelope = true
                        )
                    ) {
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
                            val msg = e.message ?: "Invalid request body"
                            call.respond(KeelResponse.failure<Unit>(400, "Invalid request: $msg"))
                        }
                    }

                    typedPost<String>("/clear", doc = OpenApiDoc(summary = "Clear log buffer", tags = listOf("system", "logs"), responseEnvelope = true)) {
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
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            // Client SSE connection closed — stream naturally terminating.
                        } catch (_: io.ktor.util.cio.ChannelWriteException) {
                            // Client closed write channel — stream naturally terminating.
                        } catch (_: io.ktor.utils.io.ClosedWriteChannelException) {
                            // Client closed write channel — stream naturally terminating.
                        } catch (_: java.io.IOException) {
                            // Broken pipe or connection reset — stream naturally terminating.
                        } finally {
                            logJob.cancel()
                            heartbeatJob.cancel()
                            merged.close()
                        }
                    }

                    typedGet<List<LogEntry>>("/download", doc = OpenApiDoc(summary = "Download logs as JSON file", tags = listOf("system", "logs"))) {
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
