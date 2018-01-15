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

//    fun GetUpdated(curState: DepthBook, newState: DepthBook, updated: DepthBook, depthLimit: Int): DepthBook {
//        updated.clear()
//        newState.forEach { pair, p ->
//            p.forEach {
//                val type = it.key
//                val newDepthLimit = Math.min(it.value.size, depthLimit)
//                for (i in 0 until newDepthLimit) {
//                    if (null != curState[pair]?.get(type)?.get(i)) {
//                        if (null != newState[pair]?.get(type)?.get(i)) {
//                            val newDepth = newState[pair]?.get(type)?.get(i)
//                            val curDepth = curState[pair]?.get(type)?.get(i)
//                            if (curDepth?.rate != newDepth?.rate || curDepth?.amount != newDepth?.amount) {
//                                updated.getOrPut(pair) { mutableMapOf() }.getOrPut(type) { mutableMapOf() }.put(i, Depth(newDepth!!))
//                                curDepth?.replace(newDepth)
//                            }
//                        }  else {
//                            curState[pair]?.get(type)?.removeAt(i)
//                            newState[pair]?.get(type)?.forEach {
//                                updated.getOrPut(pair) { mutableMapOf() }.getOrPut(type) { mutableMapOf() }.put(i, Depth(it))
//                            }
//                        }
//                    } else {
//                        newState[pair]?.get(type)?.get(i)?.let {
//                            updated.getOrPut(pair) { LinkedHashMap() }.getOrPut(type) { mutableMapOf() }.put(i, Depth(it))
//                            curState[pair]?.get(type)?.set(i, Depth(it))
//                        }
//                    }
//                }
//            }
//        }
//        return updated
//    }