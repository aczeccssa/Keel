package com.keel.kernel.logging

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ObservabilityLogFileReader {
    private const val ENABLE_PROPERTY = "keel.observability.readLogFile"
    private val json = Json { ignoreUnknownKeys = true }
    private val ansiRegex = Regex("""\u001B\[[;\d]*m""")
    private val textLogPattern = Regex(
        """^(?<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(?<level>[A-Z]+)\s+\[(?<thread>.*?)]\s+(?<logger>.+?)\s+-\s+(?<message>.*)$"""
    )
    private val textTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun recentApplicationLogs(limit: Int = 2000): List<ObservabilityExternalLogEntry> {
        if (System.getProperty(ENABLE_PROPERTY, "true").equals("false", ignoreCase = true)) {
            return emptyList()
        }
        val path = resolveApplicationLogPath() ?: return emptyList()
        return readTailLines(path, limit)
            .mapNotNull(::parseLine)
    }

    private fun resolveApplicationLogPath(): Path? {
        val candidates = listOf(
            Paths.get("cache/log/application.log"),
            Paths.get("keel-samples/cache/log/application.log")
        )
        return candidates.firstOrNull(Files::exists)
    }

    private fun parseLine(rawLine: String): ObservabilityExternalLogEntry? {
        val line = ansiRegex.replace(rawLine.trim(), "")
        if (line.isBlank()) return null
        return if (line.startsWith("{")) parseJsonLine(line) else parseTextLine(line)
    }

    private fun parseJsonLine(line: String): ObservabilityExternalLogEntry? {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val timestamp = obj["timestamp"]?.jsonPrimitive?.contentOrNull?.let(::parseTimestamp) ?: return null
        val level = obj["level"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val loggerName = obj["logger"]?.jsonPrimitive?.contentOrNull ?: return null
        val threadName = obj["thread"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return null
        val throwable = obj["exception"]?.jsonPrimitive?.contentOrNull
        return ObservabilityExternalLogEntry(
            timestamp = timestamp,
            level = level,
            loggerName = loggerName,
            message = message,
            threadName = threadName,
            throwable = throwable
        )
    }

    private fun parseTextLine(line: String): ObservabilityExternalLogEntry? {
        val match = textLogPattern.matchEntire(line) ?: return null
        val timestamp = match.groups["timestamp"]?.value?.let(::parseTimestamp) ?: return null
        val level = match.groups["level"]?.value ?: return null
        val threadName = match.groups["thread"]?.value ?: "unknown"
        val loggerName = match.groups["logger"]?.value ?: return null
        val message = match.groups["message"]?.value ?: return null
        return ObservabilityExternalLogEntry(
            timestamp = timestamp,
            level = level,
            loggerName = loggerName,
            message = message,
            threadName = threadName
        )
    }

    private fun parseTimestamp(value: String): Long? {
        return runCatching { kotlinx.datetime.Instant.parse(value).toEpochMilliseconds() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(value, textTimestampFormatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
    }

    private fun readTailLines(path: Path, limit: Int): List<String> {
        if (limit <= 0 || !Files.exists(path)) return emptyList()
        val lines = ArrayDeque<String>(limit)
        RandomAccessFile(path.toFile(), "r").use { file ->
            var pointer = file.length() - 1
            val buffer = StringBuilder()

            while (pointer >= 0 && lines.size < limit) {
                file.seek(pointer)
                val byte = file.readByte().toInt().toChar()
                if (byte == '\n') {
                    if (buffer.isNotEmpty()) {
                        lines.addFirst(buffer.reverse().toString())
                        buffer.clear()
                    }
                } else if (byte != '\r') {
                    buffer.append(byte)
                }
                pointer--
            }

            if (buffer.isNotEmpty() && lines.size < limit) {
                lines.addFirst(buffer.reverse().toString())
            }
        }
        return lines.toList()
    }
}
