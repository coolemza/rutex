package utils

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.math.BigDecimal

fun getRollingLogger(name: String, logLevel: Level): Logger {
    val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
    return ctx.getLogger(name).apply {
        isAdditive = false
        level = logLevel
        addAppender(
                RollingFileAppender<ILoggingEvent>().also {
                    it.context = ctx
                    it.file = "./logs/$name"
                    it.isAppend = true
                    it.rollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>()
                            .apply { fileNamePattern = "./logs/$name.%d{yyyy-MM-dd}"; context = ctx; maxHistory = 5; setParent(it); start() }
                    it.encoder = PatternLayoutEncoder().apply { context = ctx; pattern = "%date{HH:mm:ss.SSS} %-5p [%t%X{threadId}] %C{0}.%M %m%n"; start() }
                    it.start()
                }
        )
    }
}

inline fun <T> Iterable<T>.sumByDecimal(selector: (T) -> BigDecimal): BigDecimal {
    var sum = BigDecimal.ZERO
    for (element in this) {
        sum += selector(element)
    }
    return sum
}