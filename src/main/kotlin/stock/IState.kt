package stock

import data.DepthBook
import data.Order
import db.OrderStatus
import db.PairInfo
import db.StockKey
import kotlinx.coroutines.experimental.Deferred
import org.slf4j.Logger
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentMap

enum class Book { asks, bids }

//data class StockKey(val key: String, val secret: String, var nonce: Long, val type: String)
//data class PairInfo(val pairId: Int, val stockPairId: Int, var minAmount: BigDecimal)
data class Update(val pair:String, val type: Book, val rate: BigDecimal, val amount: BigDecimal?)
data class ApiRequest(val headers: Map<String, String>, val postData: String, val postReq: Map<String, String>)

interface IState {
    val name: String
    var id: Int

    val log: Logger

    val socketState: DepthBook
    var updated: DepthBook
    val wallet: Map<String, BigDecimal>

    var stateTime: LocalDateTime

    val debugWallet: ConcurrentMap<String, BigDecimal>
    var activeList: MutableList<Order>

    var keys: MutableList<StockKey>
    var pairs: Map<String, PairInfo>
    var currencies: Map<String, Pair<String,String>>

    val coroutines: MutableList<Deferred<Unit>>
    var lastHistoryId: Long

    var depthLimit: Int

    fun getWalletKey() = keys.find { it.type == "WALLET" }!!
    fun getActiveKey() = keys.find { it.type == "ACTIVE" }!!
    fun getWithdrawKey() = keys.find { it.type == "WITHDRAW" }!!
    fun getHistoryKey() = keys.find { it.type == "HISTORY" }!!
    fun getTradesKey() = keys.filter { it.type == "HISTORY" }

    fun getLocked(orderList: MutableList<Order> = activeList): Map<String, BigDecimal>
    fun OnStateUpdate(state: DepthBook?, update: Update?): Boolean
    fun onWalletUpdate(update: Map<String, BigDecimal>? = null, plus: Pair<String, BigDecimal>? = null, minus: Pair<String, BigDecimal>? = null)
    fun onActive(deal_id: Long?, order_id: Long, amount: BigDecimal? = null, status: OrderStatus? = null, updateTotal: Boolean = true)
//    fun UpdateProgress(o: Operation)

    fun SendRequest(urlParam: Map<String, String>, ap: ApiRequest? = null): String?
    fun shutdown()
}