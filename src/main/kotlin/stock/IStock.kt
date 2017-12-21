package stock

import data.Order
import data.SocketState
import db.*
import kotlinx.coroutines.experimental.NonCancellable
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.newSingleThreadContext
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.concurrent.read
import kotlin.concurrent.write

enum class WithdrawStatus { SUCCESS, FAILED }

data class WithdrawResponse(val withdraw_id: Long, val status: WithdrawStatus)

interface IStock {
    val state: IState

    fun depth(url: Map<String, String>, updateTo: SocketState, pair: String): SocketState?
    fun getBalance(): Map<String, BigDecimal>?
    fun GetApiRequest(key: StockKey, urlParam: Map<String, String>, data: Any? = null): ApiRequest
    fun OrderInfo(order: Order, updateTotal: Boolean = true)
    fun putOrder(orders: List<Order>)
    fun start()
    fun stop()
    fun updateHistory(fromId: Long): Long
    fun withdraw(address: Pair<String, String>, crossCur: String, amount: BigDecimal): WithdrawResponse

    fun debugWallet(debugWallet: MutableMap<String, BigDecimal>) = async(newSingleThreadContext("stockWallet")) {
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
                    OrderInfo(it)
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