package api.stocks

import api.*
import api.connectors.IConnector
import api.connectors.RestControlConnector
import api.connectors.WebSocketBookConnector
import data.Order
import database.*
import database.RutData.P
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.serialization.Serializable
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jsoup.Jsoup
import org.kodein.di.Kodein
import utils.DepthChannel
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ObsoleteCoroutinesApi
@ExperimentalUnsignedTypes
class Kraken(kodein: Kodein) : Stock(kodein, name) {
    override suspend fun reconnectPair(pair: String) = bookConnector.reconnect()

//    override val bookConnector = RestBookConnector(pairs.keys, 1100, TimeUnit.MILLISECONDS, kodein, logger, { d, b -> depth(d, b) }) {
//        updateBook(it)
//    }

    val bookConnector = WebSocketBookConnector("ws.kraken.com", "", kodein, logger, { msg, conn -> onBookMessage(msg, conn) }) {
        it.send(
            JSONObject(mapOf("event" to "subscribe", "pair" to pairs.map { wsStockPair(it.key) },
                "subscription" to mapOf("name" to "book"))).toJSONString()
        )
    }

    private fun onBookMessage(msg: String, connector: WebSocketBookConnector) = try {
        JSONParser().parse(msg).let { res ->
            when (res) {
                is JSONArray -> {
                    val rutPair = connector.pairChannel[res[0] as Long]!!.rutPair
                    val updateList = mutableListOf<Update>()

                    for (ab in res[1] as Map<*, *>) {
                        val bookType = if ((ab.key as String)[0] == 'a') BookType.asks else BookType.bids
                        (ab.value as List<*>).mapTo(updateList) { depth ->
                            (depth as List<*>).let {
                                val amount = (it[1] as String).takeIf { it != "0.00000000" }?.let { BigDecimal(it) }
                                Update(rutPair, bookType, BigDecimal(it[0] as String), amount, it[2] as String)
                            }
                        }
                    }
                    if ((res[1] as Map<*, *>).size == 2) initPair(rutPair, updateList) else updateBook(updateList)
                }
                is JSONObject -> when (res["event"]) {
                    "systemStatus" -> logger.info { "version: ${res["version"]} id: ${res["connectionID"]}" }
                    "subscriptionStatus" -> {
                        val stockPair = res["pair"] as String
                        connector.pairChannel[res["channelID"] as Long] = DepthChannel(stockPair, wsRutPair(stockPair))
                        logger.info { "${wsRutPair(stockPair)}: ${res["status"]}" }
                    }
                    "heartbeat" -> logger.info { "heartbeat" }
                    else -> logger.info("not handled event: $msg")
                }
                else -> { logger.info("bad JSON: $msg")}
            }
        }
    } catch (e: Exception) {
        logger.error(e) {"MSG: $msg"}
    }

    private val controlConnector = RestControlConnector(getKeys(KeyType.TRADE), kodein, logger, { o, d -> updateActive(o, d) })
    { urlParam, key, data -> auth(urlParam, key, data) }

    private fun infoUrl(str: String) = "https://support.kraken.com/hc/en-us/articles/$str"
    private fun publicApi(cmd: String) = "https://api.kraken.com/0/public/$cmd"
    private fun privateApi(cmd: String) = Pair("https://api.kraken.com/0/private/$cmd", "/0/private/$cmd")

    private suspend fun api(cmd: String, key: StockKey?, data: Map<String, Any>? = null, timeOut: Long = 2000): Map<*, *>? {
        return parseJsonResponse(controlConnector.apiRequest(privateApi(cmd), key, data, timeOut)) as Map<*, *>?
    }

    private fun auth(urlParam: String, key: StockKey, data: Map<String, Any>?): Pair<Map<String, String>, String> {
        val nonce = "${++key.nonce}"

        val params = mutableMapOf<String, Any>("nonce" to nonce).apply { data?.let { putAll(data) } }

        val payload = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value.toString(), "UTF-8")}"
        }

        val hmacMessage = urlParam.toByteArray() + MessageDigest.getInstance("SHA-256").run { digest((nonce + payload).toByteArray()) }

        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(Base64.getDecoder().decode(key.secret), "HmacSHA512")) }
        val sign = Base64.getEncoder().encodeToString(mac.doFinal(hmacMessage))

        return Pair(mapOf("API-Key" to key.key, "API-Sign" to sign), payload)
    }

    override suspend fun balance(key: StockKey?): Map<String, BigDecimal>? = api("Balance", key)?.let { res ->
        (res["result"] as Map<*, *>).map { rutWalletSymbol(it.key.toString()) to BigDecimal(it.value.toString()) }
            .filter { currencies.containsKey(it.first) }.toMap()
    }

    override suspend fun cancelOrders(orders: List<Order>) = controlConnector.parallelOrders(orders) { order, key ->
        api("CancelOrder", key, mapOf("txid" to order.stockOrderId))?.let { res ->
            if ((((res["result"] as Map<*, *>)["count"]) as Long) == 1L) {
                OrderUpdate(order.id, order.stockOrderId, order.amount, OrderStatus.CANCELED)
            } else {
                logger.error("Order cancellation failed.").run {
                    OrderUpdate(order.id, order.stockOrderId, order.amount, OrderStatus.CANCEL_FAILED)
                }
            }

        } ?: logger.error("CancelOrder failed.").run {
            OrderUpdate(order.id, order.stockOrderId, order.amount, OrderStatus.CANCEL_FAILED)
        }
    }

    override suspend fun deposit(lastId: Long, transfers: List<Transfer>, key: StockKey?): Pair<Long, List<TransferUpdate>> {
        val tu = transfers.map { transfer ->
            logger.info("txId(${transfer.tId}) status ${transfer.status}")
            var status = transfer.status
            api("DepositStatus", key, mapOf("asset" to getStockSymbol(transfer.cur)), 10000)?.let { res ->
                (res["result"] as List<*>).find { (it as Map<*, *>)["txid"].toString() == transfer.tId }.let { (it as Map<*, *>) }.let {
                    logger.info("txId(${it["txid"]}) found status ${it["status"]}")
                    status = when (it["status"].toString()) {
                        "Success" -> TransferStatus.SUCCESS
                        "Failed" -> TransferStatus.FAILED
                        else -> transfer.status
                    }
                }
            }

            TransferUpdate(transfer.id, status)
        }
        return Pair(lastId, tu)
    }

//    suspend fun depth(updateTo: DepthBook?, pair: String?): Boolean {
//        val update = updateTo ?: DepthBook()
//        logger.trace("pair - $pair")
//        val url = publicApi("Depth?pair=${stockPair(pair!!)}&count=$depthLimit")
//        parseJsonResponse(bookConnector.http.post(logger, url))?.let { res ->
//            ((res as Map<*, *>)["result"] as Map<*, *>).forEach { (pairName, p) ->
//                (p as Map<*, *>).forEach {
//                    update.replaceFromList(rutPair(pairName.toString()), it.key.toString(),
//                        it.value as List<*>, depthLimit)
//                }
//            }
//            return true
//        }
//        return false
//    }



    override fun handleError(res: Any) = (res as JSONObject)
        .takeIf { (it["error"] as JSONArray).isEmpty() } ?: throw Exception(res["error"].toString())

    override suspend fun pairInfo(key: StockKey?): Map<String, TradeFee>? {
        val html = Jsoup.connect(infoUrl("205893708-What-is-the-minimum-order-size")).userAgent(userAgent).get()
        val list = html.select("table").select("tr").drop(1).map { it.select("td")[1].text() }
        val minAmount = list.map { t -> t.split(" ").let { Pair(rutSymbol(it[1], false), BigDecimal(it[0])) } }.toMap()

        return api("TradeVolume", key, mapOf("pair" to pairs.keys.joinToString { stockPair(it) }))?.let { info ->
            (info["result"] as Map<*, *>).let { res ->
                (res["fees"] as Map<*, *>).map { (stockPair, p) ->
                    val pair = rutPair(stockPair as String)
                    val min = minAmount[pair.split("_")[0]]!!
                    val taker = (p as Map<*, *>)["fee"].toString()
                    val maker = res["fees_maker"]?.let { (it as Map<*, *>)[stockPair] }?.let { (it as Map<*, *>)["fee"].toString() }
                    pair to TradeFee(minAmount = min, takerFee = BigDecimal(taker), makerFee = BigDecimal(maker ?: taker))
                }.toMap()
            }
        }
    }

    override suspend fun currencyInfo(key: StockKey?): Map<String, CrossFee>? {
        val html = Jsoup.connect(infoUrl("201893608-Digital-assets-cryptocurrency-withdrawal-fees")).userAgent(userAgent).get()
        val list = html.select("table").select("tr").drop(1).map { row -> row.select("td").let { it[1].text() to  it[2].text() } }.toMap()
        return list.map {
            val cur = rutSymbol(it.key, false).trimEnd()
            val min = when(cur) {
                "dsh" -> it.value.drop(1).split(" ")[0]
                "icn" -> it.value.drop(1).split(" ")[1]
                else -> it.value.drop(1)
            }
            try {
                cur to CrossFee(withdrawFee = Fee(min = BigDecimal(min)))
            } catch (e: Exception) {
                logger.error { "$cur min value $min failed" }
                null
            }
        }.filterNotNull().toMap()

//        val gg = apiRequest("WithdrawInfo", key, mapOf(
//                "asset" to getStockSymbol(currencies.entries.first().key),
//                "key" to "DevKra",
//                "amount" to "100"))
//        val cc = gg
    }

    /**
     * Returns information about order
     *
     * pending = order pending book entry
     * open = open order
     * closed = closed order
     * canceled = order canceled
     * expired = order expired
     */
    override suspend fun orderInfo(order: Order, updateTotal: Boolean): OrderUpdate? {
        return api("QueryOrders", activeKey, mapOf("txid" to order.stockOrderId))?.let {
            val res = (it["result"] as Map<*, *>).values.first() as Map<*, *>
            val remaining = BigDecimal(res["vol"].toString()) - BigDecimal(res["vol_exec"].toString())
            val status = if (res["status"].toString() == "open" || res["status"].toString() == "pending") {
                if (order.amount > remaining) OrderStatus.PARTIAL else OrderStatus.ACTIVE
            } else {
                if (res["status"].toString() == "closed") {
                    OrderStatus.COMPLETED
                } else {
                    logger.error("order failed, strange order status ${res["status"].toString()} for $order")
                    OrderStatus.FAILED
                }
            }
            //FIXME: refactor this remaining subtraction
            OrderUpdate(order.id, order.stockOrderId, order.remaining - remaining, status)

        } ?: logger.error("OrderInfo response failed: $order").let { null }
    }

    override suspend fun putOrders(orders: List<Order>) = controlConnector.parallelOrders(orders) { order, key ->
        val params = mapOf("pair" to stockPair(order.pair), "type" to order.type.name, "ordertype" to "limit",
            "price" to order.rate.toString(), "volume" to order.amount.toString(), "userref" to order.id.toString())
        logger.info("send tp play ${params.entries.joinToString { "${it.key}:${it.value}" }}")

        api("AddOrder", key, params)?.let {
            val ret = (it["result"] as Map<*, *>)
            val txId: String = (ret["txid"] as JSONArray).first() as String
            val orderDescription = (ret["descr"] as Map<*, *>)
            val remaining = (orderDescription["order"] as String).split(" ")[1].toBigDecimal()

            logger.info(" thread id: ${Thread.currentThread().id} trade ok: remaining: $remaining  transaction_id: $txId")

            var status: OrderStatus = OrderStatus.COMPLETED
            if (orderDescription["close"] == null) {
                status = if (order.amount > remaining)
                    OrderStatus.PARTIAL
                else
                    OrderStatus.ACTIVE
            }

            OrderUpdate(order.id, txId, order.remaining - remaining, status)
        } ?: OrderUpdate(order.id, "666", BigDecimal.ZERO, OrderStatus.FAILED)
            .also { logger.error { "order: ${order.id} falied" } }
    }

    override suspend fun start() {
        super.start()
        listOf<IConnector>(controlConnector, bookConnector).forEach { it.start() }
    }

    override suspend fun stop() {
        listOf<IConnector>(controlConnector, bookConnector).forEach { it.stop() }
        super.stop()
    }

    override suspend fun withdraw(transfer: Transfer): Pair<TransferStatus, String> {
        val data = mapOf("amount" to transfer.amount.toPlainString(), "asset" to getStockSymbol(transfer.cur),
            "key" to transfer.address.first)

        return api("Withdraw", withdrawKey, data)?.let {
            Pair(TransferStatus.PENDING, (it["result"] as Map<*, *>)["refid"] as String)
        } ?: Pair(TransferStatus.FAILED, "")
    }

    private fun rutSymbol(cur: String, dropX: Boolean = true): String {
        return when (cur) {
            "XBT" -> C.btc.name
            "DASH" -> C.dsh.name
            "XDG" -> C.doge.name
            else -> (cur.takeIf { !dropX } ?: cur.drop(1)).toLowerCase()
        }.toString()
    }

    private fun rutWalletSymbol(cur: String) = when (cur) {
        "EOS" -> C.eos.name
        "XXBT" -> C.btc.name
        else -> cur.drop(1).toLowerCase()
    }.toString()

    private fun getStockSymbol(cur: String) = when (cur) {
        "btc" -> "XBT"
        "dsh" -> "DASH"
        else -> C.valueOf(cur.toLowerCase()).toString()
    }

    private fun stockPair(pair: String) = Pairs[pair]!!

    private fun rutPair(pair: String) = Pairs.filter { it.value == pair }.keys.first()

    private fun wsStockPair(pair: String) = pair.replace("btc", "xbt").replace("dsh", "dash").replace("_", "/").toUpperCase()

    private fun wsRutPair(pair: String) = pair.toLowerCase().replace("xbt", "btc").replace("dash", "dsh").replace("/", "_")

    companion object {
        val name = Kraken::class.simpleName!!

        val Pairs = mapOf(
            P(C.ada, C.btc) to "ADAXBT",
            P(C.ada, C.cad) to "ADACAD",
            P(C.ada, C.eth) to "ADAETH",
            P(C.ada, C.eur) to "ADAEUR",
            P(C.ada, C.usd) to "ADAUSD",

            P(C.bch, C.eur) to "BCHEUR",
            P(C.bch, C.usd) to "BCHUSD",
            P(C.bch, C.btc) to "BCHXBT",

            P(C.bsv, C.eur) to "BSVEUR",
            P(C.bsv, C.usd) to "BSVUSD",
            P(C.bsv, C.btc) to "BSVXBT",

            P(C.btc, C.cad) to "XXBTZCAD",
            P(C.btc, C.eur) to "XXBTZEUR",
            P(C.btc, C.gbp) to "XXBTZGBP",
            P(C.btc, C.jpy) to "XXBTZJPY",
            P(C.btc, C.usd) to "XXBTZUSD",

            P(C.doge, C.btc) to "XXDGXXBT",

            P(C.dsh, C.eur) to "DASHEUR",
            P(C.dsh, C.usd) to "DASHUSD",
            P(C.dsh, C.btc) to "DASHXBT",

            P(C.eos, C.eth) to "EOSETH",
            P(C.eos, C.eur) to "EOSEUR",
            P(C.eos, C.usd) to "EOSUSD",
            P(C.eos, C.btc) to "EOSXBT",

            P(C.etc, C.eth) to "ETCETH",
            P(C.etc, C.eur) to "ETCEUR",
            P(C.etc, C.usd) to "ETCUSD",
            P(C.etc, C.btc) to "ETCXBT",

            P(C.gno, C.eth) to "GNOETH",
            P(C.gno, C.eur) to "GNOEUR",
            P(C.gno, C.usd) to "GNOUSD",
            P(C.gno, C.btc) to "GNOXBT",

            P(C.usdt, C.usd) to "USDTZUSD",

            P(C.etc, C.eth) to "XETCXETH",
            P(C.etc, C.btc) to "XETCXXBT",
            P(C.etc, C.eur) to "XETCZEUR",
            P(C.etc, C.usd) to "XETCZUSD",

            P(C.eth, C.btc) to "XETHXXBT",
            P(C.eth, C.cad) to "XETHZCAD",
            P(C.eth, C.eur) to "XETHZEUR",
            P(C.eth, C.gbp) to "XETHZGBP",
            P(C.eth, C.jpy) to "XETHZJPY",
            P(C.eth, C.usd) to "XETHZUSD",

            P(C.ltc, C.btc) to "XLTCXXBT",
            P(C.ltc, C.eur) to "XLTCZEUR",
            P(C.ltc, C.usd) to "XLTCZUSD",

            P(C.mln, C.eth) to "XMLNXETH",
            P(C.mln, C.btc) to "XMLNXXBT",

            P(C.qtum, C.btc) to "QTUMXBT",
            P(C.qtum, C.cad) to "QTUMCAD",
            P(C.qtum, C.eth) to "QTUMETH",
            P(C.qtum, C.eur) to "QTUMEUR",
            P(C.qtum, C.usd) to "QTUMUSD",

            P(C.rep, C.eth) to "XREPXETH",
            P(C.rep, C.btc) to "XREPXXBT",
            P(C.rep, C.eur) to "XREPZEUR",
            P(C.rep, C.usd) to "XREPZUSD",

            P(C.xlm, C.btc) to "XXLMXXBT",
            P(C.xlm, C.eur) to "XXLMZEUR",
            P(C.xlm, C.usd) to "XXLMZUSD",

            P(C.xmr, C.btc) to "XXMRXXBT",
            P(C.xmr, C.eur) to "XXMRZEUR",
            P(C.xmr, C.usd) to "XXMRZUSD",

            P(C.xrp, C.btc) to "XXRPXXBT",
            P(C.xrp, C.cad) to "XXRPZCAD",
            P(C.xrp, C.eur) to "XXRPZEUR",
            P(C.xrp, C.jpy) to "XXRPZJPY",
            P(C.xrp, C.usd) to "XXRPZUSD",

            P(C.xtz, C.btc) to "XTZXBT",
            P(C.xtz, C.cad) to "XTZCAD",
            P(C.xtz, C.eth) to "XTZETH",
            P(C.xtz, C.eur) to "XTZEUR",
            P(C.xtz, C.usd) to "XTZUSD",

            P(C.zec, C.btc) to "XZECXXBT",
            P(C.zec, C.eur) to "XZECZEUR",
            P(C.zec, C.jpy) to "XZECZJPY",
            P(C.zec, C.usd) to "XZECZUSD"
        )
    }
}