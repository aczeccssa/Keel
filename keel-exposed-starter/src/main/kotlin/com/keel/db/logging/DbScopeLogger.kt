package com.keel.db.logging

import com.lestere.opensource.logger.SoulLogger

class DbScopeLogger(private val source: String) {
    fun debug(message: String) {
        SoulLogger.debug("[$source] $message")
    }

    fun info(message: String) {
        SoulLogger.info("[$source] $message")
    }

    fun warn(message: String) {
        SoulLogger.warn("[$source] $message")
    }

    fun warn(message: String, throwable: Throwable?) {
        SoulLogger.warn("[$source] $message")
        if (throwable != null) SoulLogger.error(throwable)
    }

    fun error(message: String) {
        SoulLogger.error(RuntimeException("[$source] ERROR: $message"))
    }

    fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            SoulLogger.error(RuntimeException("[$source] ERROR: $message", throwable))
        } else {
            SoulLogger.error(RuntimeException("[$source] ERROR: $message"))
        }
    }

    companion object {
        fun getLogger(source: String): DbScopeLogger = DbScopeLogger(source)
    }
}
