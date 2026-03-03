package com.keel.kernel.logging

import com.lestere.opensource.logger.SoulLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

enum class LogLevel(val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3);

    companion object {
        fun fromString(value: String): LogLevel {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INFO
        }

        fun availableLevels(): List<String> = entries.map { it.name }
    }
}

class ScopeLogger(private val source: String) {
    fun debug(message: String) = KeelLoggerService.getInstance().log(LogLevel.DEBUG, source, message)
    fun info(message: String) = KeelLoggerService.getInstance().log(LogLevel.INFO, source, message)
    fun warn(message: String) = KeelLoggerService.getInstance().log(LogLevel.WARN, source, message)
    fun warn(message: String, throwable: Throwable?) = KeelLoggerService.getInstance().log(LogLevel.WARN, source, message, throwable)
    fun error(message: String) = KeelLoggerService.getInstance().log(LogLevel.ERROR, source, message)
    fun error(message: String, throwable: Throwable?) = KeelLoggerService.getInstance().log(LogLevel.ERROR, source, message, throwable)
}

class KeelLoggerService private constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())

    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 256)

    private val _currentLevel = MutableStateFlow(LogLevel.INFO)
    val currentLevel: StateFlow<LogLevel> = _currentLevel.asStateFlow()

    fun log(level: LogLevel, source: String, message: String, throwable: Throwable? = null) {
        val formatted = "[$source] $message"

        // Delegate to SoulLogger for file + console output
        when (level) {
            LogLevel.DEBUG -> SoulLogger.debug(formatted)
            LogLevel.INFO -> SoulLogger.info(formatted)
            LogLevel.WARN -> SoulLogger.warn(formatted)
            LogLevel.ERROR -> {
                if (throwable != null) {
                    SoulLogger.error(RuntimeException(formatted, throwable))
                } else {
                    SoulLogger.error(RuntimeException(formatted))
                }
            }
        }

        // Filter by current level before buffering
        if (level.priority < _currentLevel.value.priority) return

        val entry = LogEntry(
            timestamp = Clock.System.now().toEpochMilliseconds(),
            level = level.name,
            source = source,
            message = message,
            throwable = throwable?.stackTraceToString()
        )

        // Update ring buffer (max 1000)
        _logs.value = (_logs.value + entry).takeLast(MAX_BUFFER_SIZE)

        // Emit to SSE flow (non-blocking, drops if no subscriber or buffer full)
        _logFlow.tryEmit(entry)
    }

    fun getRecentLogs(limit: Int = 100): List<LogEntry> {
        return _logs.value.takeLast(limit)
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun setLevel(level: LogLevel) {
        _currentLevel.value = level
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val MAX_BUFFER_SIZE = 1000

        @Volatile
        private var instance: KeelLoggerService? = null

        fun initialize(): KeelLoggerService {
            return instance ?: synchronized(this) {
                instance ?: KeelLoggerService().also { instance = it }
            }
        }

        fun getInstance(): KeelLoggerService {
            return instance ?: initialize()
        }

        fun getLogger(source: String): ScopeLogger {
            // Ensure service is initialized
            getInstance()
            return ScopeLogger(source)
        }
    }
}
