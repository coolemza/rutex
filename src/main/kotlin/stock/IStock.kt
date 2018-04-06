package stock

import data.DepthBook
import data.Order
import database.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.sync.withLock
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
    suspend fun start()
    fun stop()
    fun updateHistory(fromId: Long): Long
    fun withdraw(address: Pair<String, String>, crossCur: String, amount: BigDecimal): Pair<Long, WithdrawStatus>

    fun debugWallet(debugWallet: ConcurrentMap<String, BigDecimal>) = launch {
        while (isActive) {
            try {
                withContext(NonCancellable) {
                    getBalance()?.let { debugWallet.putAll(it) }
                }
            } catch (e: Exception) {
                state.log.error(e.message, e)
            }
            delay(10, TimeUnit.SECONDS)
        }
    }

    fun depth(pair: String? = null) = launch {
        val stateNew = DepthBook()
        val stateCur = DepthBook()

        while (stateNew.pairs.isEmpty()) {
            getDepth(stateNew, pair)?.let {
                stateCur.replace(stateNew)
                state.OnStateUpdate(fullState = stateNew)
            }
        }

        while (isActive) {
            try {
                stateNew.pairs.clear()  //TODO: reset socket state on error
                getDepth(stateNew, pair)?.let {
                    getUpdate(stateCur, it)?.let {
                        state.OnStateUpdate(it)
                        stateCur.replace(stateNew)
                    }
                }
            } catch (e: Exception) {
                state.log.error(e.message, e)
            }
            delay(1, TimeUnit.NANOSECONDS)
        }
    }

    fun active(activeList: MutableList<Order>) = launch {
        while (isActive) {
            RutEx.stateLock.withLock { activeList.filter { it.order_id != 0L } }.forEach {
                withContext(NonCancellable) {
                    getOrderInfo(it)
                }
            }
        }
    }

    fun history(lastId: Long, delaySeconds: Long, stockId: Int) = launch {
        var historyLastId = lastId
        while (isActive) {
            withContext(NonCancellable) {
                updateHistory(historyLastId).takeIf { it > historyLastId }
                        ?.also { state.db.saveHistoryId(it, stockId) }?.also { historyLastId = it }
            }
            delay(delaySeconds, TimeUnit.SECONDS)
        }
    }

    fun info(func: () -> Map<String, PairInfo>?, delayMinutes: Long, stockName: String, pairs: Map< String, PairInfo>) = launch {
        val lastPair = state.db.getStockPairs(stockName)
        while (isActive) {
            withContext(NonCancellable) {
                func()?.let {
                    val newList = it.filter { it.value.minAmount.compareTo(lastPair[it.key]!!.minAmount) != 0 }
                    if (newList.isNotEmpty()) {
                        state.db.updateStockPairs(newList, stockName)
                        newList.forEach { lastPair[it.key]!!.minAmount = it.value.minAmount }
                        RutEx.stateLock.withLock { newList.forEach { pairs[it.key]!!.minAmount = it.value.minAmount } }
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