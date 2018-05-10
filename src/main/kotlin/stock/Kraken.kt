package stock

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import data.Depth
import data.DepthBook
import data.Order
import database.*
import database.RutData.P
import kotlinx.coroutines.experimental.Job
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jsoup.Jsoup
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Kraken(override val kodein: Kodein) : IStock, KodeinAware {
    override val state = State(this::class.simpleName!!, kodein)
    private var tm = TradeManager(state)
    private val coroutines = mutableListOf<Job>()

    private fun getUrl(cmd: String) = Pair("https://api.kraken.com/0/private/$cmd", "/0/private/$cmd")
    private fun getDepthUrl(pair: String) = "https://api.kraken.com/0/public/Depth?pair=$pair&count=${state.depthLimit}"

    private fun browserEmulationString() = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/33.0.1750.152 Safari/537.36"
    private fun minimunOrderSizePageUrl() = "https://support.kraken.com/hc/en-us/articles/205893708-What-is-the-minimum-order-size"

    private fun info(): Map<String, PairInfo>? {
        val htmlList = Jsoup.connect(minimunOrderSizePageUrl())
                .userAgent(browserEmulationString()).get().select("article li").map { it.html() }

        val minAmount = htmlList.map {
            getRutCurrency(".*?\\(([^)]*)\\).*".toRegex().matchEntire(it.split(":")[0])!!.groups[1]!!.value) to
            BigDecimal(it.split(":")[1].trim())
        }.toMap()

        return state.pairs.map { it.key to PairInfo(0, 0, minAmount[it.key.split("_")[0]]!!) }.toMap()
    }

    override fun cancelOrders(orders: List<Order>) {
        orders.forEach {
            val params = mapOf("txid" to it.id)
            val currentOrder = it

            getUrl("CancelOrder").let {
                parseResponse(state.SendRequest(it.first, getApiRequest(state.getTradesKey().first(), it.second, params)))?.let {
                    when (((it["result"] as Map<*, *>)["count"]) as Long) {
                        1L -> state.onActive(currentOrder.id, currentOrder.order_id, currentOrder.amount, OrderStatus.CANCELED)
                        else -> state.logger.error("Order cancellation failed.")
                    }
                } ?: state.logger.error("CancelOrder failed.")
            }
        }
    }

    override fun putOrders(orders: List<Order>) {
        orders.forEach {
            val currOrder = it
            val params = mapOf("pair" to getKrakenPair(it.pair), "type" to it.type, "ordertype" to "limit", "price" to it.rate,
                    "volume" to it.amount, "userref" to it.id)

            state.logger.info("send tp play ${params.entries.joinToString { "${it.key}:${it.value}" }}")
            getUrl("AddOrder").let {
                parseResponse(state.SendRequest(it.first, getApiRequest(state.getTradesKey().first(), it.second, params)))?.let {
                    val ret = (it["result"] as Map<*, *>)
                    val transactionId: String = (ret["txid"] as JSONArray).first() as String
                    val orderDescription = (ret["descr"] as Map<*, *>)
                    val remaining = (orderDescription["order"] as String).split(" ")[1].toBigDecimal()

                    state.logger.info(" thread id: ${Thread.currentThread().id} trade ok: remaining: $remaining  transaction_id: $transactionId")

                    var status: OrderStatus = OrderStatus.COMPLETED
                    if (orderDescription["close"] == null) {
                        status = if (currOrder.amount > remaining)
                            OrderStatus.PARTIAL
                        else
                            OrderStatus.ACTIVE
                    }

                    state.onActive(currOrder.id, currOrder.order_id, currOrder.remaining - remaining, status, false)
                } ?: state.logger.error("OrderPut response failed: $currOrder")
            }
        }
    }

    override fun getBalance(): Map<String, BigDecimal>? {
        return getUrl("Balance").let {
            parseResponse(state.SendRequest(it.first, getApiRequest(state.getHistoryKey(), it.second)))?.let {
                (it["result"] as Map<*, *>)
                        .map { getRutCurrency(it.key.toString()) to BigDecimal(it.value.toString()) }
                        .filter { state.currencies.containsKey(it.first) }.toMap()
            }
        }
    }

    override fun getDepth(updateTo: DepthBook?, pair: String?): DepthBook? {
        val update = updateTo ?: DepthBook()

        parseResponse(state.SendRequest(getDepthUrl(getKrakenPair(pair!!))))?.let {
            (it["result"] as Map<*, *>).forEach {
                val pairName = getRutPair(it.key.toString())

                (it.value as Map<*, *>).forEach {
                    for (i in 0..(state.depthLimit - 1)) {
                        val value = ((it.value as List<*>)[i]) as List<*>
                        update.pairs.getOrPut(pairName) { mutableMapOf() }.getOrPut(BookType.valueOf(it.key.toString())) { mutableListOf() }
                                .add(i, Depth(value[0].toString(), value[1].toString()))
                    }
                }
            }
        }

        return update
    }

    override fun deposit(lastId: Long, transfers: List<Transfer>): Pair<Long, List<TransferUpdate>> {
        val tu = transfers.map { transfer ->
            state.logger.info("txId(${transfer.tId}) status ${transfer.status}")
            var status = transfer.status
            getUrl("DepositStatus").let {
                val param = mapOf("asset" to getKrakenCurrency(transfer.cur))
                parseResponse(state.SendRequest(it.first, getApiRequest(state.getHistoryKey(), it.second, param), 10000))?.let {
                    (it["result"] as List<*>).find { (it as Map<*,*>)["txid"].toString() == transfer.tId }.let { (it as Map<*, *>) }.let {
                        state.logger.info("txId(${it["txid"]}) found status ${it["status"]}")
                        status = when (it["status"].toString()) {
                            "Success" -> TransferStatus.SUCCESS
                            "Failed" -> TransferStatus.FAILED
                            else -> transfer.status
                        }
                    }
                }
            }
            TransferUpdate(transfer.id, status)
        }
        return Pair(lastId, tu)
    }

    private fun getApiRequest(key: StockKey, urlParam: String, data: Any? = null): ApiRequest {
        val nonce = "${++key.nonce}"
        val params = mutableMapOf("nonce" to nonce)
        data?.let { (it as Map<*, *>).forEach { params[it.key as String] = "${it.value}" } }

        val payload = params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

        val hmacMessage = urlParam.toByteArray() + MessageDigest.getInstance("SHA-256").run { digest((nonce + payload).toByteArray()) }

        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(Base64.getDecoder().decode(key.secret), "HmacSHA512")) }
        val sign = Base64.getEncoder().encodeToString(mac.doFinal(hmacMessage))

        state.logger.trace(payload)

        return stock.ApiRequest(mapOf("API-Key" to key.key, "API-Sign" to sign), payload, emptyMap())
    }

    private fun parseResponse(response: String?): JSONObject? = try {
        state.logger.trace(response)

        (JSONParser().parse(response) as JSONObject).let {
            if ((it["error"] as JSONArray).isEmpty()) it else throw Exception(it["error"].toString())
        }
    } catch (e: Exception) {
        state.logger.error(e.message, e)
        null
    }

    override fun getOrderInfo(order: Order, updateTotal: Boolean) {
        val params = mapOf("userref" to order.id)

        getUrl("QueryOrders").let {
            parseResponse(state.SendRequest(it.first, getApiRequest(state.getTradesKey().first(), it.second, params)))?.let {
                val res = (it["result"] as Map<*, *>).values.first() as Map<*, *>
                val partialAmount = BigDecimal(res["vol"].toString())
                val status = if (res["status"].toString() == "open") {
                    if (order.amount > partialAmount) OrderStatus.PARTIAL else OrderStatus.ACTIVE
                } else
                    OrderStatus.COMPLETED

                order.takeIf { it.status != status || it.remaining.compareTo(partialAmount) != 0 }
                        ?.let { state.onActive(it.id, it.order_id, it.remaining - partialAmount, status, updateTotal) }
            } ?: state.logger.error("OrderInfo response failed: $order")
        }
    }

    override suspend fun start() {
        syncWallet()
        state.pairs.forEach { coroutines.add(depth(it.key)) }
        coroutines.addAll(listOf(active(), history(5), info(this@Kraken::info, 5), debugWallet()))
        coroutines.forEach { it.join() }
    }

    override suspend fun stop() {
        state.logger.info("stopping")
//        statePool.shutdown()
        tm.shutdown()
        state.shutdown()
        state.logger.info("stopped")
    }

    override fun withdraw(transfer: Transfer): Pair<TransferStatus, String> {
        val data = mapOf("amount" to transfer.amount.toPlainString(), "asset" to getKrakenCurrency(transfer.cur), "key" to transfer.address.first)

        return getUrl("Withdraw").let {
            parseResponse(state.SendRequest(it.first, getApiRequest(state.getWithdrawKey(), it.second, data)))
                    ?.let { Pair(TransferStatus.PENDING, (it["result"] as Map<*, *>)["refid"] as String) }
                    ?: Pair(TransferStatus.FAILED, "")
        }
    }

    private fun getRutCurrency(cur: String) = when (cur) {
        "XBT" -> C.btc
        "DASH" -> C.dsh
        else -> C.valueOf(cur.drop(1).toLowerCase())
    }.toString()

    private fun getKrakenCurrency(cur: String) = when (cur) {
        "btc" -> "XBT"
        "dsh" -> "DASH"
        else -> C.valueOf(cur.toLowerCase()).toString()
    }

    private fun getKrakenPair(pair: String) = PairsKrakenRutex.findLast { it.second == pair }!!.first

    private fun getRutPair(pair: String) = PairsKrakenRutex.findLast { it.first == pair }!!.second

    companion object {
        val pairs = listOf(
                P(C.btc, C.usd),
                P(C.ltc, C.btc),

                P(C.bch, C.eur),
                P(C.bch, C.usd),
                P(C.bch, C.btc),

                P(C.dsh, C.eur),
                P(C.dsh, C.usd),
                P(C.dsh, C.btc),

                P(C.eos, C.eth),
                P(C.eos, C.eur),
                P(C.eos, C.usd),
                P(C.eos, C.btc),

                P(C.gno, C.eth),
                P(C.gno, C.eur),
                P(C.gno, C.usd),
                P(C.gno, C.btc),

                P(C.usdt, C.usd),

                P(C.etc, C.eth),
                P(C.etc, C.btc),
                P(C.etc, C.eur),
                P(C.etc, C.usd),

                P(C.eth, C.btc),
                P(C.eth, C.cad),
                P(C.eth, C.eur),
                P(C.eth, C.gbp),
                P(C.eth, C.jpy),
                P(C.eth, C.usd),

                P(C.icn, C.eth),
                P(C.icn, C.btc),

                P(C.ltc, C.btc),
                P(C.ltc, C.eur),
                P(C.ltc, C.usd),

                P(C.mln, C.eth),
                P(C.mln, C.btc),

                P(C.rep, C.eth),
                P(C.rep, C.btc),
                P(C.rep, C.eur),
                P(C.rep, C.usd),

                P(C.btc, C.cad),
                P(C.btc, C.eur),
                P(C.btc, C.gbp),
                P(C.btc, C.jpy),
                P(C.btc, C.usd),

                P(C.xdg, C.btc),

                P(C.xlm, C.btc),
                P(C.xlm, C.eur),
                P(C.xlm, C.usd),

                P(C.xmr, C.btc),
                P(C.xmr, C.eur),
                P(C.xmr, C.usd),

                P(C.xrp, C.btc),
                P(C.xrp, C.cad),
                P(C.xrp, C.eur),
                P(C.xrp, C.jpy),
                P(C.xrp, C.usd),

                P(C.zec, C.btc),
                P(C.zec, C.eur),
                P(C.zec, C.jpy),
                P(C.zec, C.usd)
        )

        val PairsKrakenRutex = listOf(
                Pair("BCHEUR", "bch_eur"),
                Pair("BCHUSD", "bch_usd"),
                Pair("BCHXBT", "bch_btc"),

                Pair("DASHEUR", "dsh_eur"),
                Pair("DASHUSD", "dsh_eur"),
                Pair("DASHXBT", "dsh_btc"),

                Pair("EOSETH", "eos_eth"),
                Pair("EOSEUR", "eos_eur"),
                Pair("EOSUSD", "eos_usd"),
                Pair("EOSXBT", "eos_btc"),

                Pair("GNOETH", "gno_eth"),
                Pair("GNOEUR", "gno_eur"),
                Pair("GNOUSD", "gno_usd"),
                Pair("GNOXBT", "gno_btc"),

                Pair("USDTUSD", "usdt_usd"),

                Pair("XETCXETH", "etc_eth"),
                Pair("XETCXXBT", "etc_btc"),
                Pair("XETCZEUR", "etc_eur"),
                Pair("XETCZUSD", "etc_usd"),

                Pair("XETHXXBT", "eth_btc"),
                Pair("XETHZCAD", "eth_cad"),
                Pair("XETHZEUR", "eth_eur"),
                Pair("XETHZGBP", "eth_gbp"),
                Pair("XETHZJPY", "eth_jpy"),
                Pair("XETHZUSD", "eth_usd"),

                Pair("XICNXETH", "icn_eth"),
                Pair("XICNXXBT", "icn_btc"),

                Pair("XLTCXXBT", "ltc_btc"),
                Pair("XLTCZEUR", "ltc_eur"),
                Pair("XLTCZUSD", "ltc_usd"),

                Pair("XMLNXETH", "mln_eth"),
                Pair("XMLNXXBT", "mln_btc"),

                Pair("XREPXETH", "rep_eth"),
                Pair("XREPXXBT", "rep_btc"),
                Pair("XREPZEUR", "rep_eur"),
                Pair("XREPZUSD", "rep_usd"),

                Pair("XXBTZCAD", "btc_cad"),
                Pair("XXBTZEUR", "btc_eur"),
                Pair("XXBTZGBP", "btc_gbp"),
                Pair("XXBTZJPY", "btc_jpy"),
                Pair("XXBTZUSD", "btc_usd"),

                Pair("XXDGXXBT", "xdg_btc"),

                Pair("XXLMXXBT", "xlm_btc"),
                Pair("XXLMZEUR", "xlm_eur"),
                Pair("XXLMZUSD", "xlm_usd"),

                Pair("XXMRXXBT", "xmr_btc"),
                Pair("XXMRZEUR", "xmr_eur"),
                Pair("XXMRZUSD", "xmr_usd"),

                Pair("XXRPXXBT", "xrp_btc"),
                Pair("XXRPZCAD", "xrp_cad"),
                Pair("XXRPZEUR", "xrp_eur"),
                Pair("XXRPZJPY", "xrp_jpy"),
                Pair("XXRPZUSD", "xrp_usd"),

                Pair("XZECXXBT", "zec_btc"),
                Pair("XZECZEUR", "zec_eur"),
                Pair("XZECZJPY", "zec_jpy"),
                Pair("XZECZUSD", "zec_usd")
        )
    }
}