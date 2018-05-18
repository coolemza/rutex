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
import java.math.BigDecimal
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WEX(override val kodein: Kodein) : IStock, KodeinAware {
    override val state = State(this::class.simpleName!!, kodein)
    private val coroutines = mutableListOf<Job>()

    private fun publicApi(param: String) = "https://wex.nz/api/3/$param"
    private val infoUrl = publicApi("info")
    private val depthUrl = publicApi("depth/${state.pairs.keys.joinToString("-")}?limit=${state.depthLimit}")

    private val privateApi: String = "https://wex.nz/tapi/"

    private fun apiRequest(cmd: String, key: StockKey, data: Any? = null): Map<*,*>? {
        val params = mutableMapOf("method" to cmd, "nonce" to "${++key.nonce}")
        data?.let { (it as Map<*, *>).forEach { params[it.key as String] = "${it.value}" } }

        val postData = params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA512"))
        val sign = Hex.encodeHexString(mac.doFinal(postData.toByteArray(charset("UTF-8"))))

        return parseResponse(state.SendRequest(privateApi, mapOf("Key" to key.key, "Sign" to sign), postData)) as Map<*, *>
    }

    override fun balance(): Map<String, BigDecimal>? = apiRequest("getInfo", state.getWalletKey())?.let {
        ((it["return"] as Map<*, *>)["funds"] as Map<*, *>)
                .filter { state.currencies.containsKey(it.key.toString()) }
                .map { it.key.toString() to BigDecimal(it.value.toString()) }.toMap()
    }

    override suspend fun cancelOrders(orders: List<Order>) = parallelOrders(orders) { order, key ->
        apiRequest("CancelOrder", key, mapOf("order_id" to order.order_id))?.let {
            state.logger.info("order_id: ${(it["return"] as Map<*, *>)["order_id"]} canceled")
            state.onActive(order.id, order.order_id, status = OrderStatus.CANCELED)
        } ?: state.logger.error("OrderInfo failed: $order")
    }

    override fun deposit(lastId: Long, transfers: List<Transfer>): Pair<Long, List<TransferUpdate>> {
        var newLastId = lastId
        var stopIncrement = false
        val tu = mutableListOf<TransferUpdate>()
        val param = mapOf("order" to "DESC", "from_id" to lastId)

        apiRequest("TransHistory", state.getHistoryKey(), param)?.also {
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
                state.logger.error("history id not found!!!")
            }
        } ?: state.logger.error("updateHistory failed")

        return Pair(newLastId, tu)
    }

    override fun depth(updateTo: DepthBook?, pair: String?): DepthBook? {
        val update = updateTo ?: DepthBook()
        return parseResponse(state.SendRequest(depthUrl))?.let {
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
            return@let update
        }
    }

    override fun handleError(res: Any) = (res as Map<*, *>).let {
        it.takeIf { !it.contains("success") || it["success"] == 1L || it["error"] == "no orders" } ?: throw Exception(it["error"].toString())
    }

    private fun info(): Map<String, PairInfo>? {
        return parseResponse(state.SendRequest(infoUrl))?.let {
            ((it as Map<*, *>)["pairs"] as Map<*, *>).filter { state.pairs.containsKey(it.key.toString()) }.map {
                it.key.toString() to PairInfo(state.pairs[it.key]!!.pairId, state.pairs[it.key]!!.stockPairId,
                        BigDecimal((it.value as Map<*, *>)["min_amount"].toString()))
            }.toMap()
        }
    }

    override fun orderInfo(order: Order, updateTotal: Boolean) {
        apiRequest("OrderInfo", state.getActiveKey(), mapOf("order_id" to order.order_id))?.let {
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

    override suspend fun putOrders(orders: List<Order>) = parallelOrders(orders) { order, key ->
        val params = mapOf("pair" to order.pair, "type" to order.type, "rate" to order.rate.toString(),
                "amount" to order.amount.toString())
        state.logger.info("send to play ${params.entries.joinToString { "${it.key}:${it.value}" }}")

        apiRequest("Trade", key, params)?.let {
            val ret = (it["return"] as Map<*, *>)

            state.logger.info(" thread id: ${Thread.currentThread().id} trade ok: received: ${ret["received"]} " +
                    "remains: ${ret["remains"]} order_id: ${ret["order_id"]}")

            val orderId = ret["order_id"].toString().toLong()
            val remaining = BigDecimal(ret["remains"].toString())

            val status = when (orderId) {
                0L -> OrderStatus.COMPLETED
                else -> if (order.amount > remaining) OrderStatus.PARTIAL else OrderStatus.ACTIVE
            }

            state.onActive(order.id, orderId, order.remaining - remaining, status)
        } ?: state.onActive(order.id, 666, BigDecimal.ZERO, OrderStatus.FAILED)
    }

    override suspend fun start() {
        syncWallet()
        coroutines.addAll(listOf(active(), depthPolling(), history(1), info(this@WEX::info, 5), debugWallet()))
        coroutines.forEach { it.join() }
    }

    override suspend fun stop() {
        state.logger.info("stopping")
        coroutines.forEach { it.cancelAndJoin() }
        state.shutdown()
        state.logger.info("stopped")
    }

    override fun withdraw(transfer: Transfer): Pair<TransferStatus, String> {
        val data = mapOf("amount" to transfer.amount.toPlainString(), "coinName" to transfer.cur.toUpperCase(),
                "address" to transfer.address.first)

        return apiRequest("WithdrawCoin", state.getWithdrawKey(), data)?.let {
            Pair(TransferStatus.PENDING, (it["return"] as Map<*, *>)["tId"].toString())
        } ?: Pair(TransferStatus.FAILED, "")
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
