package stock

import RutEx
import data.DepthBook
import data.Order
import database.BookType
import database.PairInfo
import database.StockKey
import database.TransferStatus
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.sync.withLock
import org.json.simple.parser.JSONParser
import utils.getUpdate
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

data class Update(val pair: String, val type: BookType, val rate: BigDecimal, val amount: BigDecimal? = null)
data class Transfer(val address: Pair<String, String>, val amount: BigDecimal, val cur: String, val fromStock: String,
                    val toStock: String, var status: TransferStatus, var fee: BigDecimal? = null,
                    var withdraw_id: String = "", var tId: String = "", val id: Int = 0)
data class TransferUpdate(val id: Int, val status: TransferStatus)

interface IStock {
    val state: State

    fun balance(): Map<String, BigDecimal>?
    suspend fun cancelOrders(orders: List<Order>): List<Job>?
    fun deposit(lastId: Long, transfers: List<Transfer>): Pair<Long, List<TransferUpdate>>
    fun depth(updateTo: DepthBook? = null, pair: String? = null): DepthBook?
    fun handleError(res: Any): Any?
    fun orderInfo(order: Order, updateTotal: Boolean = true)
    suspend fun putOrders(orders: List<Order>): List<Job>?
    suspend fun start()
    suspend fun stop()
    fun withdraw(transfer: Transfer): Pair<TransferStatus, String>

    fun active() = launch {
        while (isActive) {
            RutEx.stateLock.withLock { state.activeList.filter { it.order_id != 0L } }.forEach {
                withContext(NonCancellable) {
                    orderInfo(it)
                }
            }
            delay(1, TimeUnit.NANOSECONDS)
        }
    }

    fun debugWallet() = launch {
        while (isActive) {
            try {
                withContext(NonCancellable) {
                    balance()?.let { state.debugWallet.putAll(it) }
                }
            } catch (e: Exception) {
                state.logger.error(e.message, e)
            }
            delay(10, TimeUnit.SECONDS)
        }
    }

    fun depthPolling(pair: String? = null) = launch {
        val stateNew = DepthBook()
        val stateCur = DepthBook()

        while (isActive) {
            try {
                stateNew.pairs.clear()
                while (stateCur.pairs.isEmpty()) {
                    depth(stateCur, pair)?.let { state.OnStateUpdate(fullState = stateNew) }
                }
                depth(stateNew, pair)?.also {
                    getUpdate(stateCur, it)?.let { state.OnStateUpdate(it) }?.also { stateCur.replace(stateNew) }
                } ?: stateCur.reset()
            } catch (e: Exception) {
                state.logger.error(e.message, e)
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

    suspend fun parallelOrders(orders: List<Order>, code: (Order, StockKey) -> Unit): List<Job>? {
        return state.getTradeKeys(orders)?.map { (order, key) ->
            launch {
                code(order, key)
                state.releaseTradeKey(key)
            }
        }
    }

    fun parseResponse(response: String?) = try {
        response?.let {
            state.logger.trace(response)
            handleError(JSONParser().parse(it))
        }
    } catch (e: Exception) {
        run { state.logger.error(e.message, e) }.run { null }
    }

    fun syncWallet() {
        //TODO: synchronize wallet and History() on start
        var walletSynchronized = false

        do {
            val beginWallet = balance()
            state.activeList.forEach { orderInfo(it, false) }
            state.db.getTransfer(state.name).let { updateTransfer(it) }
            val endWallet = balance()
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
}