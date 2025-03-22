package com.k2fsa.sherpa.ncnn.utils

import java.util.logging.Level
import java.util.logging.Logger as JavaLogger

enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR, NONE
}

object Config {
    // 这里定义当前的日志级别，示例中设为 DEBUG
    val LOG_LEVEL = LogLevel.DEBUG
}

class Logger(private val tag: String) {
    private val currentLogLevel = Config.LOG_LEVEL
    private val logger: JavaLogger = JavaLogger.getLogger(tag)

    fun verbose(message: String) {
        if (shouldLog(LogLevel.VERBOSE)) {
            logger.log(Level.FINEST, message)
        }
    }

    fun debug(message: String) {
        if (shouldLog(LogLevel.DEBUG)) {
            logger.log(Level.FINE, message)
        }
    }

    fun info(message: String) {
        if (shouldLog(LogLevel.INFO)) {
            logger.log(Level.INFO, message)
        }
    }

    fun warn(message: String) {
        if (shouldLog(LogLevel.WARN)) {
            logger.log(Level.WARNING, message)
        }
    }

    fun error(message: String) {
        if (shouldLog(LogLevel.ERROR)) {
            logger.log(Level.SEVERE, message)
        }
    }

    fun error(message: String, throwable: Throwable) {
        if (shouldLog(LogLevel.ERROR)) {
            logger.log(Level.SEVERE, message, throwable)
        }
    }

    private fun shouldLog(level: LogLevel): Boolean {
        // 根据 LogLevel 的枚举顺序与当前日志级别来判断是否应记录日志，同时排除 NONE 级别
        return level.ordinal >= currentLogLevel.ordinal && currentLogLevel != LogLevel.NONE
    }
}
