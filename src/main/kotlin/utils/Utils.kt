package utils

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import data.DepthBook
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stock.Update
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

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


fun getUpdate(curState: DepthBook, newState: DepthBook, depthLimit: Int): List<Update>? {
    val updList = mutableListOf<Update>()
    newState.forEach { pair, p ->
        p.forEach {
            val type = it.key
            val newDepthLimit = Math.min(it.value.size, depthLimit)
            for (i in 0 until newDepthLimit) {
                val new = newState[pair]?.get(type)?.get(i)
                val cur = curState[pair]?.get(type)?.get(i)

                if (cur == null) {
                    if (new != null) {
                        updList.add(Update(pair, type, new.rate, new.amount))
                    }
                } else {
                    if (new == null) {
                        updList.add(Update(pair, type, cur.rate))
                    } else {
                        if (cur.rate == new.rate) {
                            if (cur.amount != new.amount) {
                                updList.add(Update(pair, type, new.rate, new.amount))
                            }
                        } else {
                            updList.add(Update(pair, type, new.rate))
                            updList.add(Update(pair, type, new.rate, new.amount))
                        }
                    }
                }
            }
        }
    }
    return updList.takeIf { it.isNotEmpty() }?.let { updList }
}

fun local2joda(time: LocalDateTime): org.joda.time.LocalDateTime {
//    val time = java.time.LocalDateTime.now()

// Separate steps, showing intermediate types
    val java8ZonedDateTime = time.atZone(ZoneId.systemDefault())
    val java8Instant = java8ZonedDateTime.toInstant()
    val millis = java8Instant.toEpochMilli()
    val jodaLocalDateTime = org.joda.time.LocalDateTime(millis);

    return jodaLocalDateTime
}