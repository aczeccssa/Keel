package com.keel.samples.aigateway.benchmark

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.Writer
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SimulatedProviderServer private constructor(
    private val engine: EmbeddedServer<*, *>,
    val port: Int,
    private val state: State,
) : Closeable {
    val baseUrl: String = "http://127.0.0.1:$port"

    data class ProviderRequestLog(
        val requestId: String?,
        val providerId: String,
        val protocol: BenchmarkProtocol,
        val streaming: Boolean,
        val generationMs: Long,
        val startedEpochMs: Long,
        val completedEpochMs: Long,
    )

    data class Snapshot(
        val totalRequests: Long,
        val activeByProvider: Map<String, Int>,
        val maxActiveByProvider: Map<String, Int>,
        val logs: List<ProviderRequestLog>,
    )

    private class State {
        val totalRequests = AtomicLong()
        val activeByProvider = ConcurrentHashMap<String, AtomicInteger>()
        val maxActiveByProvider = ConcurrentHashMap<String, AtomicInteger>()
        val logs = CopyOnWriteArrayList<ProviderRequestLog>()
    }

    fun snapshot(): Snapshot = Snapshot(
        totalRequests = state.totalRequests.get(),
        activeByProvider = state.activeByProvider.mapValues { it.value.get() },
        maxActiveByProvider = state.maxActiveByProvider.mapValues { it.value.get() },
        logs = state.logs.toList(),
    )

    override fun close() {
        engine.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun start(port: Int = 0): SimulatedProviderServer {
            val resolvedPort = if (port == 0) freePort() else port
            val state = State()
            val engine = embeddedServer(CIO, port = resolvedPort, host = "127.0.0.1") {
                routing {
                    post("/v1/messages") {
                        val request = json.parseToJsonElement(call.receiveText()).jsonObject
                        val context = ProviderContext.from(
                            requestId = call.request.header("X-Benchmark-Request-Id"),
                            providerId = call.request.header("X-Benchmark-Provider-Id"),
                            generationMs = call.request.header("X-Benchmark-Generation-Ms"),
                        )
                        val streaming = call.request.queryParameters["stream"] == "true" ||
                            request["stream"]?.jsonPrimitive?.contentOrNull == "true"
                        state.record(context, BenchmarkProtocol.ANTHROPIC_MESSAGES, streaming) {
                            if (streaming) {
                                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                    writeAnthropicStream(context)
                                }
                            } else {
                                delay(context.generationMs)
                                call.response.header("X-Benchmark-Provider-Id", context.providerId)
                                call.respondText(anthropicBody(request, context.providerId), ContentType.Application.Json)
                            }
                        }
                    }

                    post("/v1/responses") {
                        val request = json.parseToJsonElement(call.receiveText()).jsonObject
                        val context = ProviderContext.from(
                            requestId = call.request.header("X-Benchmark-Request-Id"),
                            providerId = call.request.header("X-Benchmark-Provider-Id"),
                            generationMs = call.request.header("X-Benchmark-Generation-Ms"),
                        )
                        val streaming = call.request.queryParameters["stream"] == "true" ||
                            request["stream"]?.jsonPrimitive?.contentOrNull == "true"
                        state.record(context, BenchmarkProtocol.OPENAI_RESPONSES, streaming) {
                            if (streaming) {
                                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                    writeResponsesStream(context)
                                }
                            } else {
                                delay(context.generationMs)
                                call.response.header("X-Benchmark-Provider-Id", context.providerId)
                                call.respondText(responsesBody(request, context.providerId), ContentType.Application.Json)
                            }
                        }
                    }
                }
            }.start(wait = false)
            return SimulatedProviderServer(engine, resolvedPort, state)
        }

        private data class ProviderContext(
            val requestId: String?,
            val providerId: String,
            val generationMs: Long,
        ) {
            companion object {
                fun from(requestId: String?, providerId: String?, generationMs: String?): ProviderContext =
                    ProviderContext(
                        requestId = requestId,
                        providerId = providerId?.takeIf { it.isNotBlank() } ?: "provider-unknown",
                        generationMs = generationMs?.toLongOrNull()?.coerceAtLeast(0L) ?: 1_000L,
                    )
            }
        }

        private suspend fun State.record(
            context: ProviderContext,
            protocol: BenchmarkProtocol,
            streaming: Boolean,
            block: suspend () -> Unit,
        ) {
            val started = System.currentTimeMillis()
            totalRequests.incrementAndGet()
            val active = activeByProvider.computeIfAbsent(context.providerId) { AtomicInteger() }.incrementAndGet()
            maxActiveByProvider.computeIfAbsent(context.providerId) { AtomicInteger() }.updateAndGet { old -> maxOf(old, active) }
            try {
                block()
            } finally {
                activeByProvider.getValue(context.providerId).decrementAndGet()
                logs += ProviderRequestLog(
                    requestId = context.requestId,
                    providerId = context.providerId,
                    protocol = protocol,
                    streaming = streaming,
                    generationMs = context.generationMs,
                    startedEpochMs = started,
                    completedEpochMs = System.currentTimeMillis(),
                )
            }
        }

        private fun anthropicBody(request: JsonObject, providerId: String): String {
            val model = request["model"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            return buildJsonObject {
                put("id", JsonPrimitive("msg_$providerId"))
                put("type", JsonPrimitive("message"))
                put("role", JsonPrimitive("assistant"))
                put("model", JsonPrimitive(model))
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive("simulated response from $providerId"))
                    })
                })
                put("stop_reason", JsonPrimitive("end_turn"))
                put("usage", buildJsonObject {
                    put("input_tokens", JsonPrimitive(16))
                    put("output_tokens", JsonPrimitive(8))
                })
            }.toString()
        }

        private suspend fun Writer.writeAnthropicStream(context: ProviderContext) {
            val gap = (context.generationMs / 3).coerceAtLeast(1)
            write("event: message_start\n")
            write("""data: {"type":"message_start","message":{"id":"msg_${context.providerId}","type":"message","role":"assistant","model":"simulated","content":[],"usage":{"input_tokens":16,"output_tokens":0}}}""" + "\n\n")
            flush()
            delay(gap)
            write("event: content_block_delta\n")
            write("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"simulated "}}""" + "\n\n")
            flush()
            delay(gap)
            write("event: content_block_delta\n")
            write("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"response"}}""" + "\n\n")
            flush()
            delay((context.generationMs - gap - gap).coerceAtLeast(1))
            write("event: message_delta\n")
            write("""data: {"type":"message_delta","usage":{"output_tokens":8},"delta":{"stop_reason":"end_turn"}}""" + "\n\n")
            write("event: message_stop\n")
            write("""data: {"type":"message_stop"}""" + "\n\n")
            flush()
        }

        private fun responsesBody(request: JsonObject, providerId: String): String {
            val model = request["model"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            return buildJsonObject {
                put("id", JsonPrimitive("resp_$providerId"))
                put("object", JsonPrimitive("response"))
                put("status", JsonPrimitive("completed"))
                put("model", JsonPrimitive(model))
                put("output", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive("msg_0"))
                        put("type", JsonPrimitive("message"))
                        put("role", JsonPrimitive("assistant"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("output_text"))
                                put("text", JsonPrimitive("simulated response from $providerId"))
                            })
                        })
                    })
                })
                put("output_text", JsonPrimitive("simulated response from $providerId"))
                put("usage", buildJsonObject {
                    put("input_tokens", JsonPrimitive(16))
                    put("output_tokens", JsonPrimitive(8))
                    put("total_tokens", JsonPrimitive(24))
                })
            }.toString()
        }

        private suspend fun Writer.writeResponsesStream(context: ProviderContext) {
            val gap = (context.generationMs / 3).coerceAtLeast(1)
            delay(gap)
            write("event: response.output_text.delta\n")
            write("""data: {"type":"response.output_text.delta","delta":"simulated "}""" + "\n\n")
            flush()
            delay(gap)
            write("event: response.output_text.delta\n")
            write("""data: {"type":"response.output_text.delta","delta":"response"}""" + "\n\n")
            flush()
            delay((context.generationMs - gap - gap).coerceAtLeast(1))
            write("event: response.completed\n")
            write("""data: {"type":"response.completed","response":{"id":"resp_${context.providerId}","object":"response","status":"completed","model":"simulated","usage":{"input_tokens":16,"output_tokens":8,"total_tokens":24}}}""" + "\n\n")
            flush()
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
