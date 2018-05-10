package stock

import RutEx
import data.DepthBook
import data.Order
import database.BookType
import database.PairInfo
import database.TransferStatus
import kotlinx.coroutines.experimental.NonCancellable
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.sync.withLock
import kotlinx.coroutines.experimental.withContext
import utils.getUpdate
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

data class Update(val pair: String, val type: BookType, val rate: BigDecimal, val amount: BigDecimal? = null)
data class Transfer(val address: Pair<String, String>, val amount: BigDecimal, val cur: String, val fromStock: String,
                    val toStock: String, var status: TransferStatus, var fee: BigDecimal? = null,
                    var withdraw_id: String = "", var tId: String = "", val id: Int = 0)
data class TransferUpdate(val id: Int, val status: TransferStatus)
data class ApiRequest(val headers: Map<String, String>, val postData: String, val postReq: Map<String, String>)

interface IStock {
    val state: State

    fun getDepth(updateTo: DepthBook? = null, pair: String? = null): DepthBook?
    fun getBalance(): Map<String, BigDecimal>?
    fun getOrderInfo(order: Order, updateTotal: Boolean = true)
    fun putOrders(orders: List<Order>)
    fun cancelOrders(orders: List<Order>)
    suspend fun start()
    suspend fun stop()
    fun deposit(lastId: Long, transfers: List<Transfer>): Pair<Long, List<TransferUpdate>>
    fun withdraw(transfer: Transfer): Pair<TransferStatus, String>

    fun debugWallet() = launch {
        while (isActive) {
            try {
                withContext(NonCancellable) {
                    getBalance()?.let { state.debugWallet.putAll(it) }
                }
            } catch (e: Exception) {
                state.logger.error(e.message, e)
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
                state.logger.error(e.message, e)
            }
            delay(1, TimeUnit.NANOSECONDS)
        }
    }

    fun active() = launch {
        while (isActive) {
            RutEx.stateLock.withLock { state.activeList.filter { it.order_id != 0L } }.forEach {
                withContext(NonCancellable) {
                    getOrderInfo(it)
                }
            }
            delay(1, TimeUnit.NANOSECONDS)
        }
    }

    fun history(delay: Long = 10) = launch {
        while (isActive) {
            state.db.getTransfer(state.name).let {
                withContext(NonCancellable) { updateTransfer(it) }
                delay(delay, TimeUnit.SECONDS)
            }
        }
    }

    fun updateTransfer(transfers: List<Transfer>) {
        deposit(state.lastHistoryId, transfers).let {
            if (it.first > state.lastHistoryId) {
                state.db.saveHistoryId(it.first, state.id)
                state.lastHistoryId = it.first
            }
            it.second.forEach { tu ->
                val ts = transfers.find { it.id == tu.id }!!
                if (tu.status != ts.status) {
                    if (ts.status == TransferStatus.SUCCESS) {
                        state.onWalletUpdate(plus = Pair(ts.cur, ts.amount))
                    }
                    state.db.saveTransfer(ts)
                }
            }
        }
    }

    fun info(func: () -> Map<String, PairInfo>?, delay: Long) = launch {
        val lastPair = state.db.getStockPairs(state.name)
        while (isActive) {
            withContext(NonCancellable) {
                func()?.let {
                    val newList = it.filter { it.value.minAmount.compareTo(lastPair[it.key]!!.minAmount) != 0 }
                    if (newList.isNotEmpty()) {
                        state.db.updateStockPairs(newList, state.name)
                        newList.forEach { lastPair[it.key]!!.minAmount = it.value.minAmount }
                        RutEx.stateLock.withLock { newList.forEach { state.pairs[it.key]!!.minAmount = it.value.minAmount } }
                    }
                }
            }
            delay(delay, TimeUnit.SECONDS)
        }
    }

    fun syncWallet() {
        //TODO: synchronize wallet and History() on start
        var walletSynchronized = false

        do {
            val beginWallet = getBalance()
            state.activeList.forEach { getOrderInfo(it, false) }
            state.db.getTransfer(state.name).let { updateTransfer(it) }
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