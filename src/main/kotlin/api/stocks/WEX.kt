package api.stocks

import api.OrderUpdate
import api.RestStock
import api.Transfer
import api.TransferUpdate
import data.*
import database.*
import database.RutData.P
import org.apache.commons.codec.binary.Hex
import org.jsoup.Jsoup
import org.kodein.di.Kodein
import java.math.BigDecimal
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WEX(kodein: Kodein): RestStock(kodein, name) {
    private val site = "https://wex.link"
    private val feeUrl = "$site/fees"

    private fun publicApi(param: String) = "$site/api/3/$param"
    private val infoUrl = publicApi("info")
    private val depthUrl = publicApi("depth/${pairs.keys.joinToString("-")}?limit=$depthLimit")

    private val privateApi = "$site/tapi/"

    override suspend fun apiRequest(cmd: String, key: StockKey, data: Map<String, Any>?, timeOut: Long): Any? {
        logger.trace("in key - $key")
        val params = mutableMapOf<String, Any>("method" to cmd, "nonce" to "${++key.nonce}").apply { data?.let { putAll(it) } }
//        data?.let { (it as Map<*, *>).forEach { params[it.key as String] = "${it.value}" } }

        val postData = params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value.toString(), "UTF-8")}" }

        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA512"))
        val sign = Hex.encodeHexString(mac.doFinal(postData.toByteArray(charset("UTF-8"))))

        logger.trace("out key - $key")

        return parseJsonResponse(http.post(logger, privateApi, mapOf("Key" to key.key, "Sign" to sign), postData))
    }

    override suspend fun balance(key: StockKey): Map<String, BigDecimal>? = apiRequest("getInfo", key)?.let { res ->
        (((res as Map<*, *>)["return"] as Map<*, *>)["funds"] as Map<*, *>)
            .filter { currencies.containsKey(it.key.toString()) }
            .map { it.key.toString() to BigDecimal(it.value.toString()) }.toMap()
    }

    override suspend fun cancelOrders(orders: List<Order>) = parallelOrders(orders) { order, key ->
        apiRequest("CancelOrder", key, mapOf("order_id" to order.stockOrderId))?.let {
            logger.info("order_id: ${((it as Map<*, *>)["return"] as Map<*, *>)["order_id"]} canceled")
//            state.onActive(order.id, order.order_id, status = OrderStatus.CANCELED)
            OrderUpdate(order.id, order.stockOrderId, order.amount, OrderStatus.CANCELED)
        } ?: logger.error("Order cancelling failed: $order").run { OrderUpdate(order.id, order.stockOrderId, order.amount, OrderStatus.CANCEL_FAILED) }
    }

    override suspend fun deposit(lastId: Long, transfers: List<Transfer>, key: StockKey): Pair<Long, List<TransferUpdate>> {
        var newLastId = lastId
        var stopIncrement = false
        val tu = mutableListOf<TransferUpdate>()
        val param = mapOf("order" to "DESC", "from_id" to lastId.toString())

        apiRequest("TransHistory", key, param)?.also { res ->
            //TODO: int or Long? or String?
            val list = ((res as Map<*, *>)["return"] as Map<*, *>).map { it.key as String to it.value }.toMap().toSortedMap()
            if (list.containsKey(lastId.toString())) {
                list.remove(lastId.toString())
                if (list.isNotEmpty()) {
                    list.map { it.key as String to it.value as Map<*, *> }.toMap().forEach { item ->
                        if (item.value["type"] == 1L) {
                            val cur = (item.value["currency"] as String).toLowerCase()
                            val amount = BigDecimal(item.value["amount"].toString())
                            val status = item.value["status"].toString().toInt()
                            transfers.find { (it.amount - it.fee!!) == amount && it.cur == cur }?.let {
                                TransferUpdate(it.id, when (status) {
                                    3 -> { stopIncrement = true; TransferStatus.WAITING }
                                    2 -> TransferStatus.SUCCESS
                                    0 -> TransferStatus.FAILED
                                    else -> TransferStatus.WAITING
                                })
                            }?.let { tu.add(it) }
                        }
                        if (!stopIncrement) {
                            newLastId = item.key.toLong()
                        }
                    }
                }
            } else {
                logger.error("history id not found!!!")
            }
        } ?: logger.error("updateHistory failed")

        return Pair(newLastId, tu)
    }

    override suspend fun depth(updateTo: DepthBook?, pair: String?): Boolean {
        val update = updateTo ?: DepthBook()
        parseJsonResponse(http.post(logger, depthUrl))?.let { res ->
            (res as Map<*, *>).forEach { (wexPair, p) ->
                (p as Map<*, *>).forEach {
                    update.replaceFromList(wexPair.toString(), it.key.toString(), it.value as List<*>, depthLimit)
                }
            }
            return true
        }
        return false
    }

    override fun handleError(res: Any) = (res as Map<*, *>)
        .takeIf { !it.contains("success") || it["success"] == 1L || it["error"] == "no orders" }
        ?: throw Exception(res["error"].toString())

    override suspend fun currencyInfo(key: StockKey): Map<String, CrossFee>? {
        val html = Jsoup.connect(feeUrl).userAgent(userAgent).get()
        val list = html.select("table").select("tr").drop(4).dropLast(6).map { it.select("td")[0].text() to it.select("td")[2].text() }
        return list.map { rutSymbol(it.first) to CrossFee(withdrawFee = Fee(min = BigDecimal(it.second.split(" ")[0]))) }.toMap()
    }

    override suspend fun pairInfo(key: StockKey): Map<String, TradeFee>? {
        return parseJsonResponse(http.post(logger, infoUrl))?.let { res ->
            ((res as Map<*, *>)["pairs"] as Map<*, *>).filter { pairs.containsKey(it.key.toString()) }.map {
                it.key.toString() to TradeFee(
                    minAmount = BigDecimal((it.value as Map<*, *>)["min_amount"].toString()),
                    makerFee = BigDecimal((it.value as Map<*, *>)["fee"].toString()),
                    takerFee = BigDecimal((it.value as Map<*, *>)["fee"].toString()))
            }.toMap()
        }
    }

    override suspend fun orderInfo(order: Order, updateTotal: Boolean): OrderUpdate? {
        return apiRequest("OrderInfo", activeKey, mapOf("order_id" to order.stockOrderId))?.let {
            //TODO: int or Long? or String?
            val res = ((it as Map<*, *>)["return"] as Map<*, *>).values.first() as Map<*, *>
            val partialAmount = BigDecimal(res["amount"].toString())
            val status = if (res["status"].toString() == "0") {
                if (order.amount > partialAmount) OrderStatus.PARTIAL else OrderStatus.ACTIVE
            } else
                OrderStatus.COMPLETED

//            order.takeIf { it.status != status || it.remaining.compareTo(partialAmount) != 0 }
//                    ?.let { state.onActive(it.id, it.order_id, it.remaining - partialAmount, status, updateTotal) }
            OrderUpdate(order.id, order.stockOrderId, order.remaining - partialAmount, status)
        } ?: logger.error("OrderInfo failed: $order").let { null }
    }

    override suspend fun putOrders(orders: List<Order>) = parallelOrders(orders) { order, key ->
        val params = mapOf("pair" to order.pair, "type" to order.type.name, "rate" to order.rate.toString(),
            "amount" to order.amount.toString())
        logger.info("send to play ${params.entries.joinToString { "${it.key}:${it.value}" }}")

        apiRequest("Trade", key, params)?.let {
            val ret = ((it as Map<*, *>)["return"] as Map<*, *>)

            logger.info("thread id: ${Thread.currentThread().id} trade ok: received: ${ret["received"]} " +
                    "remains: ${ret["remains"]} order_id: ${ret["order_id"]}")

            val orderId = ret["order_id"].toString()
            val remaining = BigDecimal(ret["remains"].toString())

            val status = when (orderId) {
                "0" -> OrderStatus.COMPLETED
                else -> if (order.amount > remaining) OrderStatus.PARTIAL else OrderStatus.ACTIVE
            }

//            state.onActive(order.id, stockOrderId, order.remaining - remaining, status)
            OrderUpdate(order.id, orderId, order.remaining - remaining, status)
        } ?: OrderUpdate(order.id, "666", BigDecimal.ZERO, OrderStatus.FAILED)
    }

    override suspend fun start() {
        startDepth()
    }

    override suspend fun withdraw(transfer: Transfer): Pair<TransferStatus, String> {
        val data = mapOf("amount" to transfer.amount.toPlainString(), "coinName" to transfer.cur.toUpperCase(),
            "address" to transfer.address.first)

        return apiRequest("WithdrawCoin", withdrawKey, data)?.let {
            Pair(TransferStatus.PENDING, ((it as Map<*, *>)["return"] as Map<*, *>)["tId"].toString())
        } ?: Pair(TransferStatus.FAILED, "")
    }

    private fun rutSymbol(cur: String) = cur.toLowerCase()

    companion object {
        val name = WEX::class.simpleName!!
        val Pairs = mapOf(
            P(C.bch, C.btc) to "",
            P(C.bch, C.dsh) to "",
            P(C.bch, C.eth) to "",
            P(C.bch, C.eur) to "",
            P(C.bch, C.ltc) to "",
            P(C.bch, C.rur) to "",
            P(C.bch, C.usd) to "",
            P(C.bch, C.zec) to "",

            P(C.btc, C.eur) to "",
            P(C.btc, C.rur) to "",
            P(C.btc, C.usd) to "",

            P(C.dsh, C.btc) to "",
            P(C.dsh, C.eth) to "",
            P(C.dsh, C.eur) to "",
            P(C.dsh, C.ltc) to "",
            P(C.dsh, C.rur) to "",
            P(C.dsh, C.usd) to "",
            P(C.dsh, C.zec) to "",

            P(C.eth, C.btc) to "",
            P(C.eth, C.eur) to "",
            P(C.eth, C.ltc) to "",
            P(C.eth, C.rur) to "",
            P(C.eth, C.usd) to "",
            P(C.eth, C.zec) to "",

            P(C.eur, C.rur) to "",
            P(C.eur, C.usd) to "",

            P(C.ltc, C.btc) to "",
            P(C.ltc, C.eur) to "",
            P(C.ltc, C.rur) to "",
            P(C.ltc, C.usd) to "",

            P(C.nmc, C.btc) to "",
            P(C.nmc, C.usd) to "",

            P(C.nvc, C.btc) to "",
            P(C.nvc, C.usd) to "",

            P(C.ppc, C.btc) to "",
            P(C.ppc, C.usd) to "",

            P(C.usd, C.rur) to "",
            P(C.usdt, C.usd) to "",

            P(C.xmr, C.btc) to "",
            P(C.xmr, C.eth) to "",
            P(C.xmr, C.eur) to "",
            P(C.xmr, C.rur) to "",
            P(C.xmr, C.usd) to "",

            P(C.zec, C.btc) to "",
            P(C.zec, C.ltc) to "",
            P(C.zec, C.rur) to "",
            P(C.zec, C.usd) to ""
        )
    }
}
