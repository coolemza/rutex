package stock

import data.Order
import db.OrderStatus
import db.StockKey
import java.math.BigDecimal
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TradeManager(val state: IState) {
    var tradePool = Executors.newFixedThreadPool(state.getTradesKey().size)
    val tradeLock = ReentrantLock()

    data class TradeKey(val key: StockKey, var busy:Boolean = false)
    private val tList = listOf<TradeKey>()

    fun getKeys(orders: List<Order>): List<TradeKey>? {
        tradeLock.withLock {
            val availableList = tList.filter { !it.busy }
            if (availableList.size >= orders.size) {
                val keyList = availableList.subList(0, orders.size)
                keyList.forEach { it.busy = true }
                return availableList
            } else {
                orders.forEach { state.onActive(it.id, it.order_id, BigDecimal.ZERO, OrderStatus.FAILED) }
                state.log.error("not enough threads for Trading!!!")
            }
        }
        return null
    }

    fun releaseKey(key: StockKey) {
        tradeLock.withLock {
            tList.find { it.key == key }?.busy == false
        }
    }

    fun shutdown() {
        tradePool.shutdown()
    }
}