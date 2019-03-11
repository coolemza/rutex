package api.connectors

import api.Update
import data.*
import kotlinx.coroutines.*
import mu.KLogger
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance
import utils.IHttp
import java.util.concurrent.TimeUnit

class RestBookConnector(
    val pairs: Set<String>? = null, val timeOut: Long = 1, val unit: TimeUnit = TimeUnit.NANOSECONDS,
    override val kodein: Kodein, val logger: KLogger, val block: suspend (DepthBook, String?) -> Boolean,
    val handler: suspend (BookMsg) -> Unit
) : IConnector, KodeinAware {
    val http: IHttp by instance()

    private val pairJobs = mutableMapOf<String, Job>()

    private var depthTimeOut: Long = 1

    override suspend fun start() {
        depthTimeOut = unit.toMillis(timeOut)

        if (pairs == null) {
            pairJobs["state"] = depthPolling(depthTimeOut)
        } else {
            pairs.forEach { pairJobs[it] = depthPolling(depthTimeOut, it) }
        }
    }

    override suspend fun reconnect() {
        stop()
        start()
    }

    override suspend fun stop() {
        pairJobs.onEach { it.value.cancel() }.forEach { it.value.join().run { logger.info("stopped ${it.key}") } }
    }

    private suspend fun stopPairs(pair: String) {
        pairJobs[pair]!!.cancelAndJoin().run { logger.info("stopped $pair") }
    }

    private fun depthPolling(timeOut: Long = 1, pair: String? = null) = GlobalScope.launch {
        val stateNew = DepthBook()
        val stateCur = DepthBook()
        val err = pair?.let { PairError(pair) } ?: BookError()

        while (isActive) {
            if (block(stateNew, pair)) {
                getUpdate(stateCur, stateNew)
                handler(InitPair(pair, stateCur))
                break
            }
        }

        while (isActive) {
            try {
                if (block(stateNew, pair)) {
                    getUpdate(stateCur, stateNew).takeIf { it.isNotEmpty() }?.let { handler(UpdateList(it)) }
                } else {
                    handler(err)
                }
            } catch (e: Exception) {
                logger.error(e.message, e)
            }

            delay(timeOut)
        }
    }

    private fun getUpdate(curState: DepthBook, newState: DepthBook): List<Update> {
        val updList = mutableListOf<Update>()
        newState.forEach { pair, p ->
            p.forEach { type, _ ->
                val newBook = newState[pair]!![type]!!
                val curBook = curState.getOrPut(pair) { DepthType() }.getOrPut(type) { DepthList() }
                val newRates = newBook.map { it.rate }
                val curRates = curBook.map { it.rate }

                val updateRates = newRates.intersect(curRates)

                updateRates.forEach { rate ->
                    newBook.find { it.rate.compareTo(rate) == 0 }?.let { new ->
                        curBook.find { it.rate.compareTo(rate) == 0 }?.takeIf { it.amount.compareTo(new.amount) != 0 }
                            ?.let {
                                updList.add(Update(pair, type, new.rate, new.amount))
                                it.amount = new.amount
                            }
                    }
                }

                if (updateRates.size != newRates.size) {
                    (newRates - updateRates).forEach { rate ->
                        newBook.find { it.rate.compareTo(rate) == 0 }?.let {
                            updList.add(Update(pair, type, it.rate, it.amount))
                        }
                        curBook.clear()
                        curBook.addAll(newBook)
                    }

                    (curRates - updateRates).forEach { rate ->
                        updList.add(Update(pair, type, rate))
                        curBook.removeIf { it.rate.compareTo(rate) == 0 }
                    }
                }
            }
        }
        return updList
    }
}