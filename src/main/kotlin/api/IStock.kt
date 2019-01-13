package api

import data.ControlMsg
import data.DepthBook
import data.Order
import database.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import java.math.BigDecimal

data class Update(val pair: String, val type: BookType, val rate: BigDecimal, val amount: BigDecimal? = null)
data class Transfer(val address: Pair<String, String>, val amount: BigDecimal, val cur: String, val fromStock: String,
                    val toStock: String, var status: TransferStatus, var fee: BigDecimal? = null,
                    var withdraw_id: String = "", var tId: String = "", val id: Int = 0)

data class TransferUpdate(val id: Int, val status: TransferStatus)
data class OrderUpdate(val id: Long? = null, val orderId: String? = null, val amount: BigDecimal? = null, val status: OrderStatus? = null)
data class UpdateWallet(val plus: Pair<String, BigDecimal>? = null, val minus: Pair<String, BigDecimal>? = null)

interface IStock {
    val name: String
    val currencies: Map<String, StockCurrencyInfo>
    val pairs: Map<String, PairInfo>
    val depthBook: DepthBook
    val wallet: Map<String, BigDecimal>
    val activeList: MutableList<Order>

    val depthChannel: Channel<ControlMsg>

    val depthLimit: Int
    val tradeAttemptCount: Int

    suspend fun cancelOrders(orders: List<Order>)
    suspend fun putOrders(orders: List<Order>)
    suspend fun getActiveOrders(): List<Order>

    suspend fun start()
    suspend fun stop()

    suspend fun reconnectPair(pair: String)
    suspend fun withdraw(transfer: Transfer): Pair<TransferStatus, String>

    suspend fun updateCurrencyInfo(forceUpdate: Boolean = false)
    suspend fun updatePairInfo(forceUpdate: Boolean = false)
}