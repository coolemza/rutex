package stock

import data.DepthBook
import data.Order
import database.*
import kotlinx.coroutines.experimental.NonCancellable
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.newSingleThreadContext
import utils.getUpdate
import java.math.BigDecimal
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.read
import kotlin.concurrent.write

enum class WithdrawStatus { SUCCESS, FAILED }

data class Update(val pair:String, val type: BookType, val rate: BigDecimal, val amount: BigDecimal? = null)
data class ApiRequest(val headers: Map<String, String>, val postData: String, val postReq: Map<String, String>)

interface IStock {
    val state: State

    fun getDepth(updateTo: DepthBook? = null, pair: String? = null): DepthBook?
    fun getBalance(): Map<String, BigDecimal>?
    fun getOrderInfo(order: Order, updateTotal: Boolean = true)
    fun putOrders(orders: List<Order>)
    fun cancelOrders(orders: List<Order>)
    fun start()
    fun stop()
    fun updateHistory(fromId: Long): Long
    fun withdraw(address: Pair<String, String>, crossCur: String, amount: BigDecimal): Pair<Long, WithdrawStatus>

    fun debugWallet(debugWallet: ConcurrentMap<String, BigDecimal>) = async(newSingleThreadContext("stockWallet")) {
        while (isActive) {
            kotlinx.coroutines.experimental.run(NonCancellable) {
                getBalance()?.let { debugWallet.putAll(it) }
            }
            delay(10, TimeUnit.SECONDS)
        }
    }

    fun depth() = async(newSingleThreadContext("Depth")) {
        val stateNew = DepthBook()
        val stateCur = DepthBook()
        while (isActive) {
            stateNew.clear()
            getDepth(stateNew)?.let {
                getUpdate(stateCur, it, state.depthLimit)?.let {
                    stateCur.replace(stateNew)
                    state.OnStateUpdate(it)
                }
            }
            delay(100)
        }
    }

    fun active(activeList: MutableList<Order>) = async(newSingleThreadContext("Active")) {
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
                        ?.also { state.db.saveHistoryId(it, stockId) }?.also { historyLastId = it }
            }
            delay(delaySeconds, TimeUnit.SECONDS)
        }
    }

    fun info(func: () -> Map<String, PairInfo>?, delayMinutes: Long, stockName: String, pairs: Map< String, PairInfo>) = async(newSingleThreadContext("info")) {
        val lastPair = state.db.getStockPairs(stockName)
        while (isActive) {
            kotlinx.coroutines.experimental.run(NonCancellable) {
                func()?.let {
                    val newList = it.filter { it.value.minAmount.compareTo(lastPair[it.key]!!.minAmount) != 0 }
                    if (newList.isNotEmpty()) {
                        state.db.updateStockPairs(newList, stockName)
                        newList.forEach { lastPair[it.key]!!.minAmount = it.value.minAmount }
                        RutEx.stateLock.write { newList.forEach { pairs[it.key]!!.minAmount = it.value.minAmount } }
                    }
                }
            }
            delay(delayMinutes, TimeUnit.MINUTES)
        }
    }

    fun syncWallet() {
        //TODO: synchronize wallet and History() on start
        var walletSynchronized = false

        do {
            val beginWallet = getBalance()
            state.activeList.forEach { getOrderInfo(it, false) }
            state.lastHistoryId = updateHistory(state.lastHistoryId)
            val endWallet = getBalance()
            if (beginWallet != null && endWallet != null) {
                if (beginWallet.all { it.value == endWallet[it.key]!! }) {
                    val locked = state.getLocked()
                    val total = endWallet.map { it.key to it.value + locked.getOrDefault(it.key, BigDecimal.ZERO) }.toMap()
                    state.onWalletUpdate(update = total)
                    walletSynchronized = true
                }
            }
        } while (!walletSynchronized)
    }
}