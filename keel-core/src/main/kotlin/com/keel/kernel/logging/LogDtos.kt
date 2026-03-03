package com.keel.kernel.logging

import kotlinx.serialization.Serializable

@Serializable
data class LogEntry(
    val timestamp: Long,
    val level: String,
    val source: String,
    val message: String,
    val throwable: String? = null
)

@Serializable
data class LogStreamEvent(
    val type: String = "log",
    val data: LogEntry
)

@Serializable
data class LogLevelData(
    val currentLevel: String,
    val availableLevels: List<String>
)

@Serializable
data class SetLogLevelRequest(
    val level: String
)
