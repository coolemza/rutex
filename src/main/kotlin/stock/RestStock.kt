package stock

import com.github.salomonbrys.kodein.Kodein
import data.DepthBook
import data.Order
import database.KeyType
import database.OrderStatus
import database.StockKey
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import utils.getUpdate
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

abstract class RestStock(kodein: Kodein, name: String) : Stock(kodein, name) {
    private val tradeLock = Mutex()
    private val tradeKeys: List<StockKey> by lazy { getKeys(KeyType.TRADE).takeIf { it.isNotEmpty() }!! }

    abstract fun depth(updateTo: DepthBook?, pair: String?): DepthBook?

    fun active(time: Long = 1, unit: TimeUnit = TimeUnit.NANOSECONDS) = launch {
        while (isActive) {
            RutEx.stateLock.withLock { activeList.filter { it.stockOrderId != "0" } }.forEach { order ->
                withContext(NonCancellable) {
                    orderInfo(order)?.let { onActiveUpdate(it) }
                }
                delay(time, unit)
            }
        }
    }

    fun depthPolling(pair: String? = null) = launch {
        val stateNew = DepthBook()
        val stateCur = DepthBook()

        while (isActive) {
            try {
                stateNew.pairs.clear()
                while (stateCur.pairs.isEmpty()) {
                    depth(stateCur, pair)?.let { OnStateUpdate(fullState = stateNew) }
                }
                depth(stateNew, pair)?.also {
                    getUpdate(stateCur, it)?.let { OnStateUpdate(it) }?.also { stateCur.replace(stateNew) }
                } ?: stateCur.reset()
            } catch (e: Exception) {
                logger.error(e.message, e)
            }
            delay(1, TimeUnit.NANOSECONDS)
        }
    }

    private suspend fun getTradeKeys(orders: List<Order>) = tradeLock.withLock {
        tradeKeys.filter { !it.busy }.takeIf { it.size >= orders.size }?.let {
            orders.mapIndexed { i, order -> order to it[i] }.onEach { it.second.busy = true }
        } ?: orders.forEach {
            onActiveUpdate(OrderUpdate(it.id, it.stockOrderId, BigDecimal.ZERO, OrderStatus.FAILED))
        }.also { logger.error("not enough threads for Trading!!!") }.let { null }
    }

    private suspend fun releaseTradeKey(key: StockKey) = tradeLock.withLock { key.busy = false }

    suspend fun parallelOrders(orders: List<Order>, code: (Order, StockKey) -> OrderUpdate?): List<Job>? {
        return getTradeKeys(orders)?.map { (order, key) ->
            launch {
                val orderUpdate = code(order, key)
                releaseTradeKey(key)
                orderUpdate?.let { onActiveUpdate(it) } ?: logger.error("something goes wrong with $order")
            }
        }
    }
}