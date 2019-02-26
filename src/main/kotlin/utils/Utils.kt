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
import api.Update
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JSON
import kotlinx.serialization.parse
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

inline fun <T> Iterable<T>.sumByDecimal(selector: (T) -> BigDecimal): BigDecimal {
    var sum = BigDecimal.ZERO
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

fun local2joda(time: LocalDateTime): org.joda.time.LocalDateTime {
//    val time = java.time.LocalDateTime.now()

// Separate steps, showing intermediate types
    val java8ZonedDateTime = time.atZone(ZoneId.systemDefault())
    val java8Instant = java8ZonedDateTime.toInstant()
    val millis = java8Instant.toEpochMilli()
    val jodaLocalDateTime = org.joda.time.LocalDateTime(millis)

    return jodaLocalDateTime
}

fun getLastRates(state: Map<String, Map<String, Map<String, List<Map<String, String>>>>>) =
    mutableMapOf<String, MutableMap<String, MutableMap<String, BigDecimal>>>().also { rates ->
    state.forEach { stock, s ->
        s.forEach { pair, p ->
            p.forEach { type, t ->
                rates.getOrPut(pair) { mutableMapOf() }.getOrPut(stock) { mutableMapOf() }
                    .put(type, BigDecimal(t.first()["rate"]!!))
            }
        }
    }
}
