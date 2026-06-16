package com.keel.samples.aigateway.airelay

import io.ktor.http.ContentType
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.http.content.OutgoingContent
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal class RelaySseContent(
    private val events: Flow<ServerSentEvent>,
    private val onComplete: suspend (Throwable?) -> Unit
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Text.EventStream
    override val headers = headersOf("Cache-Control", "no-cache")

    override suspend fun writeTo(channel: ByteWriteChannel) {
        var failure: Throwable? = null
        try {
            events.collect { event ->
                channel.writeStringUtf8(renderServerSentEvent(event))
                channel.flush()
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                onComplete(failure)
            } catch (completionError: Throwable) {
                if (failure == null && completionError !is CancellationException) throw completionError
            }
        }
    }
}

internal fun renderServerSentEvent(event: ServerSentEvent): String = buildString {
    event.id?.let { append("id: ").append(it).append('\n') }
    event.event?.let { append("event: ").append(it).append('\n') }
    event.retry?.let { append("retry: ").append(it).append('\n') }
    val data = event.data.orEmpty()
    if (data.isEmpty()) {
        append("data:").append('\n')
    } else {
        data.lines().forEach { line ->
            append("data: ").append(line).append('\n')
        }
    }
    append('\n')
}

internal fun parseSseBlock(block: String): ServerSentEvent? {
    if (block.isBlank()) return null
    var eventName: String? = null
    var data: String? = null
    var id: String? = null
    var retry: Long? = null
    for (raw in block.lineSequence()) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) continue
        val idx = line.indexOf(':')
        val (key, value) = if (idx == -1) line to "" else line.substring(0, idx) to line.substring(idx + 1).trimStart()
        when (key) {
            "event" -> eventName = value
            "data" -> data = if (data == null) value else data + "\n" + value
            "id" -> id = value
            "retry" -> retry = value.toLongOrNull()
        }
    }
    if (data == null && eventName == null && id == null && retry == null) return null
    return ServerSentEvent(data = data ?: "", event = eventName, id = id, retry = retry)
}

internal class SseChunkDecoder {
    private val buffer = StringBuilder()

    fun append(chunk: String): ServerSentEvent? = appendAll(chunk).firstOrNull()

    fun appendAll(chunk: String): List<ServerSentEvent> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)
        val events = mutableListOf<ServerSentEvent>()
        while (true) {
            val boundary = findBoundary() ?: break
            val rawBlock = buffer.substring(0, boundary)
            val consumed = if (buffer.startsWith("\r\n\r\n", boundary)) 4 else 2
            buffer.delete(0, boundary + consumed)
            parseSseBlock(rawBlock)?.let(events::add)
        }
        return events
    }

    fun flush(): ServerSentEvent? {
        val remaining = buffer.toString()
        buffer.clear()
        return parseSseBlock(remaining)
    }

    private fun findBoundary(): Int? {
        val text = buffer.toString()
        val unix = text.indexOf("\n\n")
        val windows = text.indexOf("\r\n\r\n")
        return listOf(unix, windows).filter { it >= 0 }.minOrNull()
    }
}
