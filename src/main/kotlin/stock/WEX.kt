package stock

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import data.Depth
import data.DepthBook
import data.Order
import database.*
import kotlinx.coroutines.experimental.Deferred
import org.apache.commons.codec.binary.Hex
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.math.BigDecimal
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WEX(override val kodein: Kodein) : IStock, KodeinAware {
    override val state = State(this::class.simpleName!!, kodein)
    private val coroutines = mutableListOf<Deferred<Unit>>()

    private val statePool = Executors.newScheduledThreadPool(1)
    private var tm = TradeManager(state)
//    private val util = StockUtil(this, state, state.log)

    fun getUrl(cmd: String) = mapOf("https://wex.nz/tapi/" to cmd)

    fun getApi3Url(cmd: String) = mapOf("https://wex.nz/api/3/$cmd" to cmd)

    fun getDepthUrl() = "https://wex.nz/api/3/depth/${state.pairs.keys.joinToString("-")}?limit=${state.depthLimit}"

    fun info(): Map<String, PairInfo>? {
        return getApi3Url("info").let {
            ParseResponse(state.SendRequest(it.keys.first()))?.let {
                (it["pairs"] as Map<*, *>).filter { state.pairs.containsKey(it.key.toString()) }.map {
                    it.key.toString() to PairInfo(state.pairs[it.key]!!.pairId, state.pairs[it.key]!!.stockPairId,
                            BigDecimal((it.value as Map<*, *>)["min_amount"].toString()))
                }.toMap()
            }
        }
    }

    override fun updateHistory(fromId: Long): Long {
        var lastId = fromId

        getUrl("TransHistory").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getHistoryKey(), it, mapOf("order" to "DESC", "from_id" to fromId))))?.also {
                //TODO: int or Long? or String?
                val res = (it["return"] as Map<String, *>).toSortedMap()
                if (res.containsKey(fromId.toString())) {
                    res.remove(fromId.toString())
                    if (res.isNotEmpty()) {
                        state.log.info("new history id: $fromId")
                        val cc = res as Map<String, Map<String, *>>
                        val total = mutableMapOf<String, BigDecimal>()
                        cc.filterValues { it["type"] == 1L }.forEach {
                            val cur = (it.value["currency"] as String).toLowerCase()
                            val amount = BigDecimal(it.value["amount"].toString())

                            state.log.info("found incoming transfer ${amount} $cur, status: ${it.value["status"]}")

                            total.run { put(cur, amount + getOrDefault(cur, BigDecimal.ZERO)) }
                        }
                        total.takeIf { it.isNotEmpty() }?.forEach { state.onWalletUpdate(plus = Pair(it.key, it.value)) }
                        lastId = res.lastKey().toLong()
                    }
                } else {
                    state.log.error("history id not found!!!")
                }
            } ?: state.log.error("updateHistory failed")
        }
        return lastId
    }

    override fun getDepth(updateTo: DepthBook?, pair: String?): DepthBook? {
        val update = if (updateTo != null) updateTo else DepthBook()
        ParseResponse(state.SendRequest(getDepthUrl()))?.also {
            (it as Map<*, *>).forEach {
                val pair_ = it.key.toString()
                (it.value as Map<*, *>).forEach {
                    for (i in 0..(state.depthLimit - 1)) { //TODO: optimize (depthLimit - 1)
                        val value = ((it.value as List<*>)[i]) as List<*>
                        update.getOrPut(pair_) { mutableMapOf() }.getOrPut(BookType.valueOf(it.key.toString())) { mutableListOf() }
                                .add(i, Depth(value[0].toString(), value[1].toString()))
                    }
                }
            }
            return update
        }
        return null
    }

    fun getApiRequest(key: StockKey, urlParam: Map<String, String>, data: Any? = null): ApiRequest {
        val params = mutableMapOf("method" to urlParam.entries.first().value, "nonce" to "${++key.nonce}")
        data?.let { (it as Map<*, *>).forEach { params.put(it.key as String, "${it.value}") } }

        //val body = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val postData = params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA512"))
        val sign = Hex.encodeHexString(mac.doFinal(postData.toByteArray(charset("UTF-8"))))

        return ApiRequest(mapOf("Key" to key.key, "Sign" to sign), postData, params)
    }

    override fun getOrderInfo(order: Order, updateTotal: Boolean) {
        getUrl("OrderInfo").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getActiveKey(), it, mapOf("order_id" to order.order_id))))?.also {
                //TODO: int or Long? or String?
                val res = (it["return"] as Map<*, *>).values.first() as Map<*, *>
                val partialAmount = BigDecimal(res["amount"].toString())
                val status = if (res["status"].toString() == "0") {
                    if (order.amount > partialAmount) OrderStatus.PARTIAL else OrderStatus.ACTIVE
                } else
                    OrderStatus.COMPLETED

                order.takeIf { it.status != status || it.remaining.compareTo(partialAmount) != 0 }
                        ?.let { state.onActive(it.id, it.order_id, it.remaining - partialAmount, status, updateTotal) }

            } ?: state.log.error("OrderInfo failed: $order")
        }
    }

    fun ParseResponse(response: String?): JSONObject? {
        response?.let {
            val obj = JSONParser().parse(it) as JSONObject

            if (obj.containsKey("success") && obj["success"] == 0L && obj["error"] != "no orders") {
                state.log.error(obj["error"].toString())
                return null
            } else {
                return obj
            }
        }
        return null
    }

    override fun putOrders(orders: List<Order>) {
        tm.getKeys(orders).let {
            if (it != null) {
                it.forEach {
                    tm.tradePool.submit { Trade(it.key, orders); tm.releaseKey(it.key) }
                }
            } else {
                orders.forEach { state.onActive(it.id, it.order_id, BigDecimal.ZERO, OrderStatus.FAILED) }
                state.log.error("not enough threads for Trading!!!")
            }
        }
    }

    override fun cancelOrders(orders: List<Order>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun Trade(key: StockKey, orderList: List<Order>) {
        val order = orderList.first()
        val params = mapOf("pair" to order.pair, "type" to order.type, "rate" to order.rate.toString(), "amount" to order.amount.toString())

        state.log.info("send tp play ${params.entries.joinToString { "${it.key}:${it.value}" }}")
        getUrl("Trade").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(key, it, params)))?.also {
                val ret = (it["return"] as Map<*, *>)

                state.log.info(" thread id: ${Thread.currentThread().id} trade ok: received: ${ret["received"]} remains: ${ret["remains"]} order_id: ${ret["order_id"]}")

                val order_id = ret["order_id"].toString().toLong()
                val remaining = BigDecimal(ret["remains"].toString())

                val status = when (order_id) {
                    0L -> OrderStatus.COMPLETED
                    else -> if (order.amount > remaining) OrderStatus.PARTIAL else OrderStatus.ACTIVE
                }

                state.onActive(order.id, order_id, order.remaining - remaining, status)
                return
            }
        }

        state.onActive(order.id, 666, BigDecimal.ZERO, OrderStatus.FAILED)
    }

    override fun start() {
        syncWallet()
        coroutines.addAll(listOf(Active(state.activeList), depth(), history(state.lastHistoryId, 2, 1),
                info(this::info, 5, state.name, state.pairs), debugWallet(state.debugWallet)))
    }

    override fun stop() {
        statePool.shutdown()
        tm.shutdown()
        state.shutdown()
    }

    override fun getBalance(): Map<String, BigDecimal>? {
        return getUrl("getInfo").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWalletKey(), it)))?.let {
                ((it["return"] as Map<*, *>)["funds"] as Map<*, *>)
                        .filter { state.currencies.containsKey(it.key.toString()) }
                        .map { it.key.toString() to BigDecimal(it.value.toString()) }.toMap()
            }
        }
    }

    override fun withdraw(address: Pair<String, String>, crossCur: String, amount: BigDecimal): Pair<Long, WithdrawStatus> {
        val data = mapOf("amount" to amount.toPlainString(), "coinName" to crossCur.toUpperCase(), "address" to address.first)

        return getUrl("WithdrawCoin").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWithdrawKey(), it, data)))?.let {
                val res = it["return"] as Map<*, *>
                if (it["success"] == 1L) {
                    return Pair(res["tId"] as Long, WithdrawStatus.SUCCESS)
                } else {
                    return Pair(0L, WithdrawStatus.FAILED)
                }
            } ?: return Pair(0L, WithdrawStatus.FAILED)
        }
    }
}
