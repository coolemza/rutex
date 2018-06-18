package stock

import com.github.salomonbrys.kodein.Kodein
import data.Depth
import data.DepthBook
import data.Order
import database.*
import database.RutData.P
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.cancelAndJoin
import org.apache.commons.codec.binary.Hex
import java.math.BigDecimal
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WEX(kodein: Kodein): RestStock(kodein, WEX::class.simpleName!!) {

    private val coroutines = mutableListOf<Job>()

    private fun publicApi(param: String) = "https://wex.nz/api/3/$param"
    private val infoUrl = publicApi("info")
    private val depthUrl = publicApi("depth/${pairs.keys.joinToString("-")}?limit=$depthLimit")

    private val privateApi: String = "https://wex.nz/tapi/"

    override fun apiRequest(cmd: String, key: StockKey, data: Map<String, String>?, timeOut: Long): Any? {
        logger.trace("in key - $key")
        val params = mutableMapOf("method" to cmd, "nonce" to "${++key.nonce}").apply { data?.let { putAll(it) } }
//        data?.let { (it as Map<*, *>).forEach { params[it.key as String] = "${it.value}" } }

        val postData = params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA512"))
        val sign = Hex.encodeHexString(mac.doFinal(postData.toByteArray(charset("UTF-8"))))

        logger.trace("out key - $key")

        return parseJsonResponse(SendRequest(privateApi, mapOf("Key" to key.key, "Sign" to sign), postData))
    }

    override fun balance(key: StockKey): Map<String, BigDecimal>? = apiRequest("getInfo", key)?.let {
        (((it as Map<*, *>)["return"] as Map<*, *>)["funds"] as Map<*, *>)
                .filter { currencies.containsKey(it.key.toString()) }
                .map { it.key.toString() to BigDecimal(it.value.toString()) }.toMap()
    }

    override suspend fun cancelOrders(orders: List<Order>) = parallelOrders(orders) { order, key ->
        apiRequest("CancelOrder", key, mapOf("order_id" to order.stockOrderId))?.let {
            logger.info("order_id: ${((it as Map<*, *>)["return"] as Map<*, *>)["order_id"]} canceled")
//            state.onActive(order.id, order.order_id, status = OrderStatus.CANCELED)
            OrderUpdate(order.id, order.stockOrderId, order.amount, OrderStatus.CANCELED)
        } ?: logger.error("Order cancelling failed: $order").let { null }
    }

    override fun deposit(lastId: Long, transfers: List<Transfer>, key: StockKey): Pair<Long, List<TransferUpdate>> {
        var newLastId = lastId
        var stopIncrement = false
        val tu = mutableListOf<TransferUpdate>()
        val param = mapOf("order" to "DESC", "from_id" to lastId.toString())

        apiRequest("TransHistory", key, param)?.also {
            //TODO: int or Long? or String?
            val res = ((it as Map<*, *>)["return"] as Map<*, *>).map { it.key as String to it.value }.toMap().toSortedMap()
            if (res.containsKey(lastId.toString())) {
                res.remove(lastId.toString())
                if (res.isNotEmpty()) {
                    res.map { it.key as String to it.value as Map<*, *> }.toMap().forEach {
                        if (it.value["type"] == 1L) {
                            val cur = (it.value["currency"] as String).toLowerCase()
                            val amount = BigDecimal(it.value["amount"].toString())
                            val status = it.value["status"].toString().toInt()
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
                            newLastId = it.key.toLong()
                        }
                    }
                }
            } else {
                logger.error("history id not found!!!")
            }
        } ?: logger.error("updateHistory failed")

        return Pair(newLastId, tu)
    }

    override fun depth(updateTo: DepthBook?, pair: String?): DepthBook? {
        val update = updateTo ?: DepthBook()
        return parseJsonResponse(SendRequest(depthUrl))?.let {
            (it as Map<*, *>).forEach {
                val wexPair = it.key.toString()
                (it.value as Map<*, *>).forEach {
                    for (i in 0..(depthLimit - 1)) { //TODO: optimize (depthLimit - 1)
                        val value = ((it.value as List<*>)[i]) as List<*>
                        update.pairs.getOrPut(wexPair) { mutableMapOf() }.getOrPut(BookType.valueOf(it.key.toString())) { mutableListOf() }
                                .add(i, Depth(value[0].toString(), value[1].toString()))
                    }
                }
            }
            return@let update
        }
    }

    override fun handleError(res: Any) = (res as Map<*, *>).let {
        it.takeIf { !it.contains("success") || it["success"] == 1L || it["error"] == "no orders" } ?: throw Exception(it["error"].toString())
    }

     override fun info(): Map<String, PairInfo>? {
        return parseJsonResponse(SendRequest(infoUrl))?.let {
            ((it as Map<*, *>)["pairs"] as Map<*, *>).filter { pairs.containsKey(it.key.toString()) }.map {
                it.key.toString() to PairInfo(pairs[it.key]!!.pairId, pairs[it.key]!!.stockPairId,
                        BigDecimal((it.value as Map<*, *>)["min_amount"].toString()))
            }.toMap()
        }
    }

    override fun orderInfo(order: Order, updateTotal: Boolean, key: StockKey): OrderUpdate? {
        return apiRequest("OrderInfo", key, mapOf("order_id" to order.stockOrderId))?.let {
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
        val params = mapOf("pair" to order.pair, "type" to order.type, "rate" to order.rate.toString(),
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
        syncWallet()
        coroutines.addAll(listOf(active(), depthPolling(), history(1), infoPolling(), debugWallet()))
        coroutines.forEach { it.join() }
    }

    override suspend fun stop() {
        logger.info("stopping")
        coroutines.forEach { it.cancelAndJoin() }
        shutdown()
        logger.info("stopped")
    }

    override fun withdraw(transfer: Transfer, key: StockKey): Pair<TransferStatus, String> {
        val data = mapOf("amount" to transfer.amount.toPlainString(), "coinName" to transfer.cur.toUpperCase(),
                "address" to transfer.address.first)

        return apiRequest("WithdrawCoin", key, data)?.let {
            Pair(TransferStatus.PENDING, ((it as Map<*, *>)["return"] as Map<*, *>)["tId"].toString())
        } ?: Pair(TransferStatus.FAILED, "")
    }

    companion object {
        val Pairs = listOf(
                P(C.bch, C.btc),
                P(C.bch, C.dsh),
                P(C.bch, C.eth),
                P(C.bch, C.eur),
                P(C.bch, C.ltc),
                P(C.bch, C.rur),
                P(C.bch, C.usd),
                P(C.bch, C.zec),

                P(C.btc, C.eur),
                P(C.btc, C.rur),
                P(C.btc, C.usd),

                P(C.dsh, C.btc),
                P(C.dsh, C.eth),
                P(C.dsh, C.eur),
                P(C.dsh, C.ltc),
                P(C.dsh, C.rur),
                P(C.dsh, C.usd),
                P(C.dsh, C.zec),

                P(C.eth, C.btc),
                P(C.eth, C.eur),
                P(C.eth, C.ltc),
                P(C.eth, C.rur),
                P(C.eth, C.usd),
                P(C.eth, C.zec),

                P(C.eur, C.rur),
                P(C.eur, C.usd),

                P(C.ltc, C.btc),
                P(C.ltc, C.eur),
                P(C.ltc, C.rur),
                P(C.ltc, C.usd),

                P(C.nmc, C.btc),
                P(C.nmc, C.usd),

                P(C.nvc, C.btc),
                P(C.nvc, C.usd),

                P(C.ppc, C.btc),
                P(C.ppc, C.usd),

                P(C.usd, C.rur),

                P(C.zec, C.btc),
                P(C.zec, C.ltc),
                P(C.zec, C.usd)
        )
    }
}
