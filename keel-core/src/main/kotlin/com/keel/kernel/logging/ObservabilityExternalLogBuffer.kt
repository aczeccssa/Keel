package com.keel.kernel.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory

data class ObservabilityExternalLogEntry(
    val timestamp: Long,
    val level: String,
    val loggerName: String,
    val message: String,
    val threadName: String,
    val throwable: String? = null
)

object ObservabilityExternalLogBuffer {
    private const val MAX_BUFFER_SIZE = 5_000
    private val buffer = ArrayDeque<ObservabilityExternalLogEntry>()

    fun record(
        timestamp: Long,
        level: String,
        loggerName: String,
        message: String,
        threadName: String,
        throwable: String? = null
    ) {
        val entry = ObservabilityExternalLogEntry(
            timestamp = timestamp,
            level = level,
            loggerName = loggerName,
            message = message,
            threadName = threadName,
            throwable = throwable
        )
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_BUFFER_SIZE) {
                buffer.removeFirst()
            }
        }
    }

    fun snapshot(limit: Int = 1000): List<ObservabilityExternalLogEntry> = synchronized(buffer) {
        buffer.toList().takeLast(limit)
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
        }
    }
}

internal class ObservabilityLogbackAppender : AppenderBase<ILoggingEvent>() {
    override fun append(eventObject: ILoggingEvent) {
        ObservabilityExternalLogBuffer.record(
            timestamp = eventObject.timeStamp,
            level = eventObject.level.levelStr,
            loggerName = eventObject.loggerName,
            message = eventObject.formattedMessage,
            threadName = eventObject.threadName,
            throwable = eventObject.throwableProxy?.let(ThrowableProxyUtil::asString)
        )
    }
}

internal object ObservabilityLogbackAppenderInstaller {
    private const val APPENDER_NAME = "KEEL_OBSERVABILITY_LOGBACK"

    fun install() {
        val loggerFactory = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val rootLogger = loggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        if (rootLogger.getAppender(APPENDER_NAME) != null) {
            return
        }

        val appender = ObservabilityLogbackAppender().apply {
            context = loggerFactory
            name = APPENDER_NAME
            start()
        }
        rootLogger.addAppender(appender)
    }
}
