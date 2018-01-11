package stock

import data.DepthBook
import data.Order
import db.*
import kotlinx.coroutines.experimental.NonCancellable
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.newSingleThreadContext
import java.math.BigDecimal
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.read
import kotlin.concurrent.write

enum class WithdrawStatus { SUCCESS, FAILED }

data class WithdrawResponse(val withdraw_id: Long, val status: WithdrawStatus)

interface IStock {
    val state: IState

    fun getDepth(url: Map<String, String>, pair: String, updateTo: DepthBook? = null): DepthBook?
    fun getBalance(): Map<String, BigDecimal>?
    fun getApiRequest(key: StockKey, urlParam: Map<String, String>, data: Any? = null): ApiRequest
    fun getOrderInfo(order: Order, updateTotal: Boolean = true)
    fun putOrder(orders: List<Order>)
    fun start()
    fun stop()
    fun updateHistory(fromId: Long): Long
    fun withdraw(address: Pair<String, String>, crossCur: String, amount: BigDecimal): WithdrawResponse

    fun debugWallet(debugWallet: ConcurrentMap<String, BigDecimal>) = async(newSingleThreadContext("stockWallet")) {
        while (isActive) {
            kotlinx.coroutines.experimental.run(NonCancellable) {
                getBalance()?.let { debugWallet.putAll(it) }
            }
            delay(10, TimeUnit.SECONDS)
        }
    }

    fun Active(activeList: MutableList<Order>) = async(newSingleThreadContext("Active")) {
        while (isActive) {
            RutEx.stateLock.read { activeList.filter { it.order_id != 0L } }.forEach {
                kotlinx.coroutines.experimental.run(NonCancellable) {
                    getOrderInfo(it)
                }
            }
        }
    }

    fun history(lastId: Long, delaySeconds: Long, stockId: Int) = async(newSingleThreadContext("History")) {
        var historyLastId = lastId
        while (isActive) {
            kotlinx.coroutines.experimental.run(NonCancellable) {
                updateHistory(historyLastId).takeIf { it > historyLastId }
                        ?.also { saveHistoryId(it, stockId) }?.also { historyLastId = it }
            }
            delay(delaySeconds, TimeUnit.SECONDS)
        }
    }

    fun info(func: () -> Map<String, PairInfo>?, delayMinutes: Long, stockName: String, pairs: Map< String, PairInfo>) = async(newSingleThreadContext("info")) {
        val lastPair = getPairs(stockName)
        while (isActive) {
            kotlinx.coroutines.experimental.run(NonCancellable) {
                func()?.let {
                    val newList = it.filter { it.value.minAmount.compareTo(lastPair[it.key]!!.minAmount) != 0 }
                    if (newList.isNotEmpty()) {
                        updatePairs(newList, stockName)
                        newList.forEach { lastPair[it.key]!!.minAmount = it.value.minAmount }
                        RutEx.stateLock.write { newList.forEach { pairs[it.key]!!.minAmount = it.value.minAmount } }
                    }
                }
            }
            delay(delayMinutes, TimeUnit.MINUTES)
        }
    }
}