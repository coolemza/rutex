package stock

import data.DepthBook
import data.Order
import database.BookType
import database.OrderStatus
import database.TransferStatus
import kotlinx.coroutines.Job
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
    val depthBook: DepthBook
    val wallet: Map<String, BigDecimal>

    suspend fun cancelOrders(orders: List<Order>): List<Job>?
    suspend fun putOrders(orders: List<Order>): List<Job>?
    suspend fun start()
    suspend fun stop()
    fun withdraw(transfer: Transfer): Pair<TransferStatus, String>
}