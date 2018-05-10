package stock

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import data.Depth
import data.DepthBook
import data.Order
import database.*
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.cancelAndJoin
import org.apache.commons.codec.binary.Hex
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.math.BigDecimal
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WEX(override val kodein: Kodein) : IStock, KodeinAware {
    override val state = State(this::class.simpleName!!, kodein)
    private val coroutines = mutableListOf<Job>()

    private var tm = TradeManager(state)

    private fun getUrl(cmd: String) = Pair("https://wex.nz/tapi/", cmd)

    private fun getApi3Url(cmd: String) = Pair("https://wex.nz/api/3/$cmd", cmd)

    private fun getDepthUrl() = "https://wex.nz/api/3/depth/${state.pairs.keys.joinToString("-")}?limit=${state.depthLimit}"

    private fun info(): Map<String, PairInfo>? {
        return getApi3Url("info").let {
            parseResponse(state.SendRequest(it.first))?.let {
                (it["pairs"] as Map<*, *>).filter { state.pairs.containsKey(it.key.toString()) }.map {
                    it.key.toString() to PairInfo(state.pairs[it.key]!!.pairId, state.pairs[it.key]!!.stockPairId,
                            BigDecimal((it.value as Map<*, *>)["min_amount"].toString()))
                }.toMap()
            }
        }
    }

    override fun deposit(lastId: Long, transfers: List<Transfer>): Pair<Long, List<TransferUpdate>> {
        var newLastId = lastId
        var stopIncrement = false
        val tu = mutableListOf<TransferUpdate>()
        val param = mapOf("order" to "DESC", "from_id" to lastId)

        getUrl("TransHistory").let {
            parseResponse(state.SendRequest(it.first, getApiRequest(state.getHistoryKey(), it.second, param)))?.also {
                //TODO: int or Long? or String?
                val res = (it["return"] as Map<*, *>).map { it.key as String to it.value }.toMap().toSortedMap()
                if (res.containsKey(lastId.toString())) {
                    res.remove(lastId.toString())
                    if (res.isNotEmpty()) {
                        res.map { it.key as String to it.value as Map<*, *> }.toMap().forEach {
                            if (it.value["type"] == 1L) {
                                val cur = (it.value["currency"] as String).toLowerCase()
                                val amount = BigDecimal(it.value["amount"].toString())
                                val status = it.value["status"].toString().toInt()
                                transfers.find { (it.amount - it.fee!!) == amount && it.cur == cur }?.let {
                                    TransferUpdate(it.id!!, when (status) {
                                        3 -> { stopIncrement = true; TransferStatus.WAITING }
                                        2 -> TransferStatus.SUCCESS
                                        0 -> TransferStatus.FAILED
                                        else -> TransferStatus.WAITING
                                    })
                                }?.let { tu.add(it) }
                            }
                            if (!stopIncrement) {
                                newLastId  = it.key.toLong()
                            }
                        }
                    }
                } else {
                    state.logger.error("history id not found!!!")
                }
            } ?: state.logger.error("updateHistory failed")
        }
        return Pair(newLastId, tu)
    }

    override fun getDepth(updateTo: DepthBook?, pair: String?): DepthBook? {
        val update = updateTo ?: DepthBook()
        parseResponse(state.SendRequest(getDepthUrl()))?.also {
            (it as Map<*, *>).forEach {
                val wexPair = it.key.toString()
                (it.value as Map<*, *>).forEach {
                    for (i in 0..(state.depthLimit - 1)) { //TODO: optimize (depthLimit - 1)
                        val value = ((it.value as List<*>)[i]) as List<*>
                        update.pairs.getOrPut(wexPair) { mutableMapOf() }.getOrPut(BookType.valueOf(it.key.toString())) { mutableListOf() }
                                .add(i, Depth(value[0].toString(), value[1].toString()))
                    }
                }
            }
            return update
        }
        return null
    }

    private fun getApiRequest(key: StockKey, cmd:  String, data: Any? = null): ApiRequest {
        val params = mutableMapOf("method" to cmd, "nonce" to "${++key.nonce}")
        data?.let { (it as Map<*, *>).forEach { params[it.key as String] = "${it.value}" } }

        //val body = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val postData = params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA512"))
        val sign = Hex.encodeHexString(mac.doFinal(postData.toByteArray(charset("UTF-8"))))

        return ApiRequest(mapOf("Key" to key.key, "Sign" to sign), postData, params)
    }

    override fun getOrderInfo(order: Order, updateTotal: Boolean) {
        getUrl("OrderInfo").let {
            parseResponse(state.SendRequest(it.first, getApiRequest(state.getActiveKey(), it.second, mapOf("order_id" to order.order_id))))?.also {
                //TODO: int or Long? or String?
                val res = (it["return"] as Map<*, *>).values.first() as Map<*, *>
                val partialAmount = BigDecimal(res["amount"].toString())
                val status = if (res["status"].toString() == "0") {
                    if (order.amount > partialAmount) OrderStatus.PARTIAL else OrderStatus.ACTIVE
                } else
                    OrderStatus.COMPLETED

                order.takeIf { it.status != status || it.remaining.compareTo(partialAmount) != 0 }
                        ?.let { state.onActive(it.id, it.order_id, it.remaining - partialAmount, status, updateTotal) }

            } ?: state.logger.error("OrderInfo failed: $order")
        }
    }

    private fun parseResponse(response: String?): JSONObject? {
        response?.let {
            val obj = JSONParser().parse(it) as JSONObject

            return if (obj.containsKey("success") && obj["success"] == 0L && obj["error"] != "no orders") {
                state.logger.error(obj["error"].toString())
                null
            } else {
                obj
            }
        }
        return null
    }

    override fun putOrders(orders: List<Order>) {
        tm.getKeys(orders).let {
            if (it != null) {
                it.forEach {
                    tm.tradePool.submit { trade(it.key, orders); tm.releaseKey(it.key) }
                }
            } else {
                orders.forEach { state.onActive(it.id, it.order_id, BigDecimal.ZERO, OrderStatus.FAILED) }
                state.logger.error("not enough threads for Trading!!!")
            }
        }
    }

    override fun cancelOrders(orders: List<Order>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private fun trade(key: StockKey, orderList: List<Order>) {
        val order = orderList.first()
        val params = mapOf("pair" to order.pair, "type" to order.type, "rate" to order.rate.toString(), "amount" to order.amount.toString())

        state.logger.info("send tp play ${params.entries.joinToString { "${it.key}:${it.value}" }}")
        getUrl("Trade").let {
            parseResponse(state.SendRequest(it.first, getApiRequest(key, it.second, params)))?.also {
                val ret = (it["return"] as Map<*, *>)

                state.logger.info(" thread id: ${Thread.currentThread().id} trade ok: received: ${ret["received"]} remains: ${ret["remains"]} order_id: ${ret["order_id"]}")

                val orderId = ret["order_id"].toString().toLong()
                val remaining = BigDecimal(ret["remains"].toString())

                val status = when (orderId) {
                    0L -> OrderStatus.COMPLETED
                    else -> if (order.amount > remaining) OrderStatus.PARTIAL else OrderStatus.ACTIVE
                }

                state.onActive(order.id, orderId, order.remaining - remaining, status)
                return
            }
        }

        state.onActive(order.id, 666, BigDecimal.ZERO, OrderStatus.FAILED)
    }

    override suspend fun start() {
        syncWallet()
        coroutines.addAll(listOf(active(), depth(), history(1), info(this@WEX::info, 5), debugWallet()))
        coroutines.forEach { it.join() }
    }

    override suspend fun stop() {
//        statePool.shutdown()
        state.logger.info("stopping")
        tm.shutdown()
        coroutines.forEach { it.cancelAndJoin() }
        state.shutdown()
        state.logger.info("stopped")
    }

    override fun getBalance(): Map<String, BigDecimal>? {
        return getUrl("getInfo").let {
            parseResponse(state.SendRequest(it.first, getApiRequest(state.getWalletKey(), it.second)))?.let {
                ((it["return"] as Map<*, *>)["funds"] as Map<*, *>)
                        .filter { state.currencies.containsKey(it.key.toString()) }
                        .map { it.key.toString() to BigDecimal(it.value.toString()) }.toMap()
            }
        }
    }

    override fun withdraw(transfer: Transfer): Pair<TransferStatus, String> {
        val data = mapOf("amount" to transfer.amount.toPlainString(), "coinName" to transfer.cur.toUpperCase(), "address" to transfer.address.first)

        return getUrl("WithdrawCoin").let {
            parseResponse(state.SendRequest(it.first, getApiRequest(state.getWithdrawKey(), it.second, data)))?.let {
                if (it["success"] == 1L) {
                    Pair(TransferStatus.PENDING, (it["return"] as Map<*, *>)["tId"].toString())
                } else {
                    Pair(TransferStatus.FAILED, "")
                }
            } ?: Pair(TransferStatus.FAILED, "")
        }
    }

    companion object {
        val pairs = listOf(
                RutData.P(C.bch, C.btc),
                RutData.P(C.bch, C.dsh),
                RutData.P(C.bch, C.eth),
                RutData.P(C.bch, C.eur),
                RutData.P(C.bch, C.ltc),
                RutData.P(C.bch, C.rur),
                RutData.P(C.bch, C.usd),
                RutData.P(C.bch, C.zec),

                RutData.P(C.btc, C.eur),
                RutData.P(C.btc, C.rur),
                RutData.P(C.btc, C.usd),

                RutData.P(C.dsh, C.btc),
                RutData.P(C.dsh, C.eth),
                RutData.P(C.dsh, C.eur),
                RutData.P(C.dsh, C.ltc),
                RutData.P(C.dsh, C.rur),
                RutData.P(C.dsh, C.usd),
                RutData.P(C.dsh, C.zec),

                RutData.P(C.eth, C.btc),
                RutData.P(C.eth, C.eur),
                RutData.P(C.eth, C.ltc),
                RutData.P(C.eth, C.rur),
                RutData.P(C.eth, C.usd),
                RutData.P(C.eth, C.zec),

                RutData.P(C.eur, C.rur),
                RutData.P(C.eur, C.usd),

                RutData.P(C.ltc, C.btc),
                RutData.P(C.ltc, C.eur),
                RutData.P(C.ltc, C.rur),
                RutData.P(C.ltc, C.usd),

                RutData.P(C.nmc, C.btc),
                RutData.P(C.nmc, C.usd),

                RutData.P(C.nvc, C.btc),
                RutData.P(C.nvc, C.usd),

                RutData.P(C.ppc, C.btc),
                RutData.P(C.ppc, C.usd),

                RutData.P(C.usd, C.rur),

                RutData.P(C.zec, C.btc),
                RutData.P(C.zec, C.ltc),
                RutData.P(C.zec, C.usd)
        )
    }
}
