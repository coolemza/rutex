package api

import data.*
import database.*
import kotlinx.coroutines.channels.Channel
import mu.KLogger
import utils.sumByDecimal
import java.math.BigDecimal

data class Update(val pair: String, val type: BookType, val rate: BigDecimal, val amount: BigDecimal? = null, val time: String = "")
data class Transfer(
    val address: Pair<String, String>, val amount: BigDecimal, val cur: String, val fromStock: String,
    val toStock: String, var status: TransferStatus, var fee: BigDecimal? = null,
    var withdraw_id: String = "", var tId: String = "", val id: Int = 0
)

data class TransferUpdate(val id: Int, val status: TransferStatus)
data class OrderUpdate(
    val id: Long? = null,
    val orderId: String? = null,
    val amount: BigDecimal? = null,
    val status: OrderStatus? = null
)

interface IStock {
    val name: String
    val id: Int

    val logger: KLogger

    val saveState: Boolean?

    val limit: CrossBalance
    val limitLess: CrossBalance

    val depthChannel: Channel<FullBookMsg>
    var depthBook: DepthBook
    val walletWithRatio: MutableMap<String, BigDecimal>
    val walletFull: MutableMap<String, BigDecimal>
    val walletAvailable: MutableMap<String, BigDecimal>
    val walletLocked: MutableMap<String, BigDecimal>
    val walletTotal: MutableMap<String, BigDecimal>
    val walletTotalPlayed: MutableMap<String, BigDecimal>

    val debugWallet: MutableMap<String, BigDecimal>

    val walletRatio: BigDecimal

    val currencies: Map<String, StockCurrencyInfo>
    val pairs: Map<String, PairInfo>
    val depthLimit: Int
    val activeList: MutableList<Order>
    val tradeAttemptCount: Int

    suspend fun putOrders(orders: List<Order>)
    suspend fun getActiveOrders(): List<Order>
    suspend fun cancelOrders(orders: List<Order>)

    suspend fun start()
    suspend fun stop()

    suspend fun reconnectPair(pair: String)
    suspend fun withdraw(transfer: Transfer): Pair<TransferStatus, String>

    suspend fun updateCurrencyInfo(forceUpdate: Boolean = false)
    suspend fun updatePairInfo(forceUpdate: Boolean = false)


    fun getLocked(orderList: List<Order>) = orderList.asSequence().groupBy { it.getLockCur() }
        .asSequence().associateBy({ it.key }) { l -> l.value.sumByDecimal { it.getLockAmount() } }

    fun getPlayedAmount(orderList: List<Order>) = orderList.asSequence().groupBy { it.getToCur() }
        .asSequence().associateBy({ it.key }) { l -> l.value.sumByDecimal { it.getPlayedAmount() } }

    fun updateActive(update: OrderUpdate, decLock: Boolean): Boolean
    fun updateState(update: ControlMsg): Boolean
}