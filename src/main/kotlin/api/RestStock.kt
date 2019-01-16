package api

import data.*
import database.KeyType
import database.OrderStatus
import database.StockKey
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.Kodein
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

abstract class RestStock(kodein: Kodein, name: String) : Stock(kodein, name) {
    private val tradeLock = Mutex()
    private val tradeKeys: List<StockKey>? by lazy { getKeys(KeyType.TRADE) }

    abstract suspend fun depth(updateTo: DepthBook?, pair: String?): Boolean

    private val pairJobs = mutableMapOf<String, Job>()

    private var depositTime = LocalDateTime.now()

    private var depthTimeOut: Long = 1

    suspend fun startDepth(pairs: Set<String>? = null, timeOut: Long = 1, unit: TimeUnit = TimeUnit.NANOSECONDS) {
        depthTimeOut = unit.toMillis(timeOut)
        syncWallet()
        coroutines.add(active())
        coroutines.add(infoPolling {
            if (ChronoUnit.MINUTES.between(depositTime, LocalDateTime.now()) > 10) {
                updateDeposit()
                depositTime = LocalDateTime.now()
            }
        })

        if (pairs == null) {
            pairJobs["state"] = depthPolling(depthTimeOut)
        } else {
            pairs.forEach { pairJobs[it] = depthPolling(depthTimeOut, it) }
        }
    }

    private suspend fun stopPairs() {
        pairJobs.onEach { it.value.cancel() }.forEach { it.value.join().run { logger.info("stopped ${it.key}") } }
    }

    private suspend fun stopPair(pair: String) {
        pairJobs[pair]!!.cancelAndJoin().run { logger.info("stopped $pair") }
    }

    override suspend fun reconnectPair(pair: String) {
        logger.info("reconnecting")
        if (pairJobs.size > 1) {
            stopPair(pair)
            pairJobs[pair] = depthPolling(depthTimeOut, pair)
        } else {
            stopPairs()
            pairJobs["state"] = depthPolling(depthTimeOut)
        }
        start()
        logger.info("restarted")
    }

    fun active(time: Long = 5, unit: TimeUnit = TimeUnit.SECONDS) = GlobalScope.launch(handler) {
        while (isActive) {
            getActiveOrders().filter { it.stockOrderId != "0" }.forEach { order ->
                withContext(NonCancellable) {
                    orderInfo(order)?.let {
                        if (order.status != it.status || it.amount?.let { it > BigDecimal.ZERO } == true) {
                            updateActive(it, false)
                        }
                    }
                }
                delay(unit.toMillis(time))
            }
            delay(unit.toMillis(time))
        }
    }

    private fun depthPolling(timeOut: Long = 1, pair: String? = null) = GlobalScope.launch(handler) {
        val stateNew = DepthBook()
        val stateCur = DepthBook()
        val err = pair?.let { PairError(pair) } ?: BookError()

        while (isActive) {
            if (depth(stateNew, pair)) {
                getUpdate(stateCur, stateNew)
                updateActor.send(InitPair(pair, stateCur))
                break
            }
        }

        while (isActive) {
            try {
                if (depth(stateNew, pair)) {
                    getUpdate(stateCur, stateNew).takeIf { it.isNotEmpty() }?.let { upd ->
                        UpdateList(upd).let { updateActor.send(it) } }
                } else {
                    updateActor.send(err)
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

    private suspend fun getTradeKeys(orders: List<Order>) = tradeLock.withLock { //FIXME: remove lock
        tradeKeys?.filter { !it.busy }?.takeIf { it.size >= orders.size }?.let { key ->
            orders.mapIndexed { i, order -> order to key[i] }.onEach { it.second.busy = true }
        } ?: orders.forEach {
            updateActive(OrderUpdate(it.id, it.stockOrderId, BigDecimal.ZERO, OrderStatus.FAILED), true)
        }.also { logger.error("not enough keys for Trading!!!") }.let { null }
    }

    private suspend fun releaseTradeKey(key: StockKey) = tradeLock.withLock { key.busy = false }

    suspend fun parallelOrders(orders: List<Order>, code: suspend (Order, StockKey) -> OrderUpdate) {
        getTradeKeys(orders)?.map { (order, key) -> GlobalScope.async { code(order, key).also { releaseTradeKey(key) } } }
            ?.map { it.await() }?.forEach {
                updateActive(it, true)
            }
    }

    override suspend fun stop() = stopPairs().run { shutdown() }
}

